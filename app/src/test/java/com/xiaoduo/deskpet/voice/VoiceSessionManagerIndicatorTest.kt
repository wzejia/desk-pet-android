package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.agui.AguiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * S49 语音指示层状态机单测：聆听=声纹泡 / 思考=三点泡 / 离态收 / 多轮抑制 / wozai
 * 驻留顶替 / RMS 直喂。指示层走独立 [VoiceSessionManager.IndicatorSink]（与文字泡
 * 并行的记录桩），null 指示层回退文字泡的旧行为由既有 WakeAck 组用例钉死。
 *
 * 管线不真跑（ASR 不可达端点快速失败）：onUtterance 的 PROCESSING 迁移同步可达，
 * 异步残跑只产生 IDLE 回落与 hide 事件，不影响前两个同步事件断言。
 */
class VoiceSessionManagerIndicatorTest {

    private lateinit var scope: CoroutineScope

    private object NoopBubble : VoiceSessionManager.BubbleSink {
        override fun show(text: String) {}
        override fun append(delta: String) {}
        override fun hide() {}
        override fun hideLater(delayMs: Long) {}
    }

    /** 记录型指示层：show/hide 时序断言用（事件串行追加，CopyOnWrite 防跨线程读）。 */
    private class RecordingIndicator : VoiceSessionManager.IndicatorSink {
        val events = CopyOnWriteArrayList<String>()
        val levels = CopyOnWriteArrayList<Float>()
        override fun show(mode: VoiceSessionManager.IndicatorMode) {
            events += "show:$mode"
        }
        override fun onLevel(rms: Float) {
            levels += rms
        }
        override fun hide() {
            events += "hide"
        }
    }

    private class MemStreamSupport : TtsClient.StreamSupport {
        override fun isUnsupported(): Boolean = false
        override fun markUnsupported() {}
    }

    private class FakeWakeAck : VoiceSessionManager.WakeAck {
        val playStarted = java.util.concurrent.CountDownLatch(1)
        private val releasePlay = java.util.concurrent.CountDownLatch(1)

        @Volatile
        var stopCalls = 0

        override fun playBlocking(isCancelled: () -> Boolean) {
            playStarted.countDown()
            releasePlay.await(10, TimeUnit.SECONDS)
            if (stopCalls > 0 || isCancelled()) throw java.io.IOException("stopped")
        }

        override fun stop() {
            stopCalls++
            releasePlay.countDown()
        }

        fun release() = releasePlay.countDown()
    }

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newManager(
        indicator: VoiceSessionManager.IndicatorSink?,
        bubble: VoiceSessionManager.BubbleSink = NoopBubble,
        lingerMs: Long = 400,
        wakeAck: VoiceSessionManager.WakeAck? = null,
    ): VoiceSessionManager {
        val asr = AsrClient(OkHttpClient(), "http://127.0.0.1:9/asr", logger = {})
        val tts = TtsClient(
            OkHttpClient(),
            streamUrl = "http://127.0.0.1:9/tts/stream",
            synthesizeUrl = "http://127.0.0.1:9/tts/synthesize",
            streamSupport = MemStreamSupport(),
            logger = {},
        )
        return VoiceSessionManager(
            capture = AudioCapture(),
            asr = asr,
            tts = tts,
            aguiFactory = { error("pipeline LLM not used in indicator tests") },
            bubble = bubble,
            indicator = indicator,
            scope = scope,
            logger = {},
            wakeAck = wakeAck,
            listeningHintLingerMs = lingerMs,
        )
    }

