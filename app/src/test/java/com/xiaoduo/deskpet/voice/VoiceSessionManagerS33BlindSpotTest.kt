package com.xiaoduo.deskpet.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 * S33 聆听盲区修复单测：LISTENING 期唤醒词可达。
 *
 * 背景：VoiceStateRouter 路由 LISTENING→VAD 单投——聆听期（「我在」播完、小朵等指令的
 * 1-2s 窗口+30s 长尾）再喊「小朵小朵」，麦块全进 VAD，KWS 不可达，100% 必漏
 * （2026-09-11 14.18 受控测试实锤，用户连喊有漏检的主机制之一）。
 *
 * 修复（最小侵入两处）：
 * - routeBlock 的 VAD 分支旁路喂 kwsSink（主投 VAD 判停不变；走 NLMS 消「我在」尾回声，
 *   零参考直通安全）——命中回调 startListening；
 * - startListening 开 LISTENING 口：仅唤醒路径（playWakeAck=true）先 cancelCurrentTurn
 *   （既有 LISTENING 分支：vad.stop+backToIdle，已收集音频丢弃）再重播「我在」→ 重新聆听
 *   ——与 S12 唤醒词打断接续同构。按住路径（playWakeAck=false）保持 no-op（最小侵入）。
 *
 * 变异锚（票据点名）：
 * - 删 routeBlock VAD 分支旁路 → 用例 1 红（kwsSink 零调用）；
 * - 恢复 startListening 非 IDLE 早退 → 用例 2 红（state 停在 LISTENING、无 cancel 日志）；
 * - 用例 3 锁按住路径 no-op（防开口误伤按住重听语义）；
 * - 用例 4 锁 SPEAKING 单投不双喂（route 单投语义回归）。
 */
class VoiceSessionManagerS33BlindSpotTest {

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

    /** latch 可控「我在」播放器（体例同 WakeInterruptTest.FakeWakeAck）。 */
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

    private fun newManager(logs: MutableList<String>, wakeAck: VoiceSessionManager.WakeAck? = null): VoiceSessionManager {
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
            aguiFactory = { error("pipeline not used in S33 blind-spot tests") },
            bubble = NoopBubble,
            scope = scope,
            logger = { msg -> logs += msg },
            wakeAck = wakeAck,
        )
    }

    private fun awaitQuiescence() {
        Thread.sleep(300) // listeningHintLingerMs=默认 2500 不涉本组；管线收尾 300ms 充裕
    }

    @Test
    fun listeningBlockBypassesToKwsSink() {
        val logs = mutableListOf<String>()
        val mgr = newManager(logs)
        val fed = mutableListOf<ByteArray>()
        mgr.kwsSink = { fed += it }

        mgr.startListening() // 按住语义：直进 LISTENING
        assertEquals(VoiceStateRouter.State.LISTENING, mgr.state)

        val block = ByteArray(VadDetector.BLOCK_BYTES)
        mgr.routeBlockForTest(block)

        assertTrue("LISTENING 期块必须旁路喂 KWS（S33 盲区修复点）", fed.isNotEmpty())
        assertTrue("旁路喂的是原始块（NLMS 零参考直通）", fed[0] === block)
    }

    @Test
    fun relaunchWakeDuringListeningRestartsAckChain() {
        val logs = mutableListOf<String>()
        val ack = FakeWakeAck()
        val mgr = newManager(logs, ack)
        mgr.kwsSink = { }

        mgr.startListening()
        assertEquals(VoiceStateRouter.State.LISTENING, mgr.state)

        // 模拟 KWS 在聆听期命中 → onWake → startListening(playWakeAck=true)
        mgr.startListening(playWakeAck = true)

        assertTrue(
            "重唤醒必须先终止当前聆听轮（cancelCurrentTurn LISTENING 分支）",
            logs.any { it.contains("cancel listening") },
        )
        assertEquals("重唤醒接续播「我在」→ SPEAKING(wozai)", VoiceStateRouter.State.SPEAKING, mgr.state)

        ack.playStarted.await(2, TimeUnit.SECONDS)
        ack.release() // 「我在」播完
        awaitQuiescence()
        assertEquals("播完回 LISTENING，新一轮聆听开始", VoiceStateRouter.State.LISTENING, mgr.state)
    }

    @Test
    fun holdToTalkDuringListeningStaysNoOp() {
        val logs = mutableListOf<String>()
        val mgr = newManager(logs)

        mgr.startListening()
        assertEquals(VoiceStateRouter.State.LISTENING, mgr.state)

        // 按住说话路径（playWakeAck=false）在 LISTENING 保持旧 no-op：VAD 已在采音
        // （S33 开口仅限唤醒路径，最小侵入——行为锁定，防开口误伤按住语义）
        mgr.startListening(playWakeAck = false)

        assertEquals(VoiceStateRouter.State.LISTENING, mgr.state)
        assertTrue("按住路径不得触发重唤醒 cancel 链", logs.none { it.contains("cancel listening") })
    }

    @Test
    fun speakingBlockNotDoubleFed() {
        val logs = mutableListOf<String>()
        val ack = FakeWakeAck()
        val mgr = newManager(logs, ack)

        mgr.startListening(playWakeAck = true) // IDLE → SPEAKING(wozai)
        assertTrue(ack.playStarted.await(2, TimeUnit.SECONDS))
        assertEquals(VoiceStateRouter.State.SPEAKING, mgr.state)

        var fedCount = 0
        mgr.kwsSink = { fedCount++ }

        mgr.routeBlockForTest(ByteArray(VadDetector.BLOCK_BYTES))

        assertEquals("SPEAKING 态 route 单投 KWS 分支，恰喂一次（VAD 旁路不得双投）", 1, fedCount)
        ack.release()
    }
}
