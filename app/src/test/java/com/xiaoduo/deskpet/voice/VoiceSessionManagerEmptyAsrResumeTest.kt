package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.agui.AguiClient
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

/**
 * S19-2 空 ASR 续听单测（MockWebServer 全桩 + internal onUtterance 驱动管线）。
 * 场景（rk3568 实测 20:57 轮）：纯噪声误触发 utterance -> ASR 空文本 ->
 * 旧行为落 IDLE（对话链断裂）；新行为重进 LISTENING（上限 2 次，超限落 IDLE）。
 */
class VoiceSessionManagerEmptyAsrResumeTest {

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

    private fun newManager(): VoiceSessionManager {
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
        )
    }

    /** 平台 ASR 空文本 -> AsrClient 报「ASR 返回空文本（无语音或全噪声）」（#20 语义） */
    private fun asrEmpty() =
        MockResponse().setBody("""{"code":200,"msg":"ok","data":{"text":""}}""")

    private fun utterance(mgr: VoiceSessionManager) {
        mgr.onUtterance(
            VadDetector.Event.Utterance(ShortArray(3 * VadDetector.BLOCK_SAMPLES) { 500 }, 0.3),
        )
    }

    private fun awaitState(mgr: VoiceSessionManager, target: VoiceStateRouter.State, timeoutMs: Long = 3000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (mgr.stateFlow.value == target) return
            Thread.sleep(25)
        }
        error("awaitState $target timeout, current=${mgr.stateFlow.value}")
    }

    /** 1. 单次空 ASR -> 重进 LISTENING（不曝错误泡、不落 IDLE） */
    @Test
    fun `empty asr resumes listening instead of idle`() {
        val mgr = newManager()
        server.enqueue(asrEmpty())

        utterance(mgr)
        awaitState(mgr, VoiceStateRouter.State.LISTENING)

        assertEquals(1, mgr.emptyAsrResumesForTest)
    }

    /** 2. 连续空 ASR 第 3 次 -> 落 IDLE（上限 2 次防死循环） */
    @Test
    fun `third empty asr falls back to idle`() {
        val mgr = newManager()
        server.enqueue(asrEmpty())
        utterance(mgr)
        awaitState(mgr, VoiceStateRouter.State.LISTENING)

        server.enqueue(asrEmpty())
        utterance(mgr)
        awaitState(mgr, VoiceStateRouter.State.LISTENING)
        assertEquals(0, mgr.emptyAsrResumesForTest)

        server.enqueue(asrEmpty())
        utterance(mgr)
        awaitState(mgr, VoiceStateRouter.State.IDLE)
    }

    /** 3. 续听后真文本正常走管线（计数复位，LLM/TTS 链路不受影响） */
    @Test
    fun `real text after resume resets counter and proceeds`() {
        val mgr = newManager()
        server.enqueue(asrEmpty())
        utterance(mgr)
        awaitState(mgr, VoiceStateRouter.State.LISTENING)

        // 真文本：ASR ok -> LLM SSE（单 delta 无标点 + RUN_FINISHED）-> TTS
        server.enqueue(
            MockResponse().setBody("""{"code":200,"msg":"ok","data":{"text":"今天天气怎么样"}}"""),
        )
        server.enqueue(
            MockResponse().setBody("""{"code":200,"msg":"ok","token":"00000000-0000-0000-0000-000000000001"}"""),
        )
        server.enqueue(
            MockResponse().setBody("""{"code":200,"msg":"ok","data":"thread-test"}"""),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"type\":\"RUN_STARTED\",\"runId\":\"r\"}\n\n" +
                        "data: {\"type\":\"TEXT_MESSAGE_START\",\"messageId\":\"m1\",\"role\":\"assistant\"}\n\n" +
                        "data: {\"type\":\"TEXT_MESSAGE_CONTENT\",\"messageId\":\"m1\",\"delta\":\"晴\"}\n\n" +
                        "data: {\"type\":\"TEXT_MESSAGE_END\",\"messageId\":\"m1\"}\n\n" +
                        "data: {\"type\":\"RUN_FINISHED\",\"runId\":\"r\"}\n\n",
                ),
        )

        utterance(mgr)
        // 真文本到达 LLM 阶段 -> 计数复位 2（state 会推进到 SPEAKING 或回 IDLE，取决于 TTS 桩）
        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline) {
            if (mgr.emptyAsrResumesForTest == 2) break
            Thread.sleep(25)
        }
        assertEquals(2, mgr.emptyAsrResumesForTest)
    }
}
