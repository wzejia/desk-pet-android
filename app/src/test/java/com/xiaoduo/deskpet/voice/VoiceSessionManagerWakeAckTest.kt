package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.agui.AguiClient
import com.xiaoduo.deskpet.voice.audio.AudioCapture
import com.xiaoduo.deskpet.voice.audio.VadDetector
import com.xiaoduo.deskpet.voice.client.AsrClient
import com.xiaoduo.deskpet.voice.client.TtsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * VoiceSessionManager #9 唤醒「我在」（顺序式，ADR 0003）+ cancelCurrentTurn 状态分支单测。
 *
 * 覆盖票据验收点：
 * - 迁移序列：startListening(playWakeAck=true) → SPEAKING(wozai) → 播完 → LISTENING；
 * - wakeAck=null（wozai 缺失/损坏降级）：直接 LISTENING，无 SPEAKING 中间态；
 * - 播放异常：仍进 LISTENING（播不出「我在」不吞整轮对话）；
 * - SPEAKING(wozai) 期再唤醒（S12 起麦块进 KWS 可达）= 唤醒打断唤醒：
 *   归 VoiceSessionManagerWakeInterruptTest 覆盖（原「重复唤醒被 IDLE 守卫丢弃」
 *   用例随 S12 删除——前提「唤醒在 SPEAKING 物理不可达」已不成立）；
 * - cancelCurrentTurn 三态：SPEAKING(wozai) 停播回 IDLE / LISTENING 停 VAD 回 IDLE /
 *   IDLE no-op；kwsPaused 只经 backToIdle 复位。
 *
 * 管线不启动（无 onUtterance），asr/tts 用不可达端点占位（本组用例零网络）；
 * fake WakeAck 注入（latch 可控播放/停播），被测对象零 mock。
 * startListening 会 ensureCollectLoop → JVM 上 AudioRecord 桩抛 "not mocked"，
 * 失败发生在采麦子协程内（SupervisorJob 隔离），仅日志噪音，不影响断言。
 */
class VoiceSessionManagerWakeAckTest {

    private lateinit var scope: CoroutineScope

    /** 气泡桩：行为不断言，只为让状态机跑通。 */
    private object NoopBubble : VoiceSessionManager.BubbleSink {
        override fun show(text: String) {}
        override fun append(delta: String) {}
        override fun hide() {}
        override fun hideLater(delayMs: Long) {}
    }

    /** S14-1 记录型气泡桩：「我在」应声泡 show/hide 时序断言用。 */
    private class RecordingBubble : VoiceSessionManager.BubbleSink {
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun show(text: String) { events += "show:$text" }
        override fun append(delta: String) { events += "append" }
        override fun hide() { events += "hide" }
        override fun hideLater(delayMs: Long) { events += "hideLater" }
    }

    private class MemStreamSupport : TtsClient.StreamSupport {
        override fun isUnsupported(): Boolean = false
        override fun markUnsupported() {}
    }

    /** latch 可控的「我在」播放器：play 阻塞直至 release()/stop()；stop 后 play 抛 stopped。 */
    private class FakeWakeAck : VoiceSessionManager.WakeAck {
        val playStarted = CountDownLatch(1)
        private val releasePlay = CountDownLatch(1)

        @Volatile
        var playCalls = 0

        @Volatile
        var stopCalls = 0

        @Volatile
        var throwOnPlay: Throwable? = null

        override fun playBlocking(isCancelled: () -> Boolean) {
            playCalls++
            playStarted.countDown()
            throwOnPlay?.let { throw it }
            releasePlay.await(10, TimeUnit.SECONDS)
            if (stopCalls > 0 || isCancelled()) throw IOException("stopped")
        }

        override fun stop() {
            stopCalls++
            releasePlay.countDown()
        }

