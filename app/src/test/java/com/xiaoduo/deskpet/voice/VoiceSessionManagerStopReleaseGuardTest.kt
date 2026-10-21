package com.xiaoduo.deskpet.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 免按住模式 stopListening 吞语音守卫（病 B 判停竞速修复，2026-10-18）：
 * VAD 已判停、片段在 S51 hold 待派发（continuationParts 非空）时点「结束聆听」，
 * 必须照常派发（有货送货），不得走 `manual release, no utterance` 回 IDLE 吞掉。
 *
 * 体例照 S51ContinuationTest：合成 PCM 块（恒定值=精确 RMS）走 routeBlockForTest
 * 全真块路径；loud(1000)→0.125 ≥ 0.05 判起线；zero→0.0 < 0.03 判停线；块长 100ms；
 * 网络端点不可达（http://127.0.0.1:9），ASR 失败由管线侧自愈与本组断言无关。
 *
 * 变异锚 M2：删 stopListening 守卫段 → 用例 1 红（state 落 IDLE + 金线 log 出现 +
 * 无 dispatch）；对照组用例 2 两态下都必须绿（守卫不影响「纯聆听结束回 IDLE」）。
 * cancel 语义保护=S51ContinuationTest「cancel during hold drops pending fragments」
 * 零改动回归（scoped 命令同跑）。
 */
class VoiceSessionManagerStopReleaseGuardTest {

    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private object NoopBubble : VoiceSessionManager.BubbleSink {
        override fun show(text: String) {}
        override fun append(delta: String) {}
        override fun hide() {}
        override fun hideLater(delayMs: Long) {}
    }

    private fun newManager(logs: MutableList<String>): VoiceSessionManager {
        val asr = AsrClient(okhttp3.OkHttpClient(), "http://127.0.0.1:9/asr", logger = {})
        val tts = TtsClient(
            okhttp3.OkHttpClient(),
            streamUrl = "http://127.0.0.1:9/tts/stream",
            synthesizeUrl = "http://127.0.0.1:9/tts/synthesize",
            streamSupport = object : TtsClient.StreamSupport {
                override fun isUnsupported(): Boolean = false
                override fun markUnsupported() {}
            },
            logger = {},
        )
        return VoiceSessionManager(
            capture = AudioCapture(),
            asr = asr,
            tts = tts,
            aguiFactory = { error("pipeline LLM stage not used in stop-release guard tests") },
            bubble = NoopBubble,
            scope = scope,
            logger = { msg -> logs += msg },
        )
    }

    private fun loudBlock() = ByteArray(VadDetector.BLOCK_BYTES) { i ->
        if (i % 2 == 0) (1000 and 0xFF).toByte() else (1000 shr 8).toByte()
    }

    private fun silenceBlock() = ByteArray(VadDetector.BLOCK_BYTES)

    /** 喂一个完整片段：n 响块（判起+说话）+ 8 静音块（S50 判停）→ utterance 进 S51 hold。 */
    private fun feedFragment(mgr: VoiceSessionManager, loudCount: Int) {
        repeat(loudCount) { mgr.routeBlockForTest(loudBlock()) }
        repeat(VadDetector.SILENCE_END_TICKS) { mgr.routeBlockForTest(silenceBlock()) }
    }

    @Test
    fun `stop during continuation window dispatches instead of swallowing`() {
        // 病 B 金线复现（2026-10-17 板端 4 轮）：maxLevel 过线、VAD 判停进 hold，
        // 续听窗（CONTINUATION_TICKS=1=100ms）未耗尽时结束聆听——守卫前=manual release 吞掉，
        // 守卫后=dispatchContinuation 照常派发（有货送货）。
        val logs = mutableListOf<String>()
        val mgr = newManager(logs)
        mgr.startListening()
        assertEquals(VoiceStateRouter.State.LISTENING, mgr.state)

        feedFragment(mgr, 5) // 片段判停 → hold（ticks=1，窗未耗尽）
        assertTrue(logs.any { it.contains("S51 continuation hold: parts=1 samples=17600") })
        assertEquals("hold 窗内仍是 LISTENING", VoiceStateRouter.State.LISTENING, mgr.state)

        // 不等续听窗耗尽（不再喂块驱动 tickContinuation），直接结束聆听
        mgr.stopListening()

        assertEquals("已判定语音不吞：照常派发进管线", VoiceStateRouter.State.PROCESSING, mgr.state)
        assertTrue(logs.any { it.contains("S51 continuation dispatch: 17600 samples") })
        assertFalse(
            "金线禁现：正常说完的轮次不得落 manual release, no utterance",
            logs.any { it.contains("manual release, no utterance") },
        )
    }

    @Test
    fun `stop with plain silence listening still returns to IDLE`() {
        // 对照组：纯聆听未说话（无 utterance 无 hold）→ 结束聆听维持既有语义回 IDLE。
        val logs = mutableListOf<String>()
        val mgr = newManager(logs)
        mgr.startListening()
        repeat(3) { mgr.routeBlockForTest(silenceBlock()) } // 纯静音，未判起
        assertEquals(VoiceStateRouter.State.LISTENING, mgr.state)

        mgr.stopListening()

        assertEquals("未检出语音：既有回 IDLE 语义不变", VoiceStateRouter.State.IDLE, mgr.state)
        assertTrue(logs.any { it.contains("manual release, no utterance") })
        assertTrue(logs.none { it.contains("S51 continuation dispatch") })
    }
}
