package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.agui.AguiClient
import com.xiaoduo.deskpet.voice.audio.AudioCapture
import com.xiaoduo.deskpet.voice.audio.VadDetector
import com.xiaoduo.deskpet.voice.audio.WavCodec
import com.xiaoduo.deskpet.voice.client.AsrClient
import com.xiaoduo.deskpet.voice.client.TtsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * S12 唤醒词打断（spike 双合格转正）VoiceSessionManager 全链单测——MockWebServer
 * 端到端桩 + fake WakeAck 注入，被测对象零 mock（体例同 VoiceSessionManagerBargeInTest /
 * VoiceSessionManagerWakeAckTest，SPEAKING/PROCESSING 驱动模式照搬前者）。
 *
 * 背景：S11-3 spike 真机双合格（TTS 播放 4min 误触发 0 / 播放中真声检出 8/10）后，
 * SPEAKING/PROCESSING 麦块恒进 KWS（VoiceStateRouter），对话期唤醒词命中 =
 * 打断当前轮：startListening(playWakeAck=true) 先 cancelCurrentTurn（S8-3 成熟路径：
 * 封轮/停播/收泡/部分回复补发 done/回 IDLE，全同步）再接续播「我在」→ LISTENING。
 *
 * 覆盖：
 * - SPEAKING（TTS 播放中）唤醒 → cancel 副作用（done 恰好一次/无 error/收泡）→
 *   SPEAKING(wozai) → 播完 LISTENING；残跑管线 sealed 守卫下零追加、不得腐蚀新状态；
 * - PROCESSING 唤醒同验（TTS 全降级未进 SPEAKING 的变体）；
 * - 唤醒打断唤醒（SPEAKING(wozai) 期再命中，S12 起可达，守卫四问 Q1/Q2）：重播
 *   「我在」，旧 wozaiJob 残跑 finally 不得复位新一轮 wakeAckActive
 *   （wozaiEpoch 世代守卫变异锚：删 playAckThenListen 的世代比对 → 红）。
 *
 * 变异锚（票据点名）：startListening 唤醒路径的非 IDLE 早退恢复（不 cancel）→
 * 前两条用例红（无 cancel → done 不补发/不收泡/wozai 不开播）。
 */
class VoiceSessionManagerWakeInterruptTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope

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

    /** 气泡桩：行为不断言，只为让管线跑通。 */
    private object NoopBubble : VoiceSessionManager.BubbleSink {
        override fun show(text: String) {}
        override fun append(delta: String) {}
        override fun hide() {}
        override fun hideLater(delayMs: Long) {}
    }

    /** 记录型气泡：cancel 收泡与残跑零追加断言。 */
    private class RecordingBubble : VoiceSessionManager.BubbleSink {
        val ops = CopyOnWriteArrayList<String>()
        override fun show(text: String) {
            ops.add("show")
        }
        override fun append(delta: String) {
            ops.add("append")
        }
        override fun hide() {
            ops.add("hide")
        }
        override fun hideLater(delayMs: Long) {
            ops.add("hideLater")
        }
    }

    private class MemStreamSupport : TtsClient.StreamSupport {
        override fun isUnsupported(): Boolean = false
        override fun markUnsupported() {}
    }

    /** 记录型 sink：事件序 + 首 delta latch（cancel 时机锚点）。 */
    private class RecordingSink : VoiceSessionManager.TranscriptSink {
        val events = CopyOnWriteArrayList<String>()
        val firstDeltaLatch = CountDownLatch(1)

        override fun onUserUtterance(text: String) {
            events.add("user:$text")
        }

        override fun onAssistantDelta(delta: String) {
            events.add("delta:$delta")
            firstDeltaLatch.countDown()
        }

        override fun onAssistantDone() {
            events.add("done")
        }

        override fun onAssistantError(message: String) {
            events.add("error:$message")
        }
    }

    /**
     * latch 可控「我在」播放器（单轮播放用例）：play 阻塞直至 release()；stop() 幂等
     * 记录调用次数但不毒化后续播放——本组用例的 stop 均落在「未在播」窗口（cancel
     * 先于新一轮开播），契约语义即 no-op（WakeAck.stop：未在播 no-op）。
     */
    private class FakeWakeAck : VoiceSessionManager.WakeAck {
        val playStarted = CountDownLatch(1)
        private val releasePlay = CountDownLatch(1)

        @Volatile
        var playCalls = 0

        @Volatile
        var stopCalls = 0

        override fun playBlocking(isCancelled: () -> Boolean) {
            playCalls++
            playStarted.countDown()
            releasePlay.await(10, TimeUnit.SECONDS)
            if (isCancelled()) throw IOException("stopped")
        }

        override fun stop() {
            stopCalls++
        }

        /** 测试主控放行（自然播完）。 */
        fun release() = releasePlay.countDown()
    }

    /**
     * 双轮「我在」播放器（唤醒打断唤醒用例）：两轮播放各自阻塞、各自放行；首轮的
     * stop 不打断在播阻塞（测试主控掌握旧 job 收尾落点，把 finally 确定性放到
     * 新一轮 wakeAckActive=true 之后——世代守卫变异锚的决定性前提）。
     */
    private class ChainedWakeAck : VoiceSessionManager.WakeAck {
        val firstPlayStarted = CountDownLatch(1)
        val secondPlayStarted = CountDownLatch(1)
        val releaseFirstPlay = CountDownLatch(1)
        val releaseSecondPlay = CountDownLatch(1)
        val playCalls = AtomicInteger(0)
        val stopCalls = AtomicInteger(0)

        override fun playBlocking(isCancelled: () -> Boolean) {
            val n = playCalls.incrementAndGet()
            if (n == 1) {
                firstPlayStarted.countDown()
                releaseFirstPlay.await(10, TimeUnit.SECONDS)
                if (stopCalls.get() > 0 || isCancelled()) throw IOException("stopped")
            } else {
                secondPlayStarted.countDown()
                releaseSecondPlay.await(10, TimeUnit.SECONDS)
                if (isCancelled()) throw IOException("stopped")
            }
        }

        override fun stop() {
            stopCalls.incrementAndGet()
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun newManager(
        sink: VoiceSessionManager.TranscriptSink,
        bubble: VoiceSessionManager.BubbleSink,
        wakeAck: VoiceSessionManager.WakeAck,
    ): VoiceSessionManager {
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
            bubble = bubble,
            scope = scope,
            logger = {},
            wakeAck = wakeAck,
            listeningHintLingerMs = 150, // S15：驻留 swap 注入小值，下方 sleep 300 后再快照
        ).also { it.transcriptSink = sink }
    }

    /** 零网络 manager（唤醒打断唤醒用例：管线不启动，不可达端点占位）。 */
    private fun newBareManager(wakeAck: VoiceSessionManager.WakeAck): VoiceSessionManager {
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
            aguiFactory = { error("pipeline not used in bare wake-interrupt test") },
            bubble = NoopBubble,
            scope = scope,
            logger = {},
            wakeAck = wakeAck,
        )
    }

    private fun utterance() = VadDetector.Event.Utterance(ShortArray(1600) { 100 }, 0.5)

    private fun asrOk(text: String) =
        MockResponse().setBody("""{"code":200,"msg":"ok","data":{"text":"$text"}}""")

    // token 必须是 36 字符 UUID 形态（AguiClient 登录响应校验，否则判格式非法）
    private fun loginOk() =
        MockResponse().setBody("""{"code":200,"msg":"ok","token":"00000000-0000-0000-0000-000000000001"}""")

    private fun threadOk() = MockResponse().setBody("""{"code":200,"msg":"ok","data":"thread-test"}""")

    private fun sseBody(vararg deltas: String) =
        (
            listOf(
                """{"type":"RUN_STARTED","runId":"r"}""",
                """{"type":"TEXT_MESSAGE_START","messageId":"m1","role":"assistant"}""",
            ) +
                deltas.map { """{"type":"TEXT_MESSAGE_CONTENT","messageId":"m1","delta":"$it"}""" } +
                listOf(
                    """{"type":"TEXT_MESSAGE_END","messageId":"m1"}""",
                    """{"type":"RUN_FINISHED","runId":"r"}""",
                )
            ).joinToString("\n\n") { "data: $it" } + "\n\n"

    /** 拖慢 SSE：cancel/唤醒时刻与后续 delta 之间构造 ≥100ms 时间窗（防快照竞态 flaky）。 */
    private fun throttledSse(vararg deltas: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(sseBody(*deltas))
        .throttleBody(50, 100, TimeUnit.MILLISECONDS)

    private fun http500(msg: String) =
        MockResponse().setResponseCode(500).setBody("""{"code":500,"msg":"$msg"}""")

    /** TTS 流式成功桩（NDJSON：audioData 帧 + done 帧），streamSupport 未禁用时 synthesize 走此路径。 */
    private fun synthStreamOk(pcm: ByteArray) = MockResponse()
        .setHeader("Content-Type", "application/x-ndjson")
        .setBody(
            listOf(
                """{"audioData":"${wavFrame(pcm)}","format":"wav"}""",
                """{"type":"done"}""",
            ).joinToString("\n") + "\n",
        )

    private fun wavFrame(pcm: ByteArray): String =
        Base64.getEncoder().encodeToString(WavCodec.pcmToWav(pcm, RevealScheduler.TTS_SAMPLE_RATE))

    private fun awaitState(mgr: VoiceSessionManager, target: VoiceStateRouter.State, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (mgr.state != target && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("expected state $target within ${timeoutMs}ms", target, mgr.state)
    }

    @Test
    fun wakeDuringSpeakingInterruptsPlaysAckAndListens() {
        // S12：SPEAKING（TTS 播放中）唤醒词命中 → cancelCurrentTurn（封轮+停播+收泡+
        // 部分回复补发 done+回 IDLE）→ 播「我在」→ LISTENING。
        // 变异锚①：startListening 唤醒路径非 IDLE 早退恢复（不 cancel）→ done 不补发、
        // 不收泡、wozai 不开播 → 红。变异锚②：残跑管线多轮/播放守卫删除 → 末段红。
        val sink = RecordingSink()
        val bubble = RecordingBubble()
        val ack = FakeWakeAck()
        val mgr = newManager(sink, bubble, ack)
        server.enqueue(asrOk("小朵小朵今天天气怎么样"))
        server.enqueue(loginOk())
        server.enqueue(threadOk())
        server.enqueue(throttledSse("第一句很长很长很长很长很长。", "第二句。", "第三句。"))
        server.enqueue(synthStreamOk(ByteArray(1600))) // 首句 TTS Audio -> transition(SPEAKING)
        repeat(8) { server.enqueue(http500("tts down")) } // 余句降级

        // SPEAKING 窗口窄（JVM 上 AudioTrack stub 即抛不阻塞），先挂订阅再驱动
        // （BargeInTest 体例）；throttled SSE 拖住余句文本，SPEAKING 得以保持到唤醒时刻
        val spkLatch = CountDownLatch(1)
        val spkJob = scope.launch {
            mgr.stateFlow.collect { if (it == VoiceStateRouter.State.SPEAKING) spkLatch.countDown() }
        }
        mgr.onUtterance(utterance())
        assertTrue("首 delta 应在 15s 内到达", sink.firstDeltaLatch.await(15, TimeUnit.SECONDS))
        assertTrue("应在 15s 内进 SPEAKING（首句 TTS Audio）", spkLatch.await(15, TimeUnit.SECONDS))
        assertEquals("唤醒前应为 SPEAKING", VoiceStateRouter.State.SPEAKING, mgr.state)
        val ttsReqsBeforeWake = server.requestCount

        mgr.startListening(playWakeAck = true) // = VoiceForegroundService.onWake 的调用

        // cancelCurrentTurn 副作用（全同步落地）
        assertEquals("done 应恰好一次: ${sink.events}", 1, sink.events.count { it == "done" })
        assertEquals("不得有 error: ${sink.events}", 0, sink.events.count { it.startsWith("error") })
        assertTrue("cancel 必须收泡: ${bubble.ops}", bubble.ops.contains("hide"))
        assertEquals("cancel 必须停「我在」挂点（未在播 no-op，幂等）", 1, ack.stopCalls)
        // 「我在」接续：同步进 SPEAKING(wozai) + wakeAckActive 置位（S8-7 视觉 gate）
        assertEquals("打断后应同步进 SPEAKING(wozai): ${sink.events}", VoiceStateRouter.State.SPEAKING, mgr.state)
        assertTrue("唤醒入口应置 wakeAckActive=true", mgr.wakeAckActive.value)
        assertTrue("wozai 应在 5s 内开播", ack.playStarted.await(5, TimeUnit.SECONDS))

        ack.release() // 「我在」播完 → LISTENING（S15：驻留 swap 在 150ms 后补「在听…」，属预期）
        awaitState(mgr, VoiceStateRouter.State.LISTENING)
        // 快照取在驻留 swap 落地后：不变量针对的是下方残跑管线，非 wozai 收尾/提示泡
        Thread.sleep(300)
        val snapshot = sink.events.size
        val bubbleSnapshot = bubble.ops.size
        assertFalse("wozai 完进 LISTENING 应复位 wakeAckActive", mgr.wakeAckActive.value)
        assertTrue("聆听期 kwsPaused 应为 true", mgr.kwsPaused)
        assertEquals(1, ack.playCalls)
        spkJob.cancel()

        // 残跑管线落地（throttled SSE 全量投递 + OkHttp blocking 不响应 cancel）：
        // sealed 守卫下零追加、不得把新轮 LISTENING 拉回 SPEAKING
        Thread.sleep(4_000)
        assertEquals("残跑不得追加 sink 事件: ${sink.events}", snapshot, sink.events.size)
        assertEquals("残跑不得追加气泡操作: ${bubble.ops}", bubbleSnapshot, bubble.ops.size)
        assertEquals(1, sink.events.count { it == "done" })
        assertEquals(
            "残跑不得把 state 拉回 SPEAKING（sealed 守卫防状态腐蚀）",
            VoiceStateRouter.State.LISTENING,
            mgr.state,
        )
        assertEquals(
            "cancel 后残跑不得新增 TTS 请求: before=$ttsReqsBeforeWake now=${server.requestCount}",
            ttsReqsBeforeWake,
            server.requestCount,
        )
    }

    @Test
    fun wakeDuringProcessingInterruptsPlaysAckAndListens() {
        // S12：PROCESSING（已上屏，delta 在飞，TTS 全降级未进 SPEAKING）唤醒词命中 →
        // 同一打断接续路径。变异锚同 SPEAKING 用例（早退恢复 → 红）；
        // 残跑多轮守卫锚：删多轮 !sealed → 残跑 fire「继续聆听」→ 红。
        val sink = RecordingSink()
        val bubble = RecordingBubble()
        val ack = FakeWakeAck()
        val statuses = CopyOnWriteArrayList<String>()
        val mgr = newManager(sink, bubble, ack).also { it.statusListener = { statuses.add(it) } }
        server.enqueue(asrOk("小朵小朵今天天气怎么样"))
        server.enqueue(loginOk())
        server.enqueue(threadOk())
        server.enqueue(throttledSse("第一句很长很长很长很长很长。", "第二句。", "第三句。"))
        repeat(8) { server.enqueue(http500("tts down")) } // TTS 全降级：state 保持 PROCESSING

        mgr.onUtterance(utterance())
        assertTrue("首 delta 应在 15s 内到达", sink.firstDeltaLatch.await(15, TimeUnit.SECONDS))
        assertEquals("唤醒前应为 PROCESSING", VoiceStateRouter.State.PROCESSING, mgr.state)

        mgr.startListening(playWakeAck = true)

        assertEquals("done 应恰好一次: ${sink.events}", 1, sink.events.count { it == "done" })
        assertEquals("不得有 error: ${sink.events}", 0, sink.events.count { it.startsWith("error") })
        assertTrue("cancel 必须收泡: ${bubble.ops}", bubble.ops.contains("hide"))
        assertEquals("打断后应同步进 SPEAKING(wozai): ${sink.events}", VoiceStateRouter.State.SPEAKING, mgr.state)
        assertTrue("唤醒入口应置 wakeAckActive=true", mgr.wakeAckActive.value)
        assertTrue("wozai 应在 5s 内开播", ack.playStarted.await(5, TimeUnit.SECONDS))

        ack.release()
        awaitState(mgr, VoiceStateRouter.State.LISTENING)
        // 快照取在驻留 swap 落地后（S15，见上一用例注释）
        Thread.sleep(300)
        val snapshot = sink.events.size
        val bubbleSnapshot = bubble.ops.size
        assertFalse("wozai 完进 LISTENING 应复位 wakeAckActive", mgr.wakeAckActive.value)
        assertTrue("聆听期 kwsPaused 应为 true", mgr.kwsPaused)
        assertEquals(1, ack.playCalls)

        Thread.sleep(4_000) // 残跑管线落地余量（throttled 全量投递）
        assertEquals("残跑不得追加 sink 事件: ${sink.events}", snapshot, sink.events.size)
        assertEquals("残跑不得追加气泡操作: ${bubble.ops}", bubbleSnapshot, bubble.ops.size)
        assertEquals(1, sink.events.count { it == "done" })
        assertEquals(VoiceStateRouter.State.LISTENING, mgr.state)
        assertFalse(
            "残跑多轮逻辑不得 fire「继续聆听」: $statuses",
            statuses.any { it.contains("继续聆听") },
        )
    }

    @Test
    fun wakeDuringWakeAckRestartsAckAndKeepsSignalTrue() {
        // 守卫四问 Q1/Q2：SPEAKING(wozai) 期再喊唤醒词（S12 起麦块进 KWS 可达）=
        // 唤醒打断唤醒——cancel 首轮（停播/复位信号/回 IDLE）后重播「我在」。
        // wozaiEpoch 世代守卫变异锚：旧 wozaiJob 残跑 finally 无条件写会落在
        // 新一轮 wakeAckActive=true 之后 → 覆盖新一轮信号 → 删世代比对则红。
        // 无自循环（Q2）：每轮都需真人再喊，应用自身音频不含唤醒词。
        val ack = ChainedWakeAck()
        val mgr = newBareManager(ack)
        mgr.startListening(playWakeAck = true)
        assertTrue("首轮 wozai 应在 5s 内开播", ack.firstPlayStarted.await(5, TimeUnit.SECONDS))
        assertEquals(VoiceStateRouter.State.SPEAKING, mgr.state)
        assertTrue(mgr.wakeAckActive.value)

        mgr.startListening(playWakeAck = true) // 唤醒打断唤醒
        assertEquals("cancel 后应同步重进 SPEAKING(wozai)", VoiceStateRouter.State.SPEAKING, mgr.state)
        assertTrue("新一轮唤醒入口应置 wakeAckActive=true", mgr.wakeAckActive.value)
        assertTrue("第二轮 wozai 应在 5s 内开播", ack.secondPlayStarted.await(5, TimeUnit.SECONDS))
        assertEquals(2, ack.playCalls.get())
        assertEquals("首轮被打断须调 stop 停播", 1, ack.stopCalls.get())

        // 旧 wozaiJob 残跑收尾：playBlocking 抛停播异常 → finally（世代守卫的判别点）
        ack.releaseFirstPlay.countDown()
        Thread.sleep(300) // 旧 job finally 落地窗口（cancelDuringWakeAckStopsPlaybackAndIdles 同体例）
        assertEquals("旧 wozai 收尾不得腐蚀新一轮状态", VoiceStateRouter.State.SPEAKING, mgr.state)
        assertTrue(
            "旧 wozaiJob finally 不得复位新一轮 wakeAckActive（删 wozaiEpoch 比对 → 红）",
            mgr.wakeAckActive.value,
        )

        ack.releaseSecondPlay.countDown() // 第二轮自然播完 → LISTENING
        awaitState(mgr, VoiceStateRouter.State.LISTENING)
        assertFalse("wozai 完进 LISTENING 应复位 wakeAckActive", mgr.wakeAckActive.value)
        assertTrue("聆听期 kwsPaused 应为 true", mgr.kwsPaused)
    }
}
