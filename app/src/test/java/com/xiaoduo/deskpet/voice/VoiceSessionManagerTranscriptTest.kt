package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.agui.AguiClient
import com.xiaoduo.deskpet.voice.audio.AudioCapture
import com.xiaoduo.deskpet.voice.audio.VadDetector
import com.xiaoduo.deskpet.voice.client.AsrClient
import com.xiaoduo.deskpet.voice.client.TtsClient
import com.xiaoduo.deskpet.voice.pipeline.VoiceSessionManager
import com.xiaoduo.deskpet.voice.pipeline.VoiceStateRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * VoiceSessionManager 管线 TranscriptSink 流式契约单测（语音轮流式上屏，MockWebServer
 * 端到端桩，参照 AguiClientTest 写法：HTTP 层全桩、被测对象零 mock）。
 *
 * 覆盖票据验收点：
 * - 成功轮：onUserUtterance 在 AG-UI 调用之前触发（user 立即上屏）→ onAssistantDelta
 *   逐 delta 按序多次 → onAssistantDone 收尾；
 * - AG-UI 失败：user 已上屏 + onAssistantError（无 delta 无 done）；
 * - ASR 失败 / 剥前缀后为空：sink 零触发（user 不上屏）。
 *
 * 管线经 internal onUtterance 直造 VAD 事件驱动（绕开 AudioRecord）；
 * TTS 两 URL 均返 500 走单句失败降级（纯文字 reveal），不触碰 AudioTrack。
 */
class VoiceSessionManagerTranscriptTest {

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

    /** 记录型 sink：事件序 + 终态（done/error）latch + user 触发时刻的服务端请求数。 */
    private inner class RecordingSink : VoiceSessionManager.TranscriptSink {
        val events = CopyOnWriteArrayList<String>()
        val terminalLatch = CountDownLatch(1)

        /** onUserUtterance 触发瞬间已到达服务端的请求数（验证 user 在 AG-UI 调用之前上屏）。 */
        @Volatile
        var requestsAtUser = -1

        override fun onUserUtterance(text: String) {
            requestsAtUser = server.requestCount
            events.add("user:$text")
        }

        override fun onAssistantDelta(delta: String) {
            events.add("delta:$delta")
        }

        override fun onAssistantDone() {
            events.add("done")
            terminalLatch.countDown()
        }

        override fun onAssistantError(message: String) {
            events.add("error:$message")
            terminalLatch.countDown()
        }
    }

