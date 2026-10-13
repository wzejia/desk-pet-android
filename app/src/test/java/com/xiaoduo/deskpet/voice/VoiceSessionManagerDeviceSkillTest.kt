package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.agui.AguiClient
import com.xiaoduo.deskpet.device.DeviceClient
import com.xiaoduo.deskpet.device.DeviceIntentMatcher
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
import java.util.concurrent.TimeUnit

/**
 * VSM 设备技能接线单测（S26-3）：MockWebServer 只供 ASR——设备轮**不得有任何 LLM 请求**；
 * 设备结果走同一分句/TTS 管线（静音下零 TTS 请求）；设备轮=工具轮（终态 IDLE 重唤醒）。
 */
class VoiceSessionManagerDeviceSkillTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope

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

    private class MemTokenCache : AguiClient.TokenCache {
        var token: String? = null
        override fun cached(): String? = token
        override fun saveFresh(token: String) { this.token = token }
        override fun clear() { token = null }
    }

    private class MemThreadStore : AguiClient.ThreadIdStore {
        val map = HashMap<String, String>()
        override fun load(key: String): String? = map[key]
        override fun save(key: String, threadId: String) { map[key] = threadId }
        override fun remove(key: String) { map.remove(key) }
    }

    private class RecSink : VoiceSessionManager.TranscriptSink {
        val events = CopyOnWriteArrayList<String>()
        override fun onUserUtterance(text: String) { events.add("user:$text") }
        override fun onAssistantDelta(delta: String) { events.add("delta:$delta") }
        override fun onAssistantDone() { events.add("done") }
        override fun onAssistantError(message: String) { events.add("error:$message") }
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

    private fun newManager(sink: RecSink): VoiceSessionManager {
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
            speakMcpResultProvider = { false }, // 静音：设备自带播报（工具轮待遇）
            rewakeAfterToolCallProvider = { true }, // S21：设备轮后回 IDLE 重唤醒
            deviceSkillMatcher = { DeviceIntentMatcher.match(it) },
            deviceSkillExecutor = { _ ->
                DeviceClient.SkillResult.Ok("整理台已打开。温度30度，湿度48%。")
            },
        ).also { it.transcriptSink = sink }
    }

    private fun asrOk(text: String) =
        MockResponse().setBody("""{"code":200,"msg":"ok","data":{"text":"$text"}}""")

    private fun utterance(mgr: VoiceSessionManager) {
        mgr.onUtterance(
            VadDetector.Event.Utterance(ShortArray(3 * VadDetector.BLOCK_SAMPLES) { 500 }, 0.3),
        )
    }

    private fun pumpTurn(sink: RecSink) {
        val deadline = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < deadline) {
            if (sink.events.any { it == "done" || it.startsWith("error:") }) return
            Thread.sleep(30)
        }
        error("turn did not complete: " + sink.events)
    }

    /** 设备轮：零 LLM 请求、文字上屏、静音零 TTS、终态 IDLE（工具轮重唤醒） */
    @Test
    fun `device skill turn skips llm speaks nothing and ends idle`() {
        val sink = RecSink()
        val mgr = newManager(sink)
        server.enqueue(asrOk("打开整理台"))
        // 故意不 enqueue login/stream/tts——任何 LLM 或 TTS 请求都会让 MockWebServer 阻塞暴露

        utterance(mgr)
        pumpTurn(sink)

        assertTrue(sink.events.contains("user:打开整理台"))
        assertTrue(sink.events.any { it.contains("整理台已打开") })
        assertTrue(sink.events.contains("done"))
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            if (mgr.stateFlow.value == VoiceStateRouter.State.IDLE) break
            Thread.sleep(25)
        }
        assertEquals(VoiceStateRouter.State.IDLE, mgr.stateFlow.value)
        // 唯一的 HTTP 请求是 ASR
        val paths = mutableListOf<String>()
        while (true) {
            val req = server.takeRequest(300, TimeUnit.MILLISECONDS) ?: break
            paths.add(req.path ?: "")
        }
        assertEquals("设备轮不得有任何 LLM/TTS 请求: $paths", listOf("/asr"), paths)
    }
}
