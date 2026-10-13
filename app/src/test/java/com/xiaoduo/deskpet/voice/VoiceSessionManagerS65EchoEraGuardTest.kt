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
 * S65 回声纪元守卫单测：S40 盲窗回灌只收「wozai 写完（含喇叭尾巴余量）之后」捕获的块。
 *
 * 背景（2026-09-23 测试板 10:49 三轮打断重唤醒实锤）：打断播报场景重唤醒后，S40 盲窗
 * 缓冲内是「旧 TTS 残声 + wozai『我在』回声」（maxLevel 0.66-0.99），回灌时 NLMS 因
 * 参考过期（>400ms，NlmsEchoCanceller REF_FRESH_MS 直通）零消除，VAD 在回灌块里判出
 * 伪 utterance（活块 vad diag level=0.0007 全静却 96ms 后报 1.2s 语音）→ ASR 转出
 * 「告诉我我在。」系回声文本 → 假对话轮抢占聆听窗。
 *
 * 修复锚（方向 1：静音确认后才收）：
 * - 回灌截止线 cutoff = vadSeamNs - (vadStartDelayMs + ECHO_TAIL_GUARD_MS)：
 *   capturedAt < cutoff 的块（= wozai 播放期采集，含旧 TTS 残声与「我在」回声）丢弃；
 * - 播放期块采集上界 ≈ wozai 写完（playBlocking 返回）≈ seam - vadStartDelayMs，
 *   播放期块恒 < cutoff（TAIL_GUARD 再多保喇叭尾巴 150ms）；
 * - delay 窗尾部（写完+尾巴之后）捕获的迟到块仍回灌（S40 话头找回能力收窄保留）。
 *
 * 变异锚：
 * - 删 cutoff 过滤（回灌全量喂 VAD）→ 用例 1 红（3 响块判起 → S51 hold 出现）；
 * - 过滤条件反号（只丢 delay 窗块）→ 用例 1/2 双红；
 * - cutoff 锚漏掉 vadStartDelayMs（= seam - TAIL_GUARD）→ 用例 2 红（迟到块被误丢）。
 */
class VoiceSessionManagerS65EchoEraGuardTest {

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

    private class FakeWakeAck : VoiceSessionManager.WakeAck {
        val playStarted = CountDownLatch(1)
        private val releasePlay = CountDownLatch(1)
        override fun playBlocking(isCancelled: () -> Boolean) {
            playStarted.countDown()
            releasePlay.await(10, TimeUnit.SECONDS)
        }
        override fun stop() {}
        fun release() = releasePlay.countDown()
    }

    private fun newManager(
        logs: MutableList<String>,
        ack: FakeWakeAck,
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
            aguiFactory = { error("pipeline LLM stage not used in S65 tests") },
            bubble = NoopBubble,
            scope = scope,
            logger = { msg -> logs += msg },
            wakeAck = ack,
        )
    }

    /** S59 尾音衰减窗默认 400ms：轮询等 wozai 完成+delay 后进 LISTENING（比定长 sleep 稳）。 */
    private fun awaitListening(mgr: VoiceSessionManager) {
        val deadline = System.currentTimeMillis() + 5_000
        while (mgr.state != VoiceStateRouter.State.LISTENING && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertEquals(VoiceStateRouter.State.LISTENING, mgr.state)
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

    /** 播放期（回声纪元）块：捕获时刻远早于任何截止线（真实板 wozai 期 ≈ seam-400ms 起）。 */
    private fun echoEraStamped(pcm: ByteArray) = StampedBlock(pcm, System.nanoTime() - 1_000_000_000L)

    private fun nowStamped(pcm: ByteArray) = StampedBlock(pcm, System.nanoTime())

    @Test
    fun echoEraBlocksDroppedOnBackfill() {
        val logs = mutableListOf<String>()
        val ack = FakeWakeAck()
        val mgr = newManager(logs, ack)

        mgr.startListening(playWakeAck = true)
        assertTrue(ack.playStarted.await(2, TimeUnit.SECONDS))
        assertEquals(VoiceStateRouter.State.SPEAKING, mgr.state)

        // wozai 播放期捕获的 3 个响块（= 旧 TTS 残声 +「我在」回声）进盲窗
        mgr.routeBlockForTest(echoEraStamped(tone(4000)))
        mgr.routeBlockForTest(echoEraStamped(tone(4000)))
        mgr.routeBlockForTest(echoEraStamped(tone(4000)))
        assertEquals(3, mgr.blindBufferSizeForTest)

        ack.release()
        awaitListening(mgr)

        // 接缝后首块（静音）触发回灌：3 个回声纪元块被丢弃，VAD 一个响块都没吃到
        mgr.routeBlockForTest(nowStamped(silence()))
        repeat(VadDetector.SILENCE_END_TICKS + VoiceSessionManager.CONTINUATION_TICKS) {
            mgr.routeBlockForTest(nowStamped(silence()))
        }
        assertEquals("回灌后缓冲清空", 0, mgr.blindBufferSizeForTest)
        assertFalse(mgr.pendingBlindBackfillForTest)
        assertTrue("丢弃数留痕", logs.any { it.contains("S65 echo-era dropped 3") })
        assertFalse("回声块不得进 VAD 判起（修复前：伪 utterance → S51 hold）", logs.any { it.contains("S51 continuation hold") })
        assertFalse("回声块不得产出伪 utterance 进管线", logs.any { it.contains("state -> PROCESSING") })
        assertEquals("全程保持聆听（真实用户话头不被回声假轮抢占）", VoiceStateRouter.State.LISTENING, mgr.state)
    }

    @Test
    fun postEraStaleBlocksStillBackfilled() {
        val logs = mutableListOf<String>()
        val ack = FakeWakeAck()
        val mgr = newManager(logs, ack)

        mgr.startListening(playWakeAck = true)
        assertTrue(ack.playStarted.await(2, TimeUnit.SECONDS))
        ack.release()
        awaitListening(mgr)
        assertTrue(mgr.pendingBlindBackfillForTest)

        // delay 窗尾部（seam 前 50ms 捕获 = wozai 写完+尾巴之后）的迟到响块：
        // 消费停顿使其晚于接缝到达——S40 话头找回收窄后的合法回灌对象
        val seam = mgr.vadSeamNsForTest
        val stale = StampedBlock(tone(4000), seam - 50_000_000L)
        mgr.routeBlockForTest(stale)
        mgr.routeBlockForTest(stale)
        mgr.routeBlockForTest(stale)
        assertEquals("迟到块改道缓冲", 3, mgr.blindBufferSizeForTest)

        // 接缝后活静音块触发回灌：3 响块保留回灌 → VAD 判起 → 判停 → S51 续听窗挂起
        mgr.routeBlockForTest(nowStamped(silence()))
        repeat(VadDetector.SILENCE_END_TICKS - 1) { mgr.routeBlockForTest(nowStamped(silence())) }
        assertTrue("迟到块保留回灌", logs.any { it.contains("S40 blind-window backfill: 3 blocks") })
        assertTrue("找回语义保留（delay 窗尾部话头仍可判起）", logs.any { it.contains("S51 continuation hold") })
        assertFalse("无回声纪元块可丢", logs.any { it.contains("echo-era dropped") && !it.contains("dropped 0") })
    }
}
