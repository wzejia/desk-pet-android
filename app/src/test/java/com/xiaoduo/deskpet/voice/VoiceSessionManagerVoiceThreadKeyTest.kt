package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.agui.AguiClient
import com.xiaoduo.deskpet.voice.audio.AudioCapture
import com.xiaoduo.deskpet.voice.audio.VadDetector
import com.xiaoduo.deskpet.voice.client.AsrClient
import com.xiaoduo.deskpet.voice.client.TtsClient
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * S37 语音轮平台 thread 跟随当前会话（用户拍板 2026-09-11）：语音轮与文字轮是同一会话的
 * 不同输入方式（attachVoiceTranscript 本地统一视图已实现），平台侧此前语音恒走 default
 * thread、文字走 session.id 各自 thread → 平台分成两个会话、跨通道上下文互不可见。
 * 修复：voiceChat 的 threadKey 由 [VoiceSessionManager.threadKeyProvider] 提供
 * （ChatViewModel 挂当前 session.id），null/空白回退 default（纯悬浮窗无会话兜底）。
 *
 * 断言方式：MockWebServer + 真 AguiClient，store 预存映射（命中即不 createThread），
 * 从 stream 请求 body 的 threadId 字段判定实际使用的平台 thread。
 */
class VoiceSessionManagerVoiceThreadKeyTest {

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

    /** 终态闩 sink：只关心管线跑完（threadKey 断言读服务端请求，不读上屏）。 */
    private inner class LatchSink : VoiceSessionManager.TranscriptSink {
        val terminalLatch = CountDownLatch(1)
        override fun onUserUtterance(text: String) {}
        override fun onAssistantDelta(delta: String) {}
        override fun onAssistantDone() {
            terminalLatch.countDown()
        }
        override fun onAssistantError(message: String) {
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

    private fun newManager(store: MemThreadStore, sink: LatchSink): VoiceSessionManager {
        val agui = AguiClient(
            http = OkHttpClient(),
            loginUrl = server.url("/login").toString(),
            streamUrl = server.url("/ai-server/agent/stream").toString(),
            credentials = { "admin" to "test-password" },
            tokenCache = MemTokenCache(),
            threadStore = store,
            logger = {},
        )
        return VoiceSessionManager(
            capture = AudioCapture(),
            asr = AsrClient(OkHttpClient(), server.url("/asr").toString(), logger = {}),
            tts = TtsClient(
                OkHttpClient(),
                streamUrl = server.url("/tts/stream").toString(),
                synthesizeUrl = server.url("/tts/synthesize").toString(),
                streamSupport = MemStreamSupport(),
                logger = {},
            ),
            aguiFactory = { agui },
            bubble = NoopBubble,
            scope = scope,
            logger = {},
        ).also { it.transcriptSink = sink }
    }

    private fun utterance() = VadDetector.Event.Utterance(ShortArray(1600) { 100 }, 0.5)

    private fun asrOk(text: String) =
        MockResponse().setBody("""{"code":200,"msg":"ok","data":{"text":"$text"}}""")

    private fun loginOk() =
        MockResponse().setBody("""{"code":200,"msg":"ok","token":"00000000-0000-0000-0000-000000000001"}""")

    private fun streamingSse() = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            """
            data: {"type":"RUN_STARTED","runId":"r"}

            data: {"type":"TEXT_MESSAGE_START","messageId":"m1","role":"assistant"}

            data: {"type":"TEXT_MESSAGE_CONTENT","messageId":"m1","delta":"ok"}

            data: {"type":"TEXT_MESSAGE_END","messageId":"m1"}

            data: {"type":"RUN_FINISHED","runId":"r"}

            """.trimIndent(),
        )

    private fun http500() =
        MockResponse().setResponseCode(500).setBody("""{"code":500,"msg":"down"}""")

    /** 取 stream 请求 body（请求序 = ASR → login → stream；store 命中不 createThread）。 */
    private fun streamBodyAwait(): String {
        assertTrue(server.requestCount >= 3)
        val asr = server.takeRequest(5, TimeUnit.SECONDS)!!
        val login = server.takeRequest(5, TimeUnit.SECONDS)!!
        val stream = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue("首请求应为 ASR，实际 path=${asr.path}", asr.path!!.startsWith("/asr"))
        assertTrue("次请求应为 login，实际 path=${login.path}", login.path!!.startsWith("/login"))
        assertTrue(
            "第三请求应为 stream，实际 path=${stream.path}",
            stream.path!!.startsWith("/ai-server/agent/stream"),
        )
        return stream.body.readUtf8()
    }

    private fun runVoiceTurnAndAwait(mgr: VoiceSessionManager, sink: LatchSink) {
        mgr.onUtterance(utterance())
        assertTrue(
            "terminal should arrive within 20s",
            sink.terminalLatch.await(20, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `voice turn routes to provider-provided session thread`() {
        val store = MemThreadStore().apply {
            map["default"] = "thread-default"
            map["sess-1"] = "thread-sess1"
        }
        val sink = LatchSink()
        val mgr = newManager(store, sink)
        mgr.threadKeyProvider = { "sess-1" }
        server.enqueue(asrOk("小朵小朵今天天气怎么样"))
        server.enqueue(loginOk())
        server.enqueue(streamingSse())
        server.enqueue(http500()) // TTS stream 降级
        server.enqueue(http500())

        runVoiceTurnAndAwait(mgr, sink)

        val body = streamBodyAwait()
        assertTrue(
            "stream 请求应使用会话 thread（thread-sess1），实际 body=$body",
            body.contains("\"threadId\":\"thread-sess1\""),
        )
    }

    @Test
    fun `voice turn falls back to default thread when provider absent`() {
        val store = MemThreadStore().apply { map["default"] = "thread-default" }
        val sink = LatchSink()
        val mgr = newManager(store, sink)
        server.enqueue(asrOk("小朵小朵今天天气怎么样"))
        server.enqueue(loginOk())
        server.enqueue(streamingSse())
        server.enqueue(http500())
        server.enqueue(http500())

        runVoiceTurnAndAwait(mgr, sink)

        val body = streamBodyAwait()
        assertTrue(
            "未挂 provider 应回退 default thread，实际 body=$body",
            body.contains("\"threadId\":\"thread-default\""),
        )
    }

    @Test
    fun `voice turn falls back to default thread when provider returns blank`() {
        val store = MemThreadStore().apply { map["default"] = "thread-default" }
        val sink = LatchSink()
        val mgr = newManager(store, sink)
        mgr.threadKeyProvider = { "" }
        server.enqueue(asrOk("小朵小朵今天天气怎么样"))
        server.enqueue(loginOk())
        server.enqueue(streamingSse())
        server.enqueue(http500())
        server.enqueue(http500())

        runVoiceTurnAndAwait(mgr, sink)

        val body = streamBodyAwait()
        assertTrue(
            "provider 返回空白应回退 default thread，实际 body=$body",
            body.contains("\"threadId\":\"thread-default\""),
        )
    }
}
