package com.xiaoduo.deskpet.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S51 断句续听单测：判停片段不即派发——续听窗内续说冻结倒计时、片段合并派发；
 * cancel 清挂起；按住松开（stopListening）保持即时派发直通。
 *
 * 病灶（09-18 板端实锤）：固定静音窗判停切长句字组气口（「盘点当前有哪…些档案」），
 * 且切尾改变 LLM 工具路由（盘点被当查询）。机制见 VoiceSessionManager S51 注释块。
 *
 * 体例：合成 PCM 块（恒定值=精确 RMS）走 routeBlockForTest 全真块路径；
 * loud(1000)→0.125 ≥ 0.05 判起线；zero→0.0 < 0.03 判停线；块长 100ms。
 * 变异锚：删 holdForContinuation 截流 → 用例 1/2 红（state 提前 PROCESSING）；
 * 删语音块冻结 → 用例 2 红（第二片段中途被派发截断）；删 backToIdle 清理 → 用例 3 红；
 * stopListening 改走续听 → 用例 4 红（不即派发）。
 */
class VoiceSessionManagerS51ContinuationTest {

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
            aguiFactory = { error("pipeline LLM stage not used in S51 tests") },
            bubble = NoopBubble,
            scope = scope,
            logger = { msg -> logs += msg },
        )
    }

    private fun loudBlock() = ByteArray(VadDetector.BLOCK_BYTES) { i ->
        if (i % 2 == 0) (1000 and 0xFF).toByte() else (1000 shr 8).toByte()
    }

    private fun silenceBlock() = ByteArray(VadDetector.BLOCK_BYTES)

    /** 喂一个完整片段：n 响块（判起+说话）+ 8 静音块（S50 判停），返回后必产出片段。 */
    private fun feedFragment(mgr: VoiceSessionManager, loudCount: Int) {
        repeat(loudCount) { mgr.routeBlockForTest(loudBlock()) }
        repeat(VadDetector.SILENCE_END_TICKS) { mgr.routeBlockForTest(silenceBlock()) }
    }

    @Test
    fun `fragment held then dispatched after quiet window`() {
        val logs = mutableListOf<String>()
        val mgr = newManager(logs)
        mgr.startListening()
        assertEquals(VoiceStateRouter.State.LISTENING, mgr.state)

        feedFragment(mgr, 5) // 片段1：5 响 + 8 静音 → utterance → hold
        assertEquals("判停后保持 LISTENING（续听窗内不派发）", VoiceStateRouter.State.LISTENING, mgr.state)
        assertTrue(logs.any { it.contains("S51 continuation hold: parts=1 samples=8000") })

        repeat(VoiceSessionManager.CONTINUATION_TICKS) { mgr.routeBlockForTest(silenceBlock()) }
        assertEquals("续听窗耗尽 → 合并派发", VoiceStateRouter.State.PROCESSING, mgr.state)
        assertTrue(logs.any { it.contains("S51 continuation dispatch: 8000 samples") })
    }

    @Test
    fun `resumed speech freezes window then merges second fragment`() {
        val logs = mutableListOf<String>()
        val mgr = newManager(logs)
        mgr.startListening()

        feedFragment(mgr, 5) // 片段1 → hold（samples=8000）
        assertTrue(logs.any { it.contains("S51 continuation hold: parts=1 samples=8000") })

        // 窗中途续说：3 静音（倒计时 7→4）+ 5 响块（语音级冻结倒计时，第 3 响判起）
        repeat(3) { mgr.routeBlockForTest(silenceBlock()) }
        repeat(5) { mgr.routeBlockForTest(loudBlock()) }
        assertEquals("第二片段进行中不派发", VoiceStateRouter.State.LISTENING, mgr.state)
        assertTrue(logs.none { it.contains("S51 continuation dispatch") })

        // 第二片段判停 → hold 重置窗口并累积（parts=2）
        repeat(VadDetector.SILENCE_END_TICKS) { mgr.routeBlockForTest(silenceBlock()) }
        assertEquals("片段2 判停后仍 LISTENING", VoiceStateRouter.State.LISTENING, mgr.state)
        assertTrue(logs.any { it.contains("S51 continuation hold: parts=2 samples=20800") })

        // 窗耗尽 → 两片段合并派发（8000 + 12800 = 20800）
        repeat(VoiceSessionManager.CONTINUATION_TICKS) { mgr.routeBlockForTest(silenceBlock()) }
        assertEquals(VoiceStateRouter.State.PROCESSING, mgr.state)
        assertTrue(logs.any { it.contains("S51 continuation dispatch: 20800 samples") })
    }

    @Test
    fun `cancel during hold drops pending fragments`() {
        val logs = mutableListOf<String>()
        val mgr = newManager(logs)
        mgr.startListening()
        feedFragment(mgr, 5)
        assertTrue(logs.any { it.contains("S51 continuation hold") })

        mgr.cancelCurrentTurn()
        assertEquals(VoiceStateRouter.State.IDLE, mgr.state)

        // 挂起已清：再喂整个续听窗也不派发
        repeat(VoiceSessionManager.CONTINUATION_TICKS + 3) { mgr.routeBlockForTest(silenceBlock()) }
        assertEquals("cancel 清挂起后不幽灵派发", VoiceStateRouter.State.IDLE, mgr.state)
        assertTrue(logs.none { it.contains("S51 continuation dispatch") })
    }

    @Test
    fun `hold to talk release dispatches immediately without window`() {
        val logs = mutableListOf<String>()
        val mgr = newManager(logs)
        mgr.startListening()
        repeat(5) { mgr.routeBlockForTest(loudBlock()) } // 说话中
        assertEquals(VoiceStateRouter.State.LISTENING, mgr.state)

        mgr.stopListening() // 松开：直通 onUtterance，不走续听窗
        assertEquals("按住松开即派发（明确说完语义）", VoiceStateRouter.State.PROCESSING, mgr.state)
        assertTrue(logs.none { it.contains("S51 continuation hold") })
    }
}
