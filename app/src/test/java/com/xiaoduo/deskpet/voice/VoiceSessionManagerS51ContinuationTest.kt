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

    /** 振幅自定块（轻声续说用：360→0.045 / 560→0.07，落在旧冻结线 0.05 之下、end 线 0.03 之上） */
    private fun ampBlock(amp: Int) = ByteArray(VadDetector.BLOCK_BYTES) { i ->
        if (i % 2 == 0) (amp and 0xFF).toByte() else (amp shr 8).toByte()
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
    fun `window reduced to 1 - loud pause dispatches immediately instead of merging`() {
        // S55（2026-09-21 用户拍板）：续听窗 10→1 暂关合并语义（排查「说完不回答、持续聆听」
        // 无限挂起）。窗=1 时判停后首个静音块即耗尽派发，响块续说=新轮不合并。
        // 注意：轻声续说（0.03~0.05 频段）仍冻结窗口可合并——冻结线语义见下一用例（仍绿）。
        // 恢复窗口（CONTINUATION_TICKS 拨回 10）时本用例还原为合并语义锁（原版见 git 历史）。
        val logs = mutableListOf<String>()
        val mgr = newManager(logs)
        mgr.startListening()

        feedFragment(mgr, 5) // 片段1 → hold（samples=8000）
        assertTrue(logs.any { it.contains("S51 continuation hold: parts=1 samples=8000") })

        repeat(VoiceSessionManager.CONTINUATION_TICKS) { mgr.routeBlockForTest(silenceBlock()) }
        assertEquals("窗=1：判停后 1 静音块即派发（不等合并）", VoiceStateRouter.State.PROCESSING, mgr.state)
        assertTrue(logs.any { it.contains("S51 continuation dispatch: 8000 samples") })
        assertTrue("合并语义已关：不再出现 parts=2 累积", logs.none { it.contains("parts=2") })
    }

    @Test
    fun `quiet resumed speech freezes window and merges via relaxed start`() {
        // 板端 17:00 实锤回归锁：轻声续说（0.03~0.05 频段，旧实现被当静音倒数 →
        // hold+1.0s 整提前派发+停 VAD → 档案名整段丢弃）修复后应冻结窗口并降敏判起成片段
        val logs = mutableListOf<String>()
        val mgr = newManager(logs)
        mgr.startListening()
        feedFragment(mgr, 5) // 片段1 → hold（samples=8000）
        assertTrue(logs.any { it.contains("S51 continuation hold: parts=1 samples=8000") })

        // 轻声续说 12 块（0.045/0.07 交替，波动比 0.43>0.25 免触发平台通道）：
        // 冻结线应认它是语音（≥end 0.03）不倒数；降敏判起（0.03）后成型片段2
        repeat(12) { mgr.routeBlockForTest(ampBlock(if (it % 2 == 0) 360 else 560)) }
        assertEquals("轻声续说中不派发（旧实现在第 10 块静音化派发）", VoiceStateRouter.State.LISTENING, mgr.state)
        assertTrue(logs.none { it.contains("S51 continuation dispatch") })

        // 轻声片段判停 → hold 累积（片段2 = 判起时 preRoll 3 + 后续轻声 9 = 12 块 = 19200）
        repeat(VadDetector.SILENCE_END_TICKS) { mgr.routeBlockForTest(silenceBlock()) }
        assertTrue(logs.any { it.contains("S51 continuation hold: parts=2 samples=27200") })

        // 窗耗尽 → 两片段合并派发（8000 + 19200 = 27200）
        repeat(VoiceSessionManager.CONTINUATION_TICKS) { mgr.routeBlockForTest(silenceBlock()) }
        assertEquals(VoiceStateRouter.State.PROCESSING, mgr.state)
        assertTrue(logs.any { it.contains("S51 continuation dispatch: 27200 samples") })
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
