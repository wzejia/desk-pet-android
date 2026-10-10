package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.agui.AguiClient
import com.xiaoduo.deskpet.voice.audio.AudioCapture
import com.xiaoduo.deskpet.voice.audio.VadDetector
import com.xiaoduo.deskpet.voice.client.AsrClient
import com.xiaoduo.deskpet.voice.client.TtsClient
import com.xiaoduo.deskpet.voice.pipeline.VoiceSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * FUNC-6：runBlocking{sentences.send} 不可取消 → 生产者死亡后 IO 线程永久挂死。
 *
 * 场景构造：TTS 持续失败 → 生产者打「TTS 单句失败」日志时定向抛出（测试注入）→
 * 生产者协程死亡（其 finally 只关 audioQueue，不关 sentences；consumeSentences 收尾
 * 也只 producer.cancel）→ 管线继续把 6 句灌进 Channel(4)：句 2-5 入缓冲，句 6 的
 * `runBlocking { sentences.send(s) }`（不在 pipelineJob 取消域）永挂 = 每次泄漏 1 个
 * IO 线程，反复打断长回复可耗尽 Dispatchers.IO。
 *
 * 修复：consumeSentences finally 补 sentences.cancel()——挂死的 send 立即以
 * CancellationException 解脱，走管线 catch(Throwable) 的失败轮收尾。
 * （评审勘误：kotlinx 1.8.1 的 close() 只拒新 send、不解脱已挂起 sender——
 *   实测实证 cancel() 才对；评审 P3-1 指出本文档原写 close() 系简报处方陈旧。）
 * 修复前：onAssistantError 永不到达 → 本测试 20s 超时红。
 */
class VoiceSessionManagerFunc6PipelineHangTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope

    private class MemTokenCache : AguiClient.TokenCache {
        var token: String? = null
        override fun cached(): String? = token
        override fun saveFresh(token: String) {
            this.token = token
        }
        override fun clear() {
            token = null
        }
    }

    private class MemThreadStore : AguiClient.ThreadIdStore {
        val map = HashMap<String, String>()
        override fun load(key: String): String? = map[key]
        override fun save(key: String, threadId: String) {
            map[key] = threadId
        }
        override fun remove(key: String) {
            map.remove(key)
        }
    }

    private object NoopBubble : VoiceSessionManager.BubbleSink {
        override fun show(text: String) {}
        override fun append(delta: String) {}
        override fun hide() {}
        override fun hideLater(delayMs: Long) {}
    }

    private class MemStreamSupport : TtsClient.StreamSupport {
        override fun isUnsupported(): Boolean = false
        override fun markUnsupported() {}
    }

    private class RecordingSink : VoiceSessionManager.TranscriptSink {
        val events = CopyOnWriteArrayList<String>()
        val errorLatch = CountDownLatch(1)

        override fun onUserUtterance(text: String) {
            events.add("user:$text")
        }

        override fun onAssistantDelta(delta: String) {
            events.add("delta:${delta.length}")
        }

        override fun onAssistantDone() {
            events.add("done")
        }

        override fun onAssistantError(message: String) {
            events.add("error:$message")
            errorLatch.countDown()
        }
    }

    /** 定向引爆：仅生产者独有的「TTS 单句失败」日志路径抛出，杀生产者协程。 */
    private val producerKillerLogger: (String) -> Unit = { msg ->
        if (msg.startsWith("TTS 单句失败")) throw RuntimeException("producer killed by test")
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun newManager(sink: RecordingSink): VoiceSessionManager {
        val asr = AsrClient(OkHttpClient(), server.url("/asr").toString(), logger = {})
        val tts = TtsClient(
            OkHttpClient(),
            streamUrl = server.url("/tts/stream").toString(),
            synthesizeUrl = server.url("/tts/synthesize").toString(),
            streamSupport = MemStreamSupport(),
            logger = {},
        )
        val agui = AguiClient(
            http = OkHttpClient(),
            loginUrl = server.url("/login").toString(),
            streamUrl = server.url("/ai-server/agent/stream").toString(),
            credentials = { "admin" to "test-password" },
            tokenCache = MemTokenCache(),
            threadStore = MemThreadStore(),
            logger = {},
        )
        return VoiceSessionManager(
            capture = AudioCapture(),
            asr = asr,
            tts = tts,
            aguiFactory = { agui },
            bubble = NoopBubble,
            scope = scope,
            logger = producerKillerLogger,
        ).also { it.transcriptSink = sink }
    }

    private fun asrOk(text: String) =
        MockResponse().setBody("""{"code":200,"msg":"ok","data":{"text":"$text"}}""")

    private fun loginOk() =
        MockResponse().setBody("""{"code":200,"msg":"ok","token":"00000000-0000-0000-0000-000000000001"}""")

    private fun threadOk() = MockResponse().setBody("""{"code":200,"msg":"ok","data":"thread-test"}""")

    private fun tts500() = MockResponse().setResponseCode(500)

    /** 6 个 delta、每个 5 字符句（≥MIN4 且句末标点）→ 每delta 一句，共 6 句。 */
    private fun streamingSse() = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            (
                listOf(
                    """{"type":"RUN_STARTED","runId":"r"}""",
                ) + (1..6).map {
                    """{"type":"TEXT_MESSAGE_CONTENT","messageId":"m1","delta":"甲乙$it。丁。"}"""
                } + listOf(
                    """{"type":"RUN_FINISHED","runId":"r"}""",
                )
                ).joinToString("\n\n") { "data: $it" } + "\n\n",
        )

    @Test
    fun `FUNC-6 producer death must not hang pipeline sender forever`() {
        val sink = RecordingSink()
        val mgr = newManager(sink)
        server.enqueue(asrOk("小朵小朵今天天气怎么样"))
        server.enqueue(loginOk())
        server.enqueue(threadOk())
        server.enqueue(streamingSse())
        server.enqueue(tts500()) // 句1：TTS 流式主路径失败
        server.enqueue(tts500()) // 句1：非流式兜底也失败 → Result.Error → 生产者日志 → 引爆

        mgr.onUtterance(VadDetector.Event.Utterance(ShortArray(1600) { 100 }, 0.5))

        // 全部 delta 被消费（管线推进到灌满 channel 为止的旁证）
        val deadline0 = System.currentTimeMillis() + 10_000
        while (sink.events.count { it.startsWith("delta:") } < 6 && System.currentTimeMillis() < deadline0) {
            Thread.sleep(50)
        }
        assertEquals(6, sink.events.count { it.startsWith("delta:") })
        assertEquals(1, sink.events.count { it.startsWith("user:") })

        // 核心断言：管线线程必须被解脱——scope 下不得残留 active 子协程。
        // 修复前：句6 的 runBlocking{send}（不在 pipelineJob 取消域）在 channel 打满后永挂
        // → pipeline 协程永 active → 此处 10s 内排不空 → 红。
        val deadline = System.currentTimeMillis() + 10_000
        var activeChildren: List<String>
        do {
            activeChildren = scope.coroutineContext.job.children
                .filter { it.isActive }
                .map { it.toString() }
                .toList()
            if (activeChildren.isEmpty()) break
            Thread.sleep(50)
        } while (System.currentTimeMillis() < deadline)
        assertTrue(
            "生产者死亡后仍有挂起协程（挂死 send 未被解脱 = IO 线程泄漏）: $activeChildren",
            activeChildren.isEmpty(),
        )
    }
}
