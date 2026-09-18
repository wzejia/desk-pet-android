package com.xiaoduo.deskpet.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 * S40 唤醒应答期盲区修复单测：盲区缓冲 + 接缝水位 + 回灌。
 *
 * 背景（09-12 取证，125 唤醒序列）：①唤醒→「我在」播完→vad.start 构成 ~0.9s 采音盲区
 * （SPEAKING(wozai) 麦块只投 KWS、vad.start 清空无回填）——6 次 30s 超时+句头截断；
 * ②消费停顿后 ack 时代陈旧块突发直灌 VAD（Case B 数学不可能形态：533ms 窗口产出 1.2s
 * 音频的 utterance）。
 *
 * 修复锚：
 * - SPEAKING(wozai) 期块进盲区缓冲（S12 KWS 实时解码不变）；
 * - LISTENING 期捕获早于接缝水位（vad.start 瞬间）的块改道缓冲，不直灌 VAD；
 * - 接缝后首个块触发回灌（FIFO 时序完整），回灌经 NLMS 喂 VAD 不喂 kwsSink；
 * - 按住/多轮路径零挂账零行为变更；cancel 清缓冲清挂账。
 *
 * 变异锚（票据点名）：
 * - 删 KWS 分支缓冲 → 用例 1 红（buffer==0）；
 * - 删 VAD 分支 BUFFER 改道（陈块直灌 VAD）→ 用例 2 红（3 响块直灌立刻 utterance）；
 * - 删接缝后首块回灌触发 → 用例 2 红（pending 不消费、无 backfill 日志）；
 * - 回灌喂 kwsSink（双喂）→ 用例 2 红（kwsSink 计数多 3）；
 * - 删 cancel 清理 → 用例 5 红。
 */
class VoiceSessionManagerS40BlindWindowTest {

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

    /** latch 可控「我在」播放器（体例同 S33BlindSpotTest.FakeWakeAck）。 */
    private class FakeWakeAck : VoiceSessionManager.WakeAck {
        val playStarted = CountDownLatch(1)
        private val releasePlay = CountDownLatch(1)

        override fun playBlocking(isCancelled: () -> Boolean) {
            playStarted.countDown()
            releasePlay.await(10, TimeUnit.SECONDS)
            if (isCancelled()) throw java.io.IOException("stopped")
        }

        override fun stop() {}

        fun release() = releasePlay.countDown()
    }

