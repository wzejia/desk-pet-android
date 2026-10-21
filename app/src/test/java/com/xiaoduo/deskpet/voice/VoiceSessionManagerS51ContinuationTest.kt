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
 * S55 暂关语义（窗=1 即派发）已由 S69（2026-10-21）废止：板端实锤窗=1 下句中气口
 * >600ms 即切（ASR 单字「放」）——恢复窗=10 合并语义 + 有界总上限防 S55 死锁复发。
 *
 * 体例：合成 PCM 块（恒定值=精确 RMS）走 routeBlockForTest 全真块路径；
 * loud(1000)→0.125 ≥ 0.05 判起线；zero→0.0 < 0.03 判停线；块长 100ms。
 * 变异锚：删 holdForContinuation 截流 → 合并用例红（state 提前 PROCESSING）；
 * 删语音块冻结 → 合并用例红（第二片段中途被派发截断）；删有界上限 → 有界用例红
 * （冻结永不派发）；删 backToIdle 清理 → cancel 用例红；stopListening 改走续听 → 按住用例红。
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
        assertTrue(logs.any { it.contains("S51 continuation hold: parts=1 samples=17600") })

        repeat(VoiceSessionManager.CONTINUATION_TICKS) { mgr.routeBlockForTest(silenceBlock()) }
        assertEquals("续听窗耗尽 → 合并派发", VoiceStateRouter.State.PROCESSING, mgr.state)
        assertTrue(logs.any { it.contains("S51 continuation dispatch: 17600 samples") })
    }

    @Test
    fun `S69 resumed speech freezes window then merges second fragment`() {
        // S69（2026-10-21）：续听窗恢复 10 块（S55 暂关时的「响气口即派发」语义废止）。
        // 板端实锤：600ms 判停 + 窗=1 → 句中气口 >600ms 即切且无兜底（首轮 1.8s→
        // ASR 单字「放」，19:12:25 日志）。窗>1 恢复后响块续说冻结倒计时、片段合并。
        val logs = mutableListOf<String>()
        val mgr = newManager(logs)
        mgr.startListening()

        feedFragment(mgr, 5) // 片段1 → hold（ticks=10）
        assertTrue(logs.any { it.contains("S51 continuation hold: parts=1 samples=17600") })

        // 窗中途响块续说：2 静音（倒数 10→8）+ 5 响块（语音级冻结，第 3 响判起）
        repeat(2) { mgr.routeBlockForTest(silenceBlock()) }
        repeat(5) { mgr.routeBlockForTest(loudBlock()) }
        assertEquals("第二片段进行中不派发", VoiceStateRouter.State.LISTENING, mgr.state)
        assertTrue(logs.none { it.contains("S51 continuation dispatch") })

        // 第二片段判停 → hold 重置窗口并累积（parts=2）
        feedFragmentTail(mgr)
        assertTrue(
            "parts=2 未累积 logs=${logs.filter { it.contains("S51") }}",
            logs.any { it.contains("S51 continuation hold: parts=2 samples=38400") },
        )

        // 窗耗尽 → 两片段合并派发（17600+20800：片段2 含 pre-roll 回补的句前 2 静音块）
        repeat(VoiceSessionManager.CONTINUATION_TICKS) { mgr.routeBlockForTest(silenceBlock()) }
        assertEquals(VoiceStateRouter.State.PROCESSING, mgr.state)
        assertTrue(logs.any { it.contains("S51 continuation dispatch: 38400 samples") })
    }

    /** 判停尾垫：8 静音块（第 6 块判停产出片段，与 feedFragment 尾部同构）。 */
    private fun feedFragmentTail(mgr: VoiceSessionManager) {
        repeat(VadDetector.SILENCE_END_TICKS + 2) { mgr.routeBlockForTest(silenceBlock()) }
    }

    @Test
    fun `S69 bounded hold forces dispatch when freeze never lifts (noise-immune cap)`() {
        // S55 病根防线：噪声级块（0.25 ≥ end 0.03 且判起 → isSpeaking）令冻结永不解除
        // → 旧实现 hold 无限累积（板端实测 parts=8/66s 零派发）。S69 有界总上限
        // [CONTINUATION_MAX_TICKS]：无论冻结与否，hold 累计到顶强制派发。
        val logs = mutableListOf<String>()
        val mgr = newManager(logs)
        mgr.startListening()

        feedFragment(mgr, 5) // 片段1 → hold
        assertTrue(logs.any { it.contains("S51 continuation hold: parts=1") })

        // 纯冻结构造（0.45/0.9 交替）：块恒 ≥ end 线（tick 冻结）；近峰值波动令
        // 上攻双判据皆破（0.45 恰不满足半峰 0.45<0.45、plateau 比值 0.67>0.25）、
        // 熔断破缺（0.9 ≥ peak×0.6=0.54）→ VAD 无自愈出口，唯有界上限可派发。
        // 旧实现（无有界）：冻结永不倒数 → 永不派发（S55 死锁同型）。
        repeat(VoiceSessionManager.CONTINUATION_MAX_TICKS) {
            mgr.routeBlockForTest(if (it % 2 == 0) ampBlock(3600) else ampBlock(7200))
        }
        assertEquals("冻结有顶：有界到顶必派发", VoiceStateRouter.State.PROCESSING, mgr.state)
        assertTrue(logs.any { it.contains("S51 continuation dispatch") })
    }

    @Test
    fun `S69 quiet continuation freezes window and merges (soft archive names)`() {
        // S51 历史锁还原（板端 17:00 实锤）：轻声续说（0.045 ∈ end 线之上、判起校准线
        // 之下）必须冻结窗口待片段成型——若被当静音倒数，用户开口说档案名时照常派发
        // 停 VAD，整段丢弃。startLevelOverride 降敏 + 冻结线同域 end 线，两机制配套。
        val logs = mutableListOf<String>()
        val mgr = newManager(logs)
        mgr.startListening()

        feedFragment(mgr, 5) // 片段1 → hold
        assertTrue(logs.any { it.contains("S51 continuation hold: parts=1") })

        // 2 静音（倒数）+ 5 轻声块（0.045 ≥ end 0.03 → 冻结；第 3 块降敏判起）
        repeat(2) { mgr.routeBlockForTest(silenceBlock()) }
        repeat(5) { mgr.routeBlockForTest(ampBlock(360)) }
        assertEquals("轻声续说中不派发（冻结生效）", VoiceStateRouter.State.LISTENING, mgr.state)
        assertTrue(logs.none { it.contains("S51 continuation dispatch") })

        // 轻声片段判停 → 合并派发
        feedFragmentTail(mgr)
        repeat(VoiceSessionManager.CONTINUATION_TICKS) { mgr.routeBlockForTest(silenceBlock()) }
        assertEquals(VoiceStateRouter.State.PROCESSING, mgr.state)
        assertTrue(logs.any { it.contains("S51 continuation hold: parts=2") })
        assertTrue(logs.any { it.contains("S51 continuation dispatch: 38400 samples") })
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