        /** 测试主控放行（非 cancel 路径自然播完）。 */
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
        wakeAck: VoiceSessionManager.WakeAck? = null,
        bubble: VoiceSessionManager.BubbleSink = NoopBubble,
        lingerMs: Long = 400,
    ): VoiceSessionManager {
        // 不可达端点占位：本组用例不触发管线，零网络
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
            aguiFactory = { error("pipeline not used in wake-ack tests") },
            bubble = bubble,
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

    @Test
    fun `S59 wozai 播完 VAD 延迟启动，衰减窗内保持 SPEAKING`() {
        val ack = FakeWakeAck()
        val mgr = newManager(wakeAck = ack)
        mgr.vadStartDelayMs = 250
        mgr.startListening(playWakeAck = true)
        awaitState(mgr, VoiceStateRouter.State.SPEAKING)
        assertTrue(ack.playStarted.await(3, TimeUnit.SECONDS))
        ack.release() // 播完 → 进入尾音衰减窗
        Thread.sleep(100) // 窗内（<250ms）：仍 SPEAKING，VAD 未启动、未迁 LISTENING
        assertEquals(VoiceStateRouter.State.SPEAKING, mgr.state)
        awaitState(mgr, VoiceStateRouter.State.LISTENING) // 窗后正常落地
    }

    @Test
    fun `S59 衰减窗内 cancel 不再落地 LISTENING`() {
        val ack = FakeWakeAck()
        val mgr = newManager(wakeAck = ack)
        mgr.vadStartDelayMs = 250
        mgr.startListening(playWakeAck = true)
        awaitState(mgr, VoiceStateRouter.State.SPEAKING)
        assertTrue(ack.playStarted.await(3, TimeUnit.SECONDS))
        ack.release()
        Thread.sleep(80) // 进入衰减窗
        mgr.cancelCurrentTurn()
        awaitState(mgr, VoiceStateRouter.State.IDLE)
        Thread.sleep(400) // 已过原窗点：不得有迟到的 LISTENING 落笔
        assertEquals(VoiceStateRouter.State.IDLE, mgr.state)
    }

    @Test
    fun wakeAckPlaysThenTransitionsToListening() {
        val ack = FakeWakeAck()
        val mgr = newManager(ack)
        mgr.startListening(playWakeAck = true)
        // transition 同步：先 SPEAKING（speaking 态天然丢麦块），播放由 wozaiJob 异步跑
        assertEquals(VoiceStateRouter.State.SPEAKING, mgr.state)
        assertTrue("wozai 应在 5s 内开播", ack.playStarted.await(5, TimeUnit.SECONDS))
        ack.release()
        awaitState(mgr, VoiceStateRouter.State.LISTENING)
        assertEquals(1, ack.playCalls)
        assertEquals(0, ack.stopCalls)
        // 对话期第二层保持 true（最终由 backToIdle 复位，wozai 路径不手写）
        assertTrue(mgr.kwsPaused)
    }

    @Test
    fun wakeAckNullFallsBackToListeningDirectly() {
        val mgr = newManager(null) // wozai 缺失/损坏降级
        mgr.startListening(playWakeAck = true)
        // 同步直达 LISTENING，无 SPEAKING 中间态
        assertEquals(VoiceStateRouter.State.LISTENING, mgr.state)
        assertTrue(mgr.kwsPaused)
    }

    @Test
    fun wakeAckPlayFailureStillEntersListening() {
        val ack = FakeWakeAck().apply { throwOnPlay = IOException("tts device gone") }
        val mgr = newManager(ack)
        mgr.startListening(playWakeAck = true)
        // 播不出「我在」不该吞整轮对话——降级仍进聆听
        awaitState(mgr, VoiceStateRouter.State.LISTENING)
        assertEquals(1, ack.playCalls)
    }

    @Test
    fun cancelDuringWakeAckStopsPlaybackAndIdles() {
        val ack = FakeWakeAck()
        val mgr = newManager(ack)
        mgr.startListening(playWakeAck = true)
        assertTrue(ack.playStarted.await(5, TimeUnit.SECONDS))
        mgr.cancelCurrentTurn()
        assertEquals(VoiceStateRouter.State.IDLE, mgr.state)
        assertFalse("kwsPaused 必须经 backToIdle 复位", mgr.kwsPaused)
        assertEquals("wakeAck.stop 必须被调（停「我在」）", 1, ack.stopCalls)
        // 被打断的 wozaiJob 收尾窗口：isActive=false 防线，不得晚到迁回 LISTENING
        Thread.sleep(300)
        assertEquals(VoiceStateRouter.State.IDLE, mgr.state)
        assertEquals(1, ack.playCalls)
    }

    @Test
    fun cancelInListeningStopsVadAndIdles() {
        val ack = FakeWakeAck()
        val mgr = newManager(ack)
        mgr.startListening() // 按住说话/自检路径：不传参不播「我在」
        assertEquals(VoiceStateRouter.State.LISTENING, mgr.state)
        assertEquals("按住说话路径不得播「我在」", 0, ack.playCalls)
        mgr.cancelCurrentTurn()
        assertEquals(VoiceStateRouter.State.IDLE, mgr.state)
        assertFalse(mgr.kwsPaused)
        assertEquals(0, ack.stopCalls) // LISTENING 分支无播放可停
    }

    @Test
    fun cancelInIdleIsNoOp() {
        val ack = FakeWakeAck()
        val mgr = newManager(ack)
        assertEquals(VoiceStateRouter.State.IDLE, mgr.state)
        mgr.cancelCurrentTurn()
        assertEquals(VoiceStateRouter.State.IDLE, mgr.state)
        assertFalse(mgr.kwsPaused)
        assertEquals(0, ack.playCalls)
        assertEquals(0, ack.stopCalls)
    }

    // ===== S14-1「我在」应声泡可视化（纯现场反馈，不进消息流） =====
    // ===== S15 聆听提示泡 +「我在」驻留延长 =====

    @Test
    fun holdToTalkShowsListeningHintBubble() {
        val bubble = RecordingBubble()
        val mgr = newManager(null, bubble)
        mgr.startListening() // 按住路径（playWakeAck=false 默认）
        assertEquals(VoiceStateRouter.State.LISTENING, mgr.state)
        assertEquals("按住进聆听即显提示泡", listOf("show:在听…"), bubble.events.toList())
        mgr.cancelCurrentTurn()
        assertEquals(VoiceStateRouter.State.IDLE, mgr.state)
        assertEquals("离聆听提示泡即收", listOf("show:在听…", "hide"), bubble.events.toList())
    }

    @Test
    fun wozaiAckLingersThenSwapsToListeningHint() {
        val ack = FakeWakeAck()
        val bubble = RecordingBubble()
        val mgr = newManager(ack, bubble) // linger=400ms（测试注入）
        mgr.startListening(playWakeAck = true)
        assertEquals("唤醒即显「我在」泡", listOf("show:我在"), bubble.events.toList())
        ack.release()
        awaitState(mgr, VoiceStateRouter.State.LISTENING)
        // S15 驻留：wozai 播完不即收（linger 内零新气泡操作）
        assertEquals(listOf("show:我在"), bubble.events.toList())
        Thread.sleep(600) // 越过 linger
        // 驻留结束换聆听提示泡（内容顶替，无 hide 间隙）
        assertEquals(listOf("show:我在", "show:在听…"), bubble.events.toList())
    }

    @Test
    fun wozaiAckLingerSwapAlsoAppliesOnPlayFailureFallback() {
        val ack = FakeWakeAck().apply { throwOnPlay = IOException("tts device gone") }
        val bubble = RecordingBubble()
        val mgr = newManager(ack, bubble)
        mgr.startListening(playWakeAck = true)
        awaitState(mgr, VoiceStateRouter.State.LISTENING)
        Thread.sleep(600)
        assertEquals(listOf("show:我在", "show:在听…"), bubble.events.toList())
    }

    @Test
    fun wozaiBubbleNotDoubleHiddenOnCancel() {
        val ack = FakeWakeAck()
        val bubble = RecordingBubble()
        val mgr = newManager(ack, bubble)
        mgr.startListening(playWakeAck = true)
        assertTrue(ack.playStarted.await(5, TimeUnit.SECONDS))
        mgr.cancelCurrentTurn() // cancel 路径自身收泡（cancelCurrentTurn → bubble.hide）
        assertEquals(VoiceStateRouter.State.IDLE, mgr.state)
        Thread.sleep(600) // 越过 linger：被打断轮的 swap job 不得迟补「在听…」
        assertEquals("cancel 收泡恰好一次，job 收尾不得补枪",
            1, bubble.events.count { it == "hide" })
        assertEquals(listOf("show:我在", "hide"), bubble.events.take(2))
        assertFalse("被打断轮不得迟补聆听提示泡", bubble.events.any { it == "show:在听…" })
        // 尾部允许 hideLater：onBubbleShown 武装后回 IDLE 的策略廷时收（与回复轮同行为，幂等无害）
    }

    // ---------------- wozai 播完双查的 state 半查（变异锚 critic P2-④） ----------------

    private class MemTokenCache : AguiClient.TokenCache {
        var token: String? = null
        override fun cached(): String? = token
        override fun saveFresh(token: String) {
            this.token = token
        }
        override fun clear() {
            token = null
        }
    }

    private class MemThreadStore : AguiClient.ThreadIdStore {
        val map = HashMap<String, String>()
        override fun load(key: String): String? = map[key]
        override fun save(key: String, threadId: String) {
            map[key] = threadId
        }
        override fun remove(key: String) {
            map.remove(key)
        }
    }

    /** 带真实 HTTP 桩的 manager（管线用例专用；agui 不会被调——ASR 必败先行）。 */
    private fun newServerManager(ack: FakeWakeAck, server: MockWebServer): VoiceSessionManager {
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
            wakeAck = ack,
        )
    }

