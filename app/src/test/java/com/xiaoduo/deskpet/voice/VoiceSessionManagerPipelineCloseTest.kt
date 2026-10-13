package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.agui.AguiClient
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
 * VoiceSessionManager.runPipeline 异常路径资源收尾单测（BLOCKED.md backlog #10）：
 *
 * chat 内非 IOException 逃逸（此处由 transcriptSink.onAssistantDelta 抛 RuntimeException
 * 注入——AguiClient.chat 只 catch IOException，异常原样上抛）时，catch 分支必须 close
 * sentences channel；否则 ttsConsumer 悬挂在空 channel 的 receive 上，每异常轮泄漏一个
 * 挂起协程（scope 不取消就永不回收）。
 *
 * 断言：onAssistantError 触发后，scope 下不得残留 active 子协程（pipelineJob 异常终结 +
 * ttsConsumer 随 channel close 正常落地）。修复前 ttsConsumer 悬挂 → 本测试红。
 *
 * 管线经 internal onUtterance 直造 VAD 事件驱动（绕开 AudioRecord），HTTP 层 MockWebServer
 * 全桩、被测对象零 mock（同 VoiceSessionManagerTranscriptTest 体例）。
 */
class VoiceSessionManagerPipelineCloseTest {

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

    /** 气泡桩：行为不断言（本票零变化），只为让管线跑通。 */
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

    /** 首 delta 即抛 RuntimeException 的 sink：注入「chat 内异常逃逸」路径。 */
    private inner class ThrowingSink : VoiceSessionManager.TranscriptSink {
        val events = CopyOnWriteArrayList<String>()
        val errorLatch = CountDownLatch(1)

        override fun onUserUtterance(text: String) {
            events.add("user:$text")
        }

        override fun onAssistantDelta(delta: String) {
            throw RuntimeException("boom")
        }

        override fun onAssistantDone() {
            events.add("done")
        }

        override fun onAssistantError(message: String) {
            events.add("error:$message")
            errorLatch.countDown()
        }
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

    private fun newManager(sink: ThrowingSink): VoiceSessionManager {
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
            logger = {},
        ).also { it.transcriptSink = sink }
    }

    private fun asrOk(text: String) =
        MockResponse().setBody("""{"code":200,"msg":"ok","data":{"text":"$text"}}""")

    // token 必须是 36 字符 UUID 形态（AguiClient 登录响应校验，否则判格式非法）
    private fun loginOk() =
        MockResponse().setBody("""{"code":200,"msg":"ok","token":"00000000-0000-0000-0000-000000000001"}""")

    private fun threadOk() = MockResponse().setBody("""{"code":200,"msg":"ok","data":"thread-test"}""")

    /** 单 delta（无句末标点 → 分句器不产出，channel 保持空）+ 正常收尾事件。 */
    private fun streamingSse(delta: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            (
                listOf(
                    """{"type":"RUN_STARTED","runId":"r"}""",
                    """{"type":"TEXT_MESSAGE_START","messageId":"m1","role":"assistant"}""",
                    """{"type":"TEXT_MESSAGE_CONTENT","messageId":"m1","delta":"$delta"}""",
                    """{"type":"TEXT_MESSAGE_END","messageId":"m1"}""",
                    """{"type":"RUN_FINISHED","runId":"r"}""",
                )
                ).joinToString("\n\n") { "data: $it" } + "\n\n",
        )

    @Test
    fun chatExceptionClosesSentencesChannelSoTtsConsumerDoesNotHang() {
        val sink = ThrowingSink()
        val mgr = newManager(sink)
        server.enqueue(asrOk("小朵小朵今天天气怎么样"))
        server.enqueue(loginOk())
        server.enqueue(threadOk())
        server.enqueue(streamingSse("好的"))

        mgr.onUtterance(VadDetector.Event.Utterance(ShortArray(1600) { 100 }, 0.5))

        val errored = sink.errorLatch.await(20, TimeUnit.SECONDS)
        assertTrue("onAssistantError 应在 20s 内到达; events=${sink.events}", errored)
        assertEquals(
            listOf("user:今天天气怎么样", "error:管线异常: boom"),
            sink.events.toList(),
        )

        // 核心断言：异常轮收尾后 scope 下不得残留 active 子协程。
        // ttsConsumer 悬挂在空 channel receive 上（channel 未 close）→ 此处红。
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
            "异常轮后仍有挂起协程（sentences channel 未 close → ttsConsumer 悬挂）: $activeChildren",
            activeChildren.isEmpty(),
        )
    }
}
