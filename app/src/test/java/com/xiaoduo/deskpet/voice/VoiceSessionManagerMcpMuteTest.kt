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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * S20 MCP 调用结果静音开关单测（MockWebServer 全桩 + internal onUtterance 驱动）。
 * 用户拍板：密集架自带语音播报，含工具调用轮手机跳过 TTS（文字照上屏）。
 * 事件序（探针实测）：TOOL_CALL_START 先于最终文本 -> 分句时标记已知。
 */
class VoiceSessionManagerMcpMuteTest {

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

    private fun newManager(
        sink: RecSink,
        speakMcp: Boolean,
        rewakeAfterTool: Boolean = true,
        shelfSync: MutableList<String>? = null,
    ): VoiceSessionManager {
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
            speakMcpResultProvider = { speakMcp },
            rewakeAfterToolCallProvider = { rewakeAfterTool },
            // S41：密集架联动桥挂点（测试用记录器替代真实 HTTP 桥）
            shelfMcpSync = { tool, _ -> shelfSync?.add(tool) },
        ).also { it.transcriptSink = sink }
    }

    private fun asrOk(text: String) =
        MockResponse().setBody("""{"code":200,"msg":"ok","data":{"text":"$text"}}""")

    private fun loginOk() =
        MockResponse().setBody("""{"code":200,"msg":"ok","token":"00000000-0000-0000-0000-000000000001"}""")

    private fun threadOk() = MockResponse().setBody("""{"code":200,"msg":"ok","data":"thread-test"}""")

    /** 含工具调用的 SSE（探针实测事件序：TOOL_CALL_* 先于文本） */
    private fun toolCallSse(text: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            "data: {\"type\":\"RUN_STARTED\",\"runId\":\"r\"}\n\n" +
                "data: {\"type\":\"TOOL_CALL_START\",\"toolCallId\":\"t1\",\"toolCallName\":\"mjj_move\"}\n\n" +
                "data: {\"type\":\"TOOL_CALL_END\",\"toolCallId\":\"t1\"}\n\n" +
                "data: {\"type\":\"TOOL_CALL_RESULT\",\"toolCallId\":\"t1\",\"content\":\"ok\"}\n\n" +
                "data: {\"type\":\"TEXT_MESSAGE_START\",\"messageId\":\"m1\",\"role\":\"assistant\"}\n\n" +
                "data: {\"type\":\"TEXT_MESSAGE_CONTENT\",\"messageId\":\"m1\",\"delta\":\"$text\"}\n\n" +
                "data: {\"type\":\"TEXT_MESSAGE_END\",\"messageId\":\"m1\"}\n\n" +
                "data: {\"type\":\"RUN_FINISHED\",\"runId\":\"r\"}\n\n",
        )

    /** TTS 流式响应：NDJSON 一帧 audioData（base64 WAV 剥头）+ done 帧（TtsClient 协议） */
    /** S21：独立开关（默认开）——含工具调用轮结束后回 IDLE（KWS 守门需重新唤醒），不走多轮续听 */
    @Test
    fun `rewake on sends tool call turn to idle instead of multi-turn listen`() {
        val sink = RecSink()
        val mgr = newManager(sink, speakMcp = false, rewakeAfterTool = true)
        server.enqueue(asrOk("打开1号密集架"))
        server.enqueue(loginOk())
        server.enqueue(threadOk())
        server.enqueue(toolCallSse("已为您打开。"))

        utterance(mgr)
        pumpTurn(sink)

        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline) {
            if (mgr.stateFlow.value == VoiceStateRouter.State.IDLE) break
            Thread.sleep(25)
        }
        assertEquals(VoiceStateRouter.State.IDLE, mgr.stateFlow.value)
    }

    /** S21：开关关——工具轮后保持现状（多轮续听重启 LISTENING） */
    @Test
    fun `rewake off keeps multi-turn listening after tool call turn`() {
        val sink = RecSink()
        val mgr = newManager(sink, speakMcp = false, rewakeAfterTool = false)
        server.enqueue(asrOk("打开1号密集架"))
        server.enqueue(loginOk())
        server.enqueue(threadOk())
        server.enqueue(toolCallSse("已为您打开。"))

        utterance(mgr)
        pumpTurn(sink)

        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline) {
            if (mgr.stateFlow.value == VoiceStateRouter.State.LISTENING) break
            Thread.sleep(25)
        }
        assertEquals(VoiceStateRouter.State.LISTENING, mgr.stateFlow.value)
    }

    private fun ttsOk() = MockResponse().setBody(
        """{"audioData":"UklGRuwAAABXQVZFZm10IBAAAAABAAEAgD4AAAB9AAACABAAZGF0YcgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="}""" + "\n" +
        """{"type":"done"}""" + "\n",
    )

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

    private fun drainRequestPaths(): List<String> {
        val paths = mutableListOf<String>()
        while (true) {
            val req = server.takeRequest(300, TimeUnit.MILLISECONDS) ?: break
            paths.add(req.path ?: "")
        }
        return paths
    }

    /** 1. 开关关：含工具调用轮 -> 零 TTS 请求，文字照常上屏，轮正常收尾 */
    @Test
    fun `mute on skips tts for tool call turn but keeps transcript`() {
        val sink = RecSink()
        val mgr = newManager(sink, speakMcp = false, rewakeAfterTool = false)
        server.enqueue(asrOk("打开1号密集架"))
        server.enqueue(loginOk())
        server.enqueue(threadOk())
        server.enqueue(toolCallSse("已为您打开。"))
        // 故意不 enqueue TTS 响应--若发起 TTS 请求将超时暴露

        utterance(mgr)
        pumpTurn(sink)

        assertTrue(sink.events.any { it.startsWith("user:打开1号密集架") })
        assertTrue(sink.events.any { it.contains("已为您打开") })
        assertTrue(sink.events.contains("done"))
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            if (mgr.stateFlow.value == VoiceStateRouter.State.LISTENING) break
            Thread.sleep(25)
        }
        assertEquals(VoiceStateRouter.State.LISTENING, mgr.stateFlow.value)
        val paths = drainRequestPaths()
        assertTrue("静音开关下不得有 TTS 请求: $paths", paths.none { it.startsWith("/tts") })
    }

    /** S41：语音轮 TOOL_CALL_START 的工具名经 shelfMcpSync 转发（密集架动画联动挂点） */
    @Test
    fun `tool call turn forwards tool name to shelf mcp sync`() {
        val sink = RecSink()
        val synced = CopyOnWriteArrayList<String>()
        val mgr = newManager(sink, speakMcp = true, rewakeAfterTool = false, shelfSync = synced)
        server.enqueue(asrOk("打开1号密集架"))
        server.enqueue(loginOk())
        server.enqueue(threadOk())
        server.enqueue(toolCallSse("已为您打开。"))
        server.enqueue(ttsOk())

        utterance(mgr)
        pumpTurn(sink)

        assertEquals(listOf("mjj_move"), synced)
    }

    /** 2. 开关开（默认）：含工具调用轮 -> TTS 正常合成播放（回归） */
    @Test
    fun `mute off keeps tts for tool call turn`() {
        val sink = RecSink()
        val mgr = newManager(sink, speakMcp = true, rewakeAfterTool = false)
        server.enqueue(asrOk("打开1号密集架"))
        server.enqueue(loginOk())
        server.enqueue(threadOk())
        server.enqueue(toolCallSse("已为您打开。"))
        server.enqueue(ttsOk())

        utterance(mgr)
        pumpTurn(sink)

        val paths = drainRequestPaths()
        assertTrue("开关开应有 TTS 请求: $paths", paths.any { it.startsWith("/tts") })
    }
}