    /** 状态行闩：以 status 文案为管线落地信号（同 VoiceSessionManagerTranscriptTest 体例）。 */
    private fun awaitStatus(mgr: VoiceSessionManager, prefix: String) {
        val latch = CountDownLatch(1)
        mgr.statusListener = { s -> if (s.startsWith(prefix)) latch.countDown() }
        assertTrue("expected status '$prefix…' within 10s", latch.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun wozaiCompletionDuringPipelineDoesNotClobberProcessing() {
        // 变异锚（critic P2-④）：删 wozaiJob 双查的 state==SPEAKING 半查 → 本用例红。
        // 场景：wozai 播放中管线抢跑（onUtterance 直入 PROCESSING），wozai 播完时 state
        // 已非 SPEAKING——不得把 PROCESSING 覆迁回 LISTENING（isActive 半查不拦此路，
        //  job 未被 cancel）。
        val ack = FakeWakeAck()
        val server = MockWebServer()
        server.start()
        try {
            val mgr = newServerManager(ack, server)
            // ASR 慢响应（2s）：保证 wozai 播完时管线仍在 PROCESSING
            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody("""{"code":500,"msg":"asr down"}""")
                    .setBodyDelay(2, TimeUnit.SECONDS),
            )
            mgr.startListening(playWakeAck = true)
            assertTrue(ack.playStarted.await(5, TimeUnit.SECONDS))
            mgr.onUtterance(VadDetector.Event.Utterance(ShortArray(1600) { 100 }, 0.5))
            assertEquals(VoiceStateRouter.State.PROCESSING, mgr.state)
            ack.release() // wozai 播完：job 未 cancel（isActive=true）但 state 已非 SPEAKING
            Thread.sleep(500) // wozaiJob 收尾窗口
            assertEquals(
                "wozai 播完不得把 PROCESSING 覆迁回 LISTENING",
                VoiceStateRouter.State.PROCESSING,
                mgr.state,
            )
            // S8-7 critic P2 变异锚：wozai 完时 state 非 SPEAKING（被 onUtterance 抢跑切 PROCESSING），
            // wakeAckActive 仍须复位（finally 无条件）--删 finally -> 残留 true -> 红
            assertFalse(
                "wozai 完时若 state 非 SPEAKING 仍须复位 wakeAckActive",
                mgr.wakeAckActive.value,
            )
            awaitStatus(mgr, "ASR 失败") // 管线自然落地（ASR 500 → 静默回 IDLE）
            // critic P1-⑦：status 先于 backToIdle 执行，latch 一放 state 迁移可能未落地——
            // 必须轮询等 IDLE，不得断言紧接 awaitStatus 的瞬时值
            awaitState(mgr, VoiceStateRouter.State.IDLE)
        } finally {
            server.shutdown()
        }
    }

    // ---------------- S8-7 唤醒即时视觉反馈信号 ----------------

    @Test
    fun wakeAckActiveSignalsDuringWozaiThenClearsOnListening() {
        // S8-7：唤醒路径 wakeAckActive 时序--入口置 true（transition gate 跳 TALKING，
        // S13 起视觉=入口直接 WAITING 静止，原 playOnceThen(JUMPING->WAITING) 已取消），
        // wozai 完进 LISTENING 置 false。
        // 变异锚：删入口置 true -> SPEAKING 时 wakeAckActive=false -> transition 误设 TALKING -> 红；
        //        删 wozai 完置 false -> LISTENING 时仍 true（残留，下次 SPEAKING 漏 gate）-> 红
        val ack = FakeWakeAck()
        val mgr = newManager(ack)
        assertFalse("IDLE 态 wakeAckActive 应 false", mgr.wakeAckActive.value)
        mgr.startListening(playWakeAck = true)
        assertEquals(VoiceStateRouter.State.SPEAKING, mgr.state)
        assertTrue("唤醒入口应置 wakeAckActive=true（gate TALKING，S13 起 WAITING 静止接管）", mgr.wakeAckActive.value)
        assertTrue(ack.playStarted.await(5, TimeUnit.SECONDS))
        ack.release()
        awaitState(mgr, VoiceStateRouter.State.LISTENING)
        assertFalse("wozai 完进 LISTENING 应置 wakeAckActive=false", mgr.wakeAckActive.value)
    }

    @Test
    fun wakeAckActiveFalseOnHoldToTalkPath() {
        // S8-7：按住说话路径（playWakeAck=false）wakeAckActive 恒 false，视觉走通用 TALKING 不变
        val ack = FakeWakeAck()
        val mgr = newManager(ack)
        mgr.startListening() // 按住说话/自检路径
        assertEquals(VoiceStateRouter.State.LISTENING, mgr.state)
        assertFalse("按住说话路径 wakeAckActive 须 false（不 gate 通用映射）", mgr.wakeAckActive.value)
        assertEquals("按住说话路径不得播「我在」", 0, ack.playCalls)
    }

    @Test
    fun pureWakeWordUtteranceReturnsToIdle() {
        // 2026-08-10 根因修复：唤醒后 LISTENING 里用户只说「小朵小朵」→ 前缀剥离为空
        // → 回 IDLE 让 KWS 接管（不再 playAckThenListen，防「ASR→strip空→重播」死循环：
        // 15:23-24 日志 8 轮实证，用户反复喊唤醒词永不回 IDLE → 感知"唤不醒"）。
        // 变异锚：若改回重播 → playCalls=2 + LISTENING + kwsPaused=true → 红
        val ack = FakeWakeAck()
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setBody("""{"code":200,"msg":"ok","data":{"text":"小朵小朵。"}}"""),
            )
            val mgr = newServerManager(ack, server)
            mgr.startListening(playWakeAck = true)
            assertTrue("wozai 应开播", ack.playStarted.await(5, TimeUnit.SECONDS))
            ack.release()
            awaitState(mgr, VoiceStateRouter.State.LISTENING)

            mgr.onUtterance(VadDetector.Event.Utterance(ShortArray(1600) { 100 }, 0.5))

            // ASR 返回纯唤醒词 → 回 IDLE（不重播，不继续聆听）
            awaitState(mgr, VoiceStateRouter.State.IDLE)
            assertEquals("纯唤醒词不得重播「我在」", 1, ack.playCalls)
            assertEquals("不得送对话（仅 ASR 一次请求）", 1, server.requestCount)
            assertFalse("回 IDLE 后 KWS 应恢复（不再 paused）", mgr.kwsPaused)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun cancelDuringWakeAckClearsWakeAckActive() {        // S8-7：唤醒 wozai 期间被打断 -> wakeAckActive 复位 false（防下次 SPEAKING 漏 TALKING gate）
        val ack = FakeWakeAck()
        val mgr = newManager(ack)
        mgr.startListening(playWakeAck = true)
        assertTrue(ack.playStarted.await(5, TimeUnit.SECONDS))
        assertTrue("cancel 前 wakeAckActive 应 true", mgr.wakeAckActive.value)
        mgr.cancelCurrentTurn()
        assertEquals(VoiceStateRouter.State.IDLE, mgr.state)
        assertFalse("cancel 必须复位 wakeAckActive=false", mgr.wakeAckActive.value)
    }
}
