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
    fun `window=1 dispatches even when hold-period blocks are loud (noise-immune)`() {
        // S55 板端实证回归锁（2026-09-21 18:12）：噪声电平 0.19~0.29 下冻结永不解除，
        // hold 无限累积 parts=8/66s 零派发。修复=窗=1 时冻结短路——hold 后的响块
        // （0.25 ≥ end 0.03，旧实现会冻结）必须照常倒数派发。
        val logs = mutableListOf<String>()
        val mgr = newManager(logs)
        mgr.startListening()

        feedFragment(mgr, 5) // 片段1 → hold（ticks=1）
        assertTrue(logs.any { it.contains("S51 continuation hold: parts=1 samples=8000") })

        mgr.routeBlockForTest(ampBlock(2000)) // 0.25 噪声级块：旧实现冻结（不派发）→ 现在必派发
        assertEquals("噪声块不阻塞派发（hold 死锁已除）", VoiceStateRouter.State.PROCESSING, mgr.state)
        assertTrue(logs.any { it.contains("S51 continuation dispatch: 8000 samples") })
    }

    @Test
    fun `window=1 dispatches on quiet continuation too (merge fully off)`() {
        // S55（2026-09-21）：窗=1 冻结短路后，轻声续说（0.045 ≥ end 0.03）同样不再冻结——
        // 首块即派发，合并彻底全关（含轻声场景）。
        // 历史锁（板端 17:00 实锤：轻声档案名整段丢弃 → 冻结线+降敏判起）在窗>1 恢复时
        // 还原——原版见 git 历史。
        val logs = mutableListOf<String>()
        val mgr = newManager(logs)
        mgr.startListening()
        feedFragment(mgr, 5) // 片段1 → hold（samples=8000）
        assertTrue(logs.any { it.contains("S51 continuation hold: parts=1 samples=8000") })

        // 轻声续说首块（0.045）：窗=1 冻结短路 → 照常倒数派发
        mgr.routeBlockForTest(ampBlock(360))
        assertEquals("轻声续说不再冻结（合并全关）", VoiceStateRouter.State.PROCESSING, mgr.state)
        assertTrue(logs.any { it.contains("S51 continuation dispatch: 8000 samples") })
        assertTrue(logs.none { it.contains("parts=2") })
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