    private fun awaitState(mgr: VoiceSessionManager, target: VoiceStateRouter.State, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (mgr.state != target && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("expected state $target within ${timeoutMs}ms", target, mgr.state)
    }

    /** 常幅 16k PCM 块（20ms=320 采样），幅度 A → rmsOf×32768/8000 = A/8000（方波口径）。 */
    private fun block(amplitude: Int): ByteArray {
        val out = ByteArray(640)
        for (i in 0 until 320) {
            val v = amplitude
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    // ===== S49 核心时序 =====

    @Test
    fun freshListeningShowsVoiceprint() {
        val ind = RecordingIndicator()
        val mgr = newManager(ind)
        mgr.startListening() // 按住说话/降级路径：from==IDLE 新鲜进入
        assertEquals(VoiceStateRouter.State.LISTENING, mgr.state)
        assertEquals(listOf("show:${VoiceSessionManager.IndicatorMode.VOICEPRINT}"), ind.events)
    }

    @Test
    fun processingSwapsVoiceprintForDotsWithoutHideGap() {
        val ind = RecordingIndicator()
        val mgr = newManager(ind)
        mgr.startListening()
        assertEquals(listOf("show:${VoiceSessionManager.IndicatorMode.VOICEPRINT}"), ind.events)
        // onUtterance 同步段：transition(PROCESSING) 内完成「声纹不收、三点顶替」
        mgr.onUtterance(VadDetector.Event.Utterance(shortArrayOf(1000, -1000, 800, -800), 0.5))
        assertEquals(VoiceStateRouter.State.PROCESSING, mgr.state)
        assertTrue(
            "hide 不得出现在两 show 之间（同容器顶替无收泡间隙）: ${ind.events}",
            ind.events.size >= 2 &&
                ind.events[0] == "show:${VoiceSessionManager.IndicatorMode.VOICEPRINT}" &&
                ind.events[1] == "show:${VoiceSessionManager.IndicatorMode.THINKING_DOTS}",
        )
    }

    @Test
    fun cancelInListeningHidesIndicator() {
        val ind = RecordingIndicator()
        val mgr = newManager(ind)
        mgr.startListening()
        mgr.cancelCurrentTurn()
        assertEquals(VoiceStateRouter.State.IDLE, mgr.state)
        assertEquals(
            listOf(
                "show:${VoiceSessionManager.IndicatorMode.VOICEPRINT}",
                "hide",
            ),
            ind.events,
        )
    }

    @Test
    fun cancelInProcessingHidesDots() {
        val ind = RecordingIndicator()
        val mgr = newManager(ind)
        mgr.startListening()
        mgr.onUtterance(VadDetector.Event.Utterance(shortArrayOf(1000, -1000, 800, -800), 0.5))
        awaitState(mgr, VoiceStateRouter.State.PROCESSING)
        mgr.cancelCurrentTurn()
        awaitState(mgr, VoiceStateRouter.State.IDLE)
        assertTrue("三点必须被收: ${ind.events}", ind.events.contains("hide"))
        assertTrue("hide 必须晚于三点 show: ${ind.events}", ind.events.indexOf("hide") > ind.events.lastIndexOf("show"))
    }

    @Test
    fun wozaiLingerSwapsAckBubbleForVoiceprint() {
        val ack = FakeWakeAck()
        val ind = RecordingIndicator()
        val mgr = newManager(ind, lingerMs = 250, wakeAck = ack)
        mgr.startListening(playWakeAck = true)
        awaitState(mgr, VoiceStateRouter.State.SPEAKING) // wozai 播放期：无指示事件
        assertTrue("wozai 期不得提前弹声纹: ${ind.events}", ind.events.isEmpty())
        ack.release()
        awaitState(mgr, VoiceStateRouter.State.LISTENING)
        Thread.sleep(60) // 抑制标已消费：此处不当即 show（S15 语义，防「我在」一闪而过）
        assertTrue("LISTENING 当即仍须抑制: ${ind.events}", ind.events.isEmpty())
        Thread.sleep(500) // 驻留 linger(250ms) 后 swap job 顶替声纹
        assertEquals(
            listOf("show:${VoiceSessionManager.IndicatorMode.VOICEPRINT}"),
            ind.events,
        )
    }

    @Test
    fun vadBranchFeedsRmsLevels() {
        val ind = RecordingIndicator()
        val mgr = newManager(ind)
        // IDLE 期（KWS 分发）不喂指示层：声纹只在 LISTENING 活
        mgr.routeBlockForTest(block(4000))
        assertTrue("IDLE 期不得喂电平: ${ind.levels}", ind.levels.isEmpty())
        mgr.startListening()
        assertEquals(VoiceStateRouter.State.LISTENING, mgr.state)
        mgr.routeBlockForTest(block(4000)) // 幅度 4000 → 期望电平 ≈ 4000/8000 = 0.5
        assertTrue("VAD 分支必须喂 RMS: ${ind.levels}", ind.levels.isNotEmpty())
        val lv = ind.levels.last()
        assertTrue("常幅块电平应≈0.5（方波口径）: $lv", lv in 0.45f..0.55f)
        // 静音块电平显著更低（声纹随声音起伏的数据前提）
        mgr.routeBlockForTest(block(200))
        assertTrue(ind.levels.last() < lv / 4)
        mgr.cancelCurrentTurn()
    }
}
