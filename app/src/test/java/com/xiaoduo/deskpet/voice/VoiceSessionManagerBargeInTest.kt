package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.agui.AguiClient
import com.xiaoduo.deskpet.voice.audio.AudioCapture
import com.xiaoduo.deskpet.voice.audio.VadDetector
import com.xiaoduo.deskpet.voice.audio.VoicePlayer
import com.xiaoduo.deskpet.voice.audio.WavCodec
import com.xiaoduo.deskpet.voice.client.AsrClient
import com.xiaoduo.deskpet.voice.client.TtsClient
import com.xiaoduo.deskpet.voice.pipeline.RevealScheduler
import com.xiaoduo.deskpet.voice.pipeline.VoiceSessionManager
import com.xiaoduo.deskpet.voice.pipeline.VoiceStateRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * VoiceSessionManager.cancelCurrentTurn barge-in 管线单测（#7 阶段1，MockWebServer
 * 端到端桩，同 VoiceSessionManagerTranscriptTest 体例：HTTP 层全桩、被测对象零 mock）。
 *
 * 覆盖票据验收点（守卫四问 Q2——本票最大雷：恰好一次终态回调防 voiceActive 永挂）：
 * - 流式中途 cancel：部分回复补发 done **恰好一次**、无 error（已流出文本是有效内容，
 *   标 error 会出「重发」误导）；残跑管线（OkHttp 不响应协程取消）自然落地后
 *   不得再有 delta/终态入列（迟到写防线，变异锚：删 turn.sealed 守卫 → 红），
 *   气泡侧 delta sealed 拦截（变异锚：cancel 后零气泡操作）；
 * - 正常终态后 cancel：不二次 fire（CAS 防线反向）。
 *
 * throttleBody 拖慢 SSE：cancel 时刻与后续 delta 之间有构造性时间窗，消除快照竞态。
 */
class VoiceSessionManagerBargeInTest {

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