    private fun newManager(
        logs: MutableList<String>,
        wakeAck: VoiceSessionManager.WakeAck? = null,
    ): VoiceSessionManager {
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
            aguiFactory = { error("pipeline LLM stage not used in S40 tests") },
            bubble = NoopBubble,
            scope = scope,
            logger = { msg -> logs += msg },
            wakeAck = wakeAck,
        )
    }

    private fun awaitQuiescence() {
        Thread.sleep(300)
    }

    private fun tone(levelOverEightK: Short): ByteArray {
        val pcm = ByteArray(VadDetector.BLOCK_BYTES)
        var i = 0
        while (i < pcm.size) {
            pcm[i] = (levelOverEightK.toInt() and 0xFF).toByte()
            pcm[i + 1] = (levelOverEightK.toInt() shr 8).toByte()
            i += 2
        }
        return pcm
    }

    private fun silence(): ByteArray = ByteArray(VadDetector.BLOCK_BYTES)

    private fun nowStamped(pcm: ByteArray) = StampedBlock(pcm, System.nanoTime())

    @Test
    fun speakingWozaiBlocksGoToBlindBuffer() {
        val logs = mutableListOf<String>()
        val ack = FakeWakeAck()
        val mgr = newManager(logs, ack)
        var kwsFed = 0
        mgr.kwsSink = { kwsFed++ }

        mgr.startListening(playWakeAck = true)
        assertTrue(ack.playStarted.await(2, TimeUnit.SECONDS))
        assertEquals(VoiceStateRouter.State.SPEAKING, mgr.state)

        mgr.routeBlockForTest(nowStamped(tone(4000)))
        mgr.routeBlockForTest(nowStamped(tone(4000)))

        assertEquals("SPEAKING(wozai) 期块进盲区缓冲（S12 KWS 实时解码不变）", 2, mgr.blindBufferSizeForTest)
        assertEquals("KWS 分支照旧实时喂（唤醒打断唤醒可达性不回退）", 2, kwsFed)

        ack.release()
        awaitQuiescence()
        assertEquals(VoiceStateRouter.State.LISTENING, mgr.state)
        assertTrue("wozai 播完挂账待回灌", mgr.pendingBlindBackfillForTest)

        // 接缝后首块：触发回灌（缓冲 2 响块 → aboveTicks=2）→ 活响块 aboveTicks=3 判起
        mgr.routeBlockForTest(nowStamped(tone(4000)))
        assertEquals("回灌后缓冲清空（恰好一次）", 0, mgr.blindBufferSizeForTest)
        assertFalse(mgr.pendingBlindBackfillForTest)
        assertTrue(logs.any { it.contains("S40 blind-window backfill: 2 blocks") })
    }

    @Test
    fun ackEraStaleBlocksBufferedNotFedToVadDirectly() {
        val logs = mutableListOf<String>()
        val ack = FakeWakeAck()
        val mgr = newManager(logs, ack)
        var kwsFed = 0
        mgr.kwsSink = { kwsFed++ }

        mgr.startListening(playWakeAck = true)
        assertTrue(ack.playStarted.await(2, TimeUnit.SECONDS))

        // 模拟消费停顿：3 个 ack 时代响块在接缝后才被处理（修复前直灌 VAD 立刻伪 utterance）
        val stale = StampedBlock(tone(4000), System.nanoTime() - 1_000_000_000L)
        mgr.routeBlockForTest(stale)
        mgr.routeBlockForTest(stale)
        mgr.routeBlockForTest(stale)
        ack.release()
        awaitQuiescence()
        assertEquals(VoiceStateRouter.State.LISTENING, mgr.state)

        // 回归咬合点：3 响块已入缓冲而非直灌 VAD —— 若直灌此处 state 已 PROCESSING
        assertEquals("陈块改道缓冲，不直灌 VAD", 3, mgr.blindBufferSizeForTest)
        assertEquals(VoiceStateRouter.State.LISTENING, mgr.state)

        // 接缝后首块触发回灌：3 响块判起 → 活静音块×SILENCE_END_TICKS 判停（S50 800ms）→ S51 续听窗挂起
        mgr.routeBlockForTest(nowStamped(silence()))
        repeat(VadDetector.SILENCE_END_TICKS - 1) { mgr.routeBlockForTest(nowStamped(silence())) }
        assertEquals("判停后进 S51 续听窗（状态仍 LISTENING 不即派发）", VoiceStateRouter.State.LISTENING, mgr.state)
        assertTrue(logs.any { it.contains("S40 blind-window backfill: 3 blocks") })
        assertTrue(logs.any { it.contains("S51 continuation hold") })
        // 续听窗耗尽（再 CONTINUATION_TICKS 静音块）→ 合并派发 → utterance 进管线
        repeat(VoiceSessionManager.CONTINUATION_TICKS) { mgr.routeBlockForTest(nowStamped(silence())) }
        assertEquals("续听窗耗尽派发 → utterance 进管线（盲区语音找回）", VoiceStateRouter.State.PROCESSING, mgr.state)
        // 回灌块不喂 kwsSink（KWS 已实时解过，双喂恐二次命中重唤醒环）；活块 S33 旁路照旧
        // （活静音块共 15：8 判停 + 7 续听窗）→ 3（ack 期）+ 15（活块旁路）= 18；
        // 若回灌双喂应为 21
        assertEquals(
            "kwsSink 计数 = ack 期 3 + 活块（判停 ${VadDetector.SILENCE_END_TICKS} + 续听窗 ${VoiceSessionManager.CONTINUATION_TICKS}）（回灌 3 块不双喂）",
            3 + VadDetector.SILENCE_END_TICKS + VoiceSessionManager.CONTINUATION_TICKS, kwsFed,
        )
    }

    @Test
    fun holdToTalkPathNeverSetsSeam() {
        val logs = mutableListOf<String>()
        val mgr = newManager(logs)

        mgr.startListening() // 按住语义：直进 LISTENING，无 wozai 无接缝
        assertEquals(VoiceStateRouter.State.LISTENING, mgr.state)

        mgr.routeBlockForTest(nowStamped(tone(4000)))
        assertEquals("按住路径零挂账", false, mgr.pendingBlindBackfillForTest)
        assertEquals("按住路径零缓冲", 0, mgr.blindBufferSizeForTest)
        assertTrue(logs.none { it.contains("S40 blind-window backfill") })
    }

    @Test
    fun cancelClearsBlindWindowAndFlag() {
        val logs = mutableListOf<String>()
        val ack = FakeWakeAck()
        val mgr = newManager(logs, ack)

        mgr.startListening(playWakeAck = true)
        assertTrue(ack.playStarted.await(2, TimeUnit.SECONDS))
        mgr.routeBlockForTest(nowStamped(tone(4000)))
        mgr.routeBlockForTest(nowStamped(tone(4000)))
        assertEquals(2, mgr.blindBufferSizeForTest)

        // 唤醒打断唤醒/用户打断：取消必须清缓冲清挂账（防旧轮音频回灌进新轮）
        mgr.cancelCurrentTurn()
        assertEquals(VoiceStateRouter.State.IDLE, mgr.state)
        assertEquals("cancel 清缓冲", 0, mgr.blindBufferSizeForTest)
        assertFalse(mgr.pendingBlindBackfillForTest)

        ack.release()
        awaitQuiescence()
        assertEquals("取消后 wozai 残跑不得迁 LISTENING", VoiceStateRouter.State.IDLE, mgr.state)
    }

    @Test
    fun degradedNoAckPathHasNoSeam() {
        val logs = mutableListOf<String>()
        val mgr = newManager(logs, wakeAck = null)

        // wozai 缺失降级：直接进聆听，无接缝无缓冲零行为变更
        mgr.startListening(playWakeAck = true)
        assertEquals(VoiceStateRouter.State.LISTENING, mgr.state)
        assertFalse(mgr.pendingBlindBackfillForTest)

        mgr.routeBlockForTest(nowStamped(tone(4000)))
        assertEquals(0, mgr.blindBufferSizeForTest)
        assertTrue(logs.none { it.contains("S40 blind-window backfill") })
    }
}