    private class MemStreamSupport : TtsClient.StreamSupport {
        override fun isUnsupported(): Boolean = false
        override fun markUnsupported() {}
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
            logger = {},
        ).also { it.transcriptSink = sink }
    }

    private fun utterance() = VadDetector.Event.Utterance(ShortArray(1600) { 100 }, 0.5)

    private fun asrOk(text: String) =
        MockResponse().setBody("""{"code":200,"msg":"ok","data":{"text":"$text"}}""")

    // token 必须是 36 字符 UUID 形态（AguiClient 登录响应校验，否则判格式非法）
    private fun loginOk() =
        MockResponse().setBody("""{"code":200,"msg":"ok","token":"00000000-0000-0000-0000-000000000001"}""")

    private fun threadOk() = MockResponse().setBody("""{"code":200,"msg":"ok","data":"thread-test"}""")

    /** 多 delta 流式 SSE：每个 content 事件独立一段，验证逐 delta 实时上屏。 */
    private fun streamingSse(vararg deltas: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            (
                listOf(
                    """{"type":"RUN_STARTED","runId":"r"}""",
                    """{"type":"TEXT_MESSAGE_START","messageId":"m1","role":"assistant"}""",
                ) +
                    deltas.map { """{"type":"TEXT_MESSAGE_CONTENT","messageId":"m1","delta":"$it"}""" } +
                    listOf(
                        """{"type":"TEXT_MESSAGE_END","messageId":"m1"}""",
                        """{"type":"RUN_FINISHED","runId":"r"}""",
                    )
                ).joinToString("\n\n") { "data: $it" } + "\n\n",
        )

    private fun http500(msg: String) =
        MockResponse().setResponseCode(500).setBody("""{"code":500,"msg":"$msg"}""")

    /** 状态行闩：失败路径以 status 文案为管线跑完信号（sink 零触发无回调可等）。 */
    private fun awaitStatus(mgr: VoiceSessionManager, prefix: String) {
        val latch = CountDownLatch(1)
        mgr.statusListener = { s -> if (s.startsWith(prefix)) latch.countDown() }
        assertTrue("expected status '$prefix…' within 10s", latch.await(10, TimeUnit.SECONDS))
    }

    /** 状态闩：轮询等状态迁移（10ms 间隔，5s 超时；与 WakeAckTest 同款）。 */
    private fun awaitState(mgr: VoiceSessionManager, target: VoiceStateRouter.State, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (mgr.state != target && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("expected state $target within ${timeoutMs}ms", target, mgr.state)
    }

    @Test
    fun successRoundFiresUserBeforeAguiThenDeltasThenDone() {
        val sink = RecordingSink()
        val mgr = newManager(sink)
        val statuses = CopyOnWriteArrayList<String>()
        mgr.statusListener = { statuses.add(it) }
        // 到达序：ASR → login → createThread → SSE；TTS 两句请求在 SSE 之后（均为 500 走降级 reveal）
        server.enqueue(asrOk("小朵小朵今天天气怎么样"))
        server.enqueue(loginOk())
        server.enqueue(threadOk())
        server.enqueue(streamingSse("好的，", "今天晴。"))
        server.enqueue(http500("tts stream down"))
        server.enqueue(http500("tts synthesize down"))

        mgr.onUtterance(utterance())

        val ok = sink.terminalLatch.await(20, TimeUnit.SECONDS)
        assertTrue(
            "done 应在 20s 内到达; statuses=$statuses; requests=${server.requestCount}; events=${sink.events}",
            ok,
        )
        assertEquals(
            listOf("user:今天天气怎么样", "delta:好的，", "delta:今天晴。", "done"),
            sink.events.toList(),
        )
        assertEquals(
            "user 上屏时服务端应只收到 ASR 1 个请求（AG-UI login/thread/stream 均未发出）",
            1,
            sink.requestsAtUser,
        )
    }

    @Test
    fun aguiFailureFiresUserThenError() {
        val sink = RecordingSink()
        val mgr = newManager(sink)
        server.enqueue(asrOk("小朵小朵今天天气怎么样"))
        server.enqueue(loginOk())
        server.enqueue(threadOk())
        server.enqueue(http500("boom")) // SSE 请求 HTTP 500 → ChatResult.Error

        mgr.onUtterance(utterance())

        val ok = sink.terminalLatch.await(20, TimeUnit.SECONDS)
        assertTrue("error 应在 20s 内到达; events=${sink.events}", ok)
        assertEquals(
            listOf("user:今天天气怎么样", "error:AG-UI HTTP 500: boom"),
            sink.events.toList(),
        )
    }

    @Test
    fun asrFailureFiresNothing() {
        val sink = RecordingSink()
        val mgr = newManager(sink)
        server.enqueue(http500("asr down"))

        mgr.onUtterance(utterance())

        awaitStatus(mgr, "ASR 失败")
        assertEquals(emptyList<String>(), sink.events.toList())
    }

    @Test
    fun emptyAfterStripFiresNothing() {
        val sink = RecordingSink()
        val mgr = newManager(sink)
        // 剥前缀后为空（纯唤醒词）：2026-08-10 根因修复起回 IDLE 让 KWS 接管，
        // 不再 playAckThenListen 重播（防「ASR→strip空→重播」死循环），此路径无 status
        server.enqueue(asrOk("小朵小朵"))

        mgr.onUtterance(utterance())

        // onUtterance 已同步置 PROCESSING，回 IDLE 即管线跑完（删回 IDLE 分支 → 超时红）
        awaitState(mgr, VoiceStateRouter.State.IDLE)
        assertEquals(emptyList<String>(), sink.events.toList())
        assertEquals("不得送对话（仅 ASR 一次请求）", 1, server.requestCount)
    }
}