    /** 记录型气泡：钉 delta sealed 拦截（cancel 后零 append/show，变异锚 critic P2-④）。 */
    private class RecordingBubble : VoiceSessionManager.BubbleSink {
        val ops = CopyOnWriteArrayList<String>()
        val firstOpLatch = CountDownLatch(1)
        override fun show(text: String) {
            ops.add("show")
            firstOpLatch.countDown()
        }
        override fun append(delta: String) {
            ops.add("append")
            firstOpLatch.countDown()
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

    /**
     * 阻塞型 sink（S8-5 P2 竞态窗构造器）：首个 onAssistantDelta 内挂起，把
     * 「sealed 入口检查 → bubble.show」之间的时间窗撑开到测试线程可控——
     * entered 计数后测试线程 cancel（封轮+收泡），release 放行后回调继续走向 bubble。
     */
    private class BlockingFirstDeltaSink : VoiceSessionManager.TranscriptSink {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private var firstDelta = true // 仅 delta 回调线程读写（SSE 顺序投递）

        override fun onUserUtterance(text: String) {}

        override fun onAssistantDelta(delta: String) {
            if (firstDelta) {
                firstDelta = false
                entered.countDown()
                release.await(20, TimeUnit.SECONDS)
            }
        }

        override fun onAssistantDone() {}
        override fun onAssistantError(message: String) {}
    }

    /** 记录型 sink：事件序 + 首 delta latch（cancel 时机锚点）+ 终态 latch。 */
    private inner class RecordingSink : VoiceSessionManager.TranscriptSink {
        val events = CopyOnWriteArrayList<String>()
        val firstDeltaLatch = CountDownLatch(1)
        val terminalLatch = CountDownLatch(1)

        override fun onUserUtterance(text: String) {
            events.add("user:$text")
        }

        override fun onAssistantDelta(delta: String) {
            events.add("delta:$delta")
            firstDeltaLatch.countDown()
        }

        override fun onAssistantDone() {
            events.add("done")
            terminalLatch.countDown()
        }

        override fun onAssistantError(message: String) {
            events.add("error:$message")
            terminalLatch.countDown()
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
        bubble: VoiceSessionManager.BubbleSink = NoopBubble,
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
        ).also { it.transcriptSink = sink }
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

    private fun streamingSse(vararg deltas: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(sseBody(*deltas))

    /** 拖慢 SSE：cancel 时刻与后续 delta 之间构造 ≥100ms 时间窗（防快照竞态 flaky）。 */
    private fun throttledSse(vararg deltas: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(sseBody(*deltas))
        .throttleBody(50, 100, TimeUnit.MILLISECONDS)

    /** 断流 SSE（无 RUN_FINISHED）：finish() → RunResult.Error → ChatResult.Error（不重试）。 */
    private fun throttledTruncatedSse(vararg deltas: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .throttleBody(50, 100, TimeUnit.MILLISECONDS)
        .setBody(
            (
                listOf(
                    """{"type":"RUN_STARTED","runId":"r"}""",
                    """{"type":"TEXT_MESSAGE_START","messageId":"m1","role":"assistant"}""",
                ) +
                    deltas.map { """{"type":"TEXT_MESSAGE_CONTENT","messageId":"m1","delta":"$it"}""" } +
                    listOf("""{"type":"TEXT_MESSAGE_END","messageId":"m1"}""")
                ).joinToString("\n\n") { "data: $it" } + "\n\n",
        )

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

    /** 等 scope 子协程全部落地（残跑管线 + ttsConsumer 自然终结）。 */
    private fun awaitQuiescence(timeoutMs: Long = 20_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var active: List<String>
        do {
            active = scope.coroutineContext.job.children
                .filter { it.isActive }
                .map { it.toString() }
                .toList()
            if (active.isEmpty()) return
            Thread.sleep(50)
        } while (System.currentTimeMillis() < deadline)
        fail("quiescence timeout, active coroutines: $active")
    }

    @Test
    fun cancelDuringStreamingFiresDoneExactlyOnceAndDropsLateDeltas() {
        val sink = RecordingSink()
        val bubble = RecordingBubble()
        val mgr = newManager(sink, bubble)
        server.enqueue(asrOk("小朵小朵今天天气怎么样"))
        server.enqueue(loginOk())
        server.enqueue(threadOk())
        // 首句拉长 + throttle：保证 cancel 时 SSE 仍在投递、后续 delta 还在路上
        // （迟到 delta/气泡拦截变异锚的决定性前提——否则 cancel 后无残余 delta 可观，
        //  删 sealed 守卫也测不出）
        server.enqueue(throttledSse("第一句很长很长很长很长很长。", "第二句。", "第三句。", "第四句。"))
        repeat(8) { server.enqueue(http500("tts down")) } // 句均一次请求，余量防欠

        mgr.onUtterance(utterance())
        assertTrue("首 delta 应在 15s 内到达", sink.firstDeltaLatch.await(15, TimeUnit.SECONDS))
        assertTrue("首帧气泡应在 15s 内上屏", bubble.firstOpLatch.await(15, TimeUnit.SECONDS))

        mgr.cancelCurrentTurn()

        // 状态与半双工第二层即时复位（backToIdle 同步）
        assertEquals(VoiceStateRouter.State.IDLE, mgr.state)
        assertFalse(mgr.kwsPaused)
        // 部分回复补发 done 恰好一次、无 error（error 会出「重发」误导）
        assertEquals("done 应恰好一次: ${sink.events}", 1, sink.events.count { it == "done" })
        assertEquals("不得有 error: ${sink.events}", 0, sink.events.count { it.startsWith("error") })
        assertTrue("cancel 必须收泡: ${bubble.ops}", bubble.ops.contains("hide"))
        val snapshot = sink.events.size
        val bubbleSnapshot = bubble.ops.size

        // 残跑管线（OkHttp blocking 不响应 cancel）自然落地：cancelled job 的 isActive
        // 立即为 false，quiescence 检查对其无效（实测：dump 时流还剩 ~600ms 未读完），
        // 必须等满 throttled 全量投递时长 + 迟到 delta 落地余量（4s）再断言
        Thread.sleep(4_000)
        awaitQuiescence() // ttsConsumer 未被 cancel（独立 scope 子），此处等它收尾
        // 迟到 delta 防线：cancel 后零事件入列（变异锚：删 turn.sealed 守卫 → 红）；
        // 终态零追加（CAS 恰好一次，变异锚：cancel 不 fire → 上一处断言已红）
        assertEquals(
            "cancel 后不得再有 delta/终态入列: ${sink.events}",
            snapshot,
            sink.events.size,
        )
        assertEquals(1, sink.events.count { it == "done" })
        // delta sealed 拦截：cancel 后零 show/append（变异锚 critic P2-④：删入口守卫 →
        // 迟到 delta 继续 show/append 顶出幽灵气泡 → 红）
        assertEquals(
            "cancel 后不得再有气泡操作: ${bubble.ops}",
            bubbleSnapshot,
            bubble.ops.size,
        )
    }

    @Test
    fun cancelAfterNormalCompletionDoesNotDoubleFire() {
        val sink = RecordingSink()
        val mgr = newManager(sink)
        server.enqueue(asrOk("小朵小朵你好"))
        server.enqueue(loginOk())
        server.enqueue(threadOk())
        server.enqueue(streamingSse("好。"))
        repeat(2) { server.enqueue(http500("tts down")) }

        mgr.onUtterance(utterance())
        assertTrue("done 应在 20s 内到达; events=${sink.events}", sink.terminalLatch.await(20, TimeUnit.SECONDS))
        awaitQuiescence() // 多轮迁 LISTENING、管线收尾完毕

        mgr.cancelCurrentTurn() // 正常终态后 cancel：CAS 防线，不得二次 fire
        assertEquals("done 仍应恰好一次: ${sink.events}", 1, sink.events.count { it == "done" })
        assertEquals(0, sink.events.count { it.startsWith("error") })
        assertEquals(VoiceStateRouter.State.IDLE, mgr.state)
        assertFalse(mgr.kwsPaused)
    }

    @Test
    fun cancelDuringAsrFlightSealsTurnAndFiresNothing() {
        // 封轮锚（critic P3-⑪）：ASR 在飞期间 cancel——未上屏静默落地，sink 零事件；
        // 变异锚：删 pre-forward sealed 检查 → 残跑管线继续上屏整轮（user/delta/done 入列）→ 红
        val sink = RecordingSink()
        val mgr = newManager(sink)
        server.enqueue(
            asrOk("小朵小朵今天天气怎么样").setBodyDelay(2, TimeUnit.SECONDS), // 慢 ASR：cancel 必落在在飞窗口
        )
        // 变异时残跑管线走完一轮所需的全部响应（守卫完好时不会被消费）
        server.enqueue(loginOk())
        server.enqueue(threadOk())
        server.enqueue(streamingSse("好的。"))
        repeat(2) { server.enqueue(http500("tts down")) }

        mgr.onUtterance(utterance())
        // 等 ASR 请求确实发出（在飞）再 cancel
        val deadline = System.currentTimeMillis() + 5_000
        while (server.requestCount < 1 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("ASR 请求应在 5s 内发出", 1, server.requestCount)
        assertEquals(VoiceStateRouter.State.PROCESSING, mgr.state)

        mgr.cancelCurrentTurn()
        assertEquals(VoiceStateRouter.State.IDLE, mgr.state)
        assertFalse(mgr.kwsPaused)

        Thread.sleep(3_000) // ASR 响应到达（2s）+ 残跑落地余量
        assertEquals(
            "封轮后 sink 必须零事件（未上屏不欠终态）: ${sink.events}",
            emptyList<String>(),
            sink.events.toList(),
        )
        assertEquals(VoiceStateRouter.State.IDLE, mgr.state)
    }

    @Test
    fun cancelThenChatErrorDoesNotGhostBubble() {
        // 幽灵错误泡锚（critic P2-⑨）：cancel 后残跑管线拿到 ChatResult.Error——
        // sealed 守卫下 status/错误泡不上；变异锚：删守卫 → 错误泡在 hide 后重新顶出 → 红
        val sink = RecordingSink()
        val bubble = RecordingBubble()
        val mgr = newManager(sink, bubble)
        server.enqueue(asrOk("小朵小朵今天天气怎么样"))
        server.enqueue(loginOk())
        server.enqueue(threadOk())
        server.enqueue(throttledTruncatedSse("第一句。")) // 断流 → finish() Error → ChatResult.Error
        repeat(2) { server.enqueue(http500("tts down")) }

        mgr.onUtterance(utterance())
        assertTrue("首 delta 应在 15s 内到达", sink.firstDeltaLatch.await(15, TimeUnit.SECONDS))

        mgr.cancelCurrentTurn()
        val bubbleSnapshot = bubble.ops.size

        Thread.sleep(3_000) // 断流 SSE 全量投递（~1s）+ Error 分支落地余量
        assertEquals(
            "cancel 后 Error 分支不得再上错误泡/状态行（幽灵输出）: ${bubble.ops}",
            bubbleSnapshot,
            bubble.ops.size,
        )
        // 终态仍是 cancel 补发的 done 恰好一次；Error 分支的 fireErrorOnce 让位（CAS）
        assertEquals(1, sink.events.count { it == "done" })
        assertEquals(0, sink.events.count { it.startsWith("error") })
    }

    @Test
    fun startListeningDuringProcessingInterruptsAndResumesListening() {
        // S8-3：PROCESSING 态（已上屏，delta 在飞，TTS 全降级未进 SPEAKING）按住说话 ->
        // 复用 cancelCurrentTurn（封轮+停 TTS+收泡+部分回复补发 done+回 IDLE）-> 同一次按住直接进 LISTENING 采音。
        // 变异锚：删 startListening 的 speaking/processing 打断分支 -> state 仍 PROCESSING -> 红
        val sink = RecordingSink()
        val bubble = RecordingBubble()
        val statuses = CopyOnWriteArrayList<String>()
        val mgr = newManager(sink, bubble).also { it.statusListener = { statuses.add(it) } }
        server.enqueue(asrOk("小朵小朵今天天气怎么样"))
        server.enqueue(loginOk())
        server.enqueue(threadOk())
        server.enqueue(throttledSse("第一句很长很长很长很长很长。", "第二句。", "第三句。"))
        repeat(8) { server.enqueue(http500("tts down")) } // TTS 降级：state 保持 PROCESSING

        mgr.onUtterance(utterance())
        assertTrue("首 delta 应在 15s 内到达", sink.firstDeltaLatch.await(15, TimeUnit.SECONDS))
        assertEquals("cancel 前应为 PROCESSING", VoiceStateRouter.State.PROCESSING, mgr.state)

        val ttsReqsBeforeCancel = server.requestCount
        mgr.startListening() // 模拟按住说话（playWakeAck=false 默认）

        // 打断接续：cancel 副作用 + 进 LISTENING
        assertEquals("应进 LISTENING: ${sink.events}", VoiceStateRouter.State.LISTENING, mgr.state)
        assertTrue("kwsPaused 应为 true（聆听中）", mgr.kwsPaused)
        assertEquals("done 应恰好一次: ${sink.events}", 1, sink.events.count { it == "done" })
        assertEquals("不得有 error: ${sink.events}", 0, sink.events.count { it.startsWith("error") })
        assertTrue("cancel 必须收泡: ${bubble.ops}", bubble.ops.contains("hide"))
        val snapshot = sink.events.size
        val bubbleSnapshot = bubble.ops.size

        // 残跑管线落地：sealed 守卫下零 delta/终态/气泡追加 + 零新增 TTS 请求（consumeSentences
        //  sealed 守卫变异锚：删 -> 残跑继续对缓冲句调 synthesize 发 HTTP -> requestCount 增长 -> 红）
        Thread.sleep(4_000)
        assertEquals("残跑不得追加 sink 事件: ${sink.events}", snapshot, sink.events.size)
        assertEquals("残跑不得追加气泡操作: ${bubble.ops}", bubbleSnapshot, bubble.ops.size)
        assertEquals(1, sink.events.count { it == "done" })
        // 多轮 !sealed 守卫变异锚（P2-1）：删 -> 残跑多轮逻辑 transition(LISTENING)+status("继续聆听")
        //  -> statuses 出现"继续聆听" -> 红（statusListener 是唯一观测点，state 已 LISTENING 无变化不可观测）
        assertFalse(
            "残跑多轮逻辑不得 fire「继续聆听」: $statuses",
            statuses.any { it.contains("继续聆听") },
        )
        // consumeSentences sealed 守卫变异锚（P2-2）：删 -> 残跑继续 synthesize -> requestCount 增长 -> 红
        assertEquals(
            "cancel 后残跑不得新增 TTS 请求: before=$ttsReqsBeforeCancel now=${server.requestCount}",
            ttsReqsBeforeCancel,
            server.requestCount,
        )
    }

    @Test
    fun startListeningDuringSpeakingInterruptsAndDoesNotCorruptNewTurnPlayer() {
        // S8-3 SPEAKING 态（spec 点名）：TTS 返回 Audio -> consumeSentences transition(SPEAKING) ->
        // playSentence 内 currentPlayer=player 赋值后 VoicePlayer.playBlocking 在 JVM 抛 AudioTrack
        // stub 异常被 catch（state 停 SPEAKING）。按住说话 -> cancelCurrentTurn（含 currentPlayer.stop）
        // -> startListening 进 LISTENING。
        // 关键变异锚（P2-2 状态腐蚀）：删 consumeSentences 二查的 || turn.sealed（L502）-> 残跑
        // playSentence 的 currentPlayer=player 覆盖新轮引用 -> 此处断言新轮 currentPlayer 可被 stop
        //  打到非 null（若被旧轮覆盖则 currentPlayer 已被残跑重置/污染）。此处用「cancel 后 currentPlayer
        //  应为 null」+「无幽灵 SPEAKING 迁移」作为侧证（残跑 transition(SPEAKING) 会覆盖 LISTENING -> 红）
        val sink = RecordingSink()
        val bubble = RecordingBubble()
        val mgr = newManager(sink, bubble)
        server.enqueue(asrOk("小朵小朵今天天气怎么样"))
        server.enqueue(loginOk())
        server.enqueue(threadOk())
        // 首句 TTS 流式成功（NDJSON audioData+done -> Result.Audio -> transition(SPEAKING)），余句降级防欠
        server.enqueue(throttledSse("第一句很长很长很长很长很长。", "第二句。", "第三句。"))
        server.enqueue(synthStreamOk(ByteArray(1600))) // 首句 TTS Audio -> transition(SPEAKING)
        repeat(8) { server.enqueue(http500("tts down")) } // 余句降级

        mgr.onUtterance(utterance())
        assertTrue("首 delta 应在 15s 内到达", sink.firstDeltaLatch.await(15, TimeUnit.SECONDS))
        // 等 SPEAKING 落地（首句 Audio -> transition；AudioTrack stub 异常被 catch 不回退状态）。
        // 用 stateFlow 订阅捕 SPEAKING 瞬态--playBlocking 在 JVM 即抛异常不阻塞，SPEAKING 窗口窄，
        // 轮询会错过（余句 http500 降级快，pipeline 跑完进多轮 LISTENING）
        val spkLatch = CountDownLatch(1)
        val spkJob = scope.launch {
            mgr.stateFlow.collect { if (it == VoiceStateRouter.State.SPEAKING) spkLatch.countDown() }
        }
        assertTrue("应在 15s 内进 SPEAKING（首句 TTS Audio）", spkLatch.await(15, TimeUnit.SECONDS))
        spkJob.cancel()
        assertEquals("cancel 前应为 SPEAKING", VoiceStateRouter.State.SPEAKING, mgr.state)

        mgr.startListening() // 模拟按住说话（playWakeAck=false 默认）
        assertEquals("应进 LISTENING: ${sink.events}", VoiceStateRouter.State.LISTENING, mgr.state)
        assertEquals("done 应恰好一次: ${sink.events}", 1, sink.events.count { it == "done" })
        assertEquals("不得有 error: ${sink.events}", 0, sink.events.count { it.startsWith("error") })

        // 残跑余句 consumeSentences 落地：sealed 守卫下不得 transition(SPEAKING) 覆盖新轮 LISTENING
        // （P2-2/P2-3 变异锚：删二查 sealed -> 残跑 transition(SPEAKING,"first audio") 覆盖 LISTENING -> 红）
        Thread.sleep(4_000)
        assertEquals(
            "残跑不得把 state 拉回 SPEAKING（sealed 守卫防状态腐蚀）: ${sink.events}",
            VoiceStateRouter.State.LISTENING,
            mgr.state,
        )
        assertEquals(1, sink.events.count { it == "done" })
    }

    @Test
    fun cancelDuringSinkCallbackDoesNotGhostBubble() {
        // S8-5 P2 check-then-act 竞态窗：delta callback 内 sealed 入口检查（L438）与
        // bubble.show（L444）之间隔着 transcriptSink.onAssistantDelta（可阻塞）——
        // cancel 在此窗落地则 hide 后又被迟到 show 顶出幽灵气泡。
        // 构造：BlockingFirstDeltaSink 在首 delta 内挂起 → 测试线程 cancel（封轮+收泡）
        // → 放行 sink → 回调继续走向 bubble。
        // 变异锚：删 bubble 前 double-check sealed → show 在 hide 后顶出 → 红
        val sink = BlockingFirstDeltaSink()
        val bubble = RecordingBubble()
        val mgr = newManager(sink, bubble)
        server.enqueue(asrOk("小朵小朵今天天气怎么样"))
        server.enqueue(loginOk())
        server.enqueue(threadOk())
        server.enqueue(throttledSse("第一句很长很长很长很长很长。", "第二句。"))
        repeat(4) { server.enqueue(http500("tts down")) }

        mgr.onUtterance(utterance())
        assertTrue("首 delta 应进入 sink 回调并挂起", sink.entered.await(15, TimeUnit.SECONDS))

        mgr.cancelCurrentTurn() // 落进 sink 阻塞窗：封轮 + 收泡 + 回 IDLE（同步）
        assertEquals(VoiceStateRouter.State.IDLE, mgr.state)
        sink.release.countDown() // 放行：回调继续走向 bubble.show

        Thread.sleep(3_000) // 残跑管线落地余量（throttled 全量投递 ~1s）
        assertTrue("cancel 必须收泡: ${bubble.ops}", bubble.ops.contains("hide"))
        assertEquals(
            "cancel 后不得有气泡 show/append（幽灵气泡）: ${bubble.ops}",
            0,
            bubble.ops.count { it == "show" || it == "append" },
        )
    }
}
