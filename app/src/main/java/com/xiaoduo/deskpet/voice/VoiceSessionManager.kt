package com.xiaoduo.deskpet.voice

import android.content.Context
import com.xiaoduo.deskpet.PetOverlayService
import com.xiaoduo.deskpet.agui.AguiClient
import com.xiaoduo.deskpet.cloud.CloudClientFactory
import com.xiaoduo.deskpet.cloud.Message
import com.xiaoduo.deskpet.cloud.OpenAIAsrClient
import com.xiaoduo.deskpet.cloud.OpenAIChatClient
import com.xiaoduo.deskpet.cloud.OpenAITtsClient
import com.xiaoduo.deskpet.net.NetConfig
import com.xiaoduo.deskpet.pet.PetFrameTable
import com.xiaoduo.deskpet.util.PetLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语音会话编排（半双工四态，知识包 §3.5 + 对账表状态机节）：
 *
 * idle / listening / processing / speaking；麦块经 [VoiceStateRouter] 分发
 * （idle→KWS 挂点 / listening→VAD / 其余丢弃）；[kwsPaused] 公开 var = S5 KWS pause flag 挂点；
 * [kwsSink] 公开 var = S5 KWS 块订阅挂点（单条采麦流复用）。
 *
 * 管线：VAD 判停 → ASR → 唤醒词前缀剥除 → AG-UI（增量单条）→ 逐字符分句 →
 * 逐句 TTS（Channel 4 背压）→ 阻塞播放。
 * 气泡文字：直接消费 AG-UI delta 流式上屏（与 App 聊天页同节奏，S8-5 修订 KD13——撤
 * 句级 PCM 时序 reveal）；TTS 播放与文字解耦，单句失败降级仅跳过本句播放，文字照常流式。
 * 多轮：TTS 播完回 LISTENING（重启 VAD + 30s 超时），超时回 IDLE。
 */
class VoiceSessionManager(
    private val capture: AudioCapture,
    private val asr: AsrClient,
    private val tts: TtsClient,
    private val aguiFactory: () -> AguiClient,
    private val appContext: Context? = null,
    private val bubble: BubbleSink,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = { msg -> PetLogger.i(TAG, msg) },
    private val wakeAck: WakeAck? = null,
) {

    /** ASR 模式分支：云端按配置厂商分发（pcm 包 WAV），平台走 AsrClient。 */
    private fun transcribeAny(pcm: ByteArray): AsrClient.Result {
        val ctx = appContext ?: return asr.transcribe(pcm)
        if (!NetConfig.asrIsCloud(ctx)) return asr.transcribe(pcm)
        val wav = WavCodec.pcmToWav(pcm, VadDetector.SAMPLE_RATE)
        return CloudClientFactory.asrTranscribe(ctx, wav)
    }

    /** TTS 模式分支：云端按配置厂商分发（返裸 PCM），平台走 TtsClient。 */
    private fun synthesizeAny(text: String): TtsClient.Result {
        val ctx = appContext ?: return tts.synthesize(text)
        if (!NetConfig.ttsIsCloud(ctx)) return tts.synthesize(text)
        return CloudClientFactory.ttsSynthesize(ctx, text)
    }

    /** 语音 LLM 模式分支：云端走 OpenAI 兼容（无状态），平台走 AguiClient（线程记忆）。 */
    private fun voiceChat(stripped: String, onDelta: (String) -> Unit): AguiClient.ChatResult {
        val ctx = appContext ?: return aguiFactory().chat(stripped, onDelta = onDelta)
        if (!NetConfig.llmIsCloud(ctx)) return aguiFactory().chat(stripped, onDelta = onDelta)
        return when (val r = CloudClientFactory.chat(ctx).chat(listOf(Message("user", stripped)), null, onDelta)) {
            is OpenAIChatClient.Result.Success -> AguiClient.ChatResult.Success(r.text, r.firstTokenLatencyMs, 1)
            is OpenAIChatClient.Result.Error -> AguiClient.ChatResult.Error(r.message)
        }
    }

    /** 气泡出口抽象（Android 侧适配 PetOverlayService.instance；show_bubble 开关在 S3 API 内）。 */
    interface BubbleSink {
        fun show(text: String)
        fun append(delta: String)
        fun hide()

        /** S5：延时收泡（语义见 [BubbleHidePolicy] 类注释——播完 12s 收，回 IDLE 才触发）。 */
        fun hideLater(delayMs: Long)
    }

    /**
     * 会话历史出口抽象（语音轮流式上屏，Android 侧由 ChatViewModel 适配）：
     * - [onUserUtterance]：ASR 剥唤醒词前缀非空后、AG-UI 调用**之前**触发（user 立即上屏）；
     * - [onAssistantDelta]：AG-UI 每个原始 delta 实时触发（未过 ThinkCleaner，清洗归适配侧）；
     * - [onAssistantDone]：AG-UI RUN_FINISHED 成功后触发（assistant 标 done）；
     * - [onAssistantError]：AG-UI 失败后触发（user 已上屏保留，assistant 标 error）。
     * ASR 失败 / 剥前缀后为空零触发（user 不上屏，气泡错误提示照旧）。
     */
    interface TranscriptSink {
        /** ASR 剥唤醒词前缀后的最终发送文本（AG-UI 调用之前触发）。 */
        fun onUserUtterance(text: String)

        /** AG-UI 流式原始 delta（逐次实时，未清洗）。 */
        fun onAssistantDelta(delta: String)

        /** AG-UI 成功（RUN_FINISHED）后触发。 */
        fun onAssistantDone()

        /** AG-UI 失败后触发，message 为透传的真实原因。 */
        fun onAssistantError(message: String)
    }

    /**
     * #9 唤醒应答「我在」抽象（顺序式，ADR 0003）：Android 侧由 VoiceClientFactory
     * 装配（assets/wozai.wav 剥头缓存 PCM + VoicePlayer 委托）；null = 降级，
     * 唤醒直接进 LISTENING（wozai 缺失/损坏不致命）。
     */
    interface WakeAck {
        /**
         * 同步阻塞播放（调用方切 IO 协程）；[stop] 置位后应尽快抛异常返回。
         * [isCancelled]：注册后复查回调（critic P1-①）——实现侧应先注册播放器引用
         * 再调它，为真立即停；这是「cancel 在调用方 pre-check 之后、注册之前到达」
         * 窗口的唯一兜底（该窗口内 stop() 必打到 null player）。
         */
        fun playBlocking(isCancelled: () -> Boolean)

        /** 打断在途播放（幂等；未在播 no-op）。 */
        fun stop()
    }

    /**
     * 单轮终态守卫（barge-in #7 阶段1）：每轮独立实例——cancel 后旧管线 blocking
     * 残跑（OkHttp 不响应协程取消），若共享一份标志，新一轮 reset 会放旧轮迟到
     * delta/终态写进新轮（跨轮串写）。旧轮回调闭包持自己这份，新轮换实例即自然屏蔽。
     */
    private class TurnFlags {
        /** 本轮 user 已上屏（终态补发前提：上屏后必须恰好一次 done|error，防 voiceActive 永挂）。 */
        @Volatile
        var utteranceForwarded = false

        /**
         * cancel 封轮（critic P2-③）：无论 user 是否已上屏都置位——ASR 在飞期间 cancel
         * 若只在 forwarded 时置 terminalFired，残跑管线会继续上屏 + 流出整轮幽灵消息；
         * 封轮后残跑管线的上屏/分句/多轮迁移全部禁止（已上屏才补发 done，未上屏静默落地）。
         */
        @Volatile
        var sealed = false

        /** 终态恰好一次防线（CAS：cancel 与正常终态竞态下也只 fire 一次）。 */
        val terminalFired = AtomicBoolean(false)
    }

    /** 当前轮守卫（runPipeline 开始即换实例；cancel 读当前轮补发终态）。 */
    @Volatile
    private var currentTurn: TurnFlags? = null

    /** #9「我在」播放 job（cancel 时先 cancel 置 isActive=false，job 体断点检查不再迁 LISTENING）。 */
    private var wozaiJob: Job? = null

    /** 在途 TTS 播放器（barge-in 停播挂点；playSentence 注册/注销）。 */
    @Volatile
    private var currentPlayer: VoicePlayer? = null

    val router = VoiceStateRouter()

    /** S5 收泡判定（播完 12s 收 / 回 IDLE 才收 / 多轮回 LISTENING 不收）。 */
    private val bubbleHidePolicy = BubbleHidePolicy()

    @Volatile
    var state: VoiceStateRouter.State = VoiceStateRouter.State.IDLE
        private set

    /**
     * S7-d 全屏语音模式：四态多播通道（全屏 PetView 相位订阅用）。
     * 唯一写入点 = [transition]（与 [state] 同步写，单播 statusListener 不动）。
     */
    private val _stateFlow = MutableStateFlow(VoiceStateRouter.State.IDLE)
    val stateFlow: StateFlow<VoiceStateRouter.State> = _stateFlow

    /**
     * S8-7 唤醒即时视觉反馈信号：唤醒路径（playWakeAck=true）入口置 true，wozai 播完
     * 进 LISTENING 置 false。悬浮窗与全屏两模式订阅此信号，在 SPEAKING(wozai) 期间
     * 播 JUMPING 一次->WAITING（跳过通用 TALKING 映射），表达唤醒的即时明确动作。
     * 唤醒路径外（按住说话/TTS 回复）恒 false，视觉走通用四态映射不变。
     */
    private val _wakeAckActive = MutableStateFlow(false)
    val wakeAckActive: StateFlow<Boolean> = _wakeAckActive

    /** S5 KWS pause flag 挂点（对话期间 true，暂停 KWS decode 防 TTS 自声误触发）。 */
    var kwsPaused: Boolean
        get() = router.kwsPaused
        set(v) {
            router.kwsPaused = v
        }

    /** S5 KWS 块订阅挂点：IDLE 态麦块回调（单条采麦流扇出）。 */
    @Volatile
    var kwsSink: ((ByteArray) -> Unit)? = null

    /** UI 状态行回调（设置页语音自检显示进度；Compose snapshot state 跨线程写安全）。 */
    @Volatile
    var statusListener: ((String) -> Unit)? = null

    /** P1 会话历史出口（挂点同 [kwsSink]/[statusListener] 模式；null = 不记录，行为与接入前一致）。 */
    @Volatile
    var transcriptSink: TranscriptSink? = null

    private val vad = VadDetector()
    private var collectJob: Job? = null
    private var pipelineJob: Job? = null

    /**
     * 按住说话按下 / 唤醒后进入聆听（仅 IDLE 可入）。
     * [playWakeAck]=true（仅 KWS 唤醒路径）时走 #9 顺序式「我在」：
     * SPEAKING 播预录应答（speaking 态天然丢麦块）→ 播完进 LISTENING；
     * 按住说话/自检路径不传参不播（手指即即时反馈，播了反而延迟采麦）。
     */
    fun startListening(playWakeAck: Boolean = false) {
        // S8-3：speaking/processing 态按住说话 = 打断并接续新一轮。复用 cancelCurrentTurn
        // （停 TTS+停气泡+封轮+部分回复补发 done+回 IDLE），再走正常 IDLE->LISTENING 采音。
        // 仅按住路径（playWakeAck=false）；唤醒路径不会在对话态发生（kwsPaused 期间 KWS
        // 不解码，物理不可达）。LISTENING 态按住：已在采音，下方 if(state!=IDLE)return no-op。
        if (!playWakeAck && (state == VoiceStateRouter.State.SPEAKING || state == VoiceStateRouter.State.PROCESSING)) {
            cancelCurrentTurn()
        }
        if (state != VoiceStateRouter.State.IDLE) return
        ensureCollectLoop()
        kwsPaused = true // 半双工第二层：对话期暂停 KWS
        val ack = if (playWakeAck) wakeAck else null
        if (ack == null) {
            // 默认路径 + wozai 缺失/损坏降级：直接进聆听
            vad.start()
            transition(VoiceStateRouter.State.LISTENING, "VAD start, timeout 30s")
            status("聆听中…（30s 无语音自动退出）")
            return
        }
        // 顺序式「我在」：SPEAKING 期间麦块走 DROP + kwsPaused，重复唤醒物理不可达
        playAckThenListen()
    }

    /**
     * 顺序式「我在」→ LISTENING（KWS 唤醒路径与纯唤醒词重听路径共用）。
     * 前置：kwsPaused 已置 true（对话期）。SPEAKING(wozai) 播预录应答 → 播完进 LISTENING。
     * S8-7：入口置 wakeAckActive 让 transition(SPEAKING) 跳过通用 TALKING 映射，
     * 由 playPetOnceThen(JUMPING→WAITING) 接管视觉（悬浮窗）；wozai 完进 LISTENING 置 false。
     */
    private fun playAckThenListen() {
        _wakeAckActive.value = true
        PetOverlayService.playPetOnceThen(PetFrameTable.JUMPING, PetFrameTable.WAITING)
        transition(VoiceStateRouter.State.SPEAKING, "wake-ack wozai")
        status("我在")
        wozaiJob = scope.launch(Dispatchers.IO) {
            // cancel 先于开播到达（job.cancel 先行于 wakeAck.stop）：直接不播
            if (!isActive) return@launch
            try {
                // isCancelled 透传给播放层注册后复查：pre-check→注册窗口内到达的
                // cancel 由播放层补枪（VoicePlayer stopped 持久，首写即抛，零出声）
                wakeAck?.playBlocking { !isActive }
            } catch (e: Exception) {
                // 播不出「我在」不该吞整轮对话——降级直接进聆听
                logger("wake-ack play failed, fallback to listening: ${e.message}")
            } finally {
                // S8-7：无条件复位 wakeAckActive（critic P2）--wozai 完时 state 已被
                // onUtterance 抢跑切 PROCESSING 则原 if(state==SPEAKING) 跳过复位致残留，
                // 下次 TTS SPEAKING 被误 gate（不切 TALKING）+ 全屏重复播 jumping。
                // cancel 路径 cancelCurrentTurn 已置 false，此处重复写无害（幂等）
                _wakeAckActive.value = false
            }
            // cancel 双防线：cancelCurrentTurn 先 wozaiJob.cancel()（isActive=false 先行可见）
            // 再 wakeAck.stop() 打断 playBlocking；backToIdle 后 state 也非 SPEAKING。
            // 留痕（critic P3-⑥）：双查是 check-then-act，cancel 与 transition 间有微秒窗——
            // 最坏拦不住一次已落地的 LISTENING 迁移（30s 无语音超时自愈回 IDLE），无状态泄漏
            if (isActive && state == VoiceStateRouter.State.SPEAKING) {
                vad.start()
                transition(VoiceStateRouter.State.LISTENING, "wozai done, VAD start")
                status("聆听中…（30s 无语音自动退出）")
            }
        }
    }

    /**
     * barge-in（#7 阶段1 键盘打断）：终止当前轮一切输出，回 IDLE。
     * - LISTENING：停 VAD 回 IDLE（同 manual release，走 backToIdle 复位 kwsPaused）
     * - PROCESSING/SPEAKING：停「我在」/停 TTS/停管线 + 部分回复补发 done（**非 error**——
     *   被打断轮已流出文本是有效内容，error 会出「重发」误导重复投递）+ 收泡 + 回 IDLE
     * - IDLE：no-op
     * 顺序：先 cancel job（isActive 置位，job 体断点检查不再做状态迁移）再停播放器
     * （VoicePlayer stop 的 @Volatile 写先行，playBlocking 打断返回后 job 体查
     * isActive=false 直接退出）；backToIdle 最后，kwsPaused 只准经它复位。
     */
    fun cancelCurrentTurn() {
        when (state) {
            VoiceStateRouter.State.IDLE -> Unit
            VoiceStateRouter.State.LISTENING -> {
                vad.stop()
                backToIdle("cancel listening")
            }
            VoiceStateRouter.State.PROCESSING, VoiceStateRouter.State.SPEAKING -> {
                // 封轮上提到所有 stop 之前（critic R3 P3 处方）：playSentence 补枪读
                // sealed——旗标必须在第 1 步翻转（对齐 wozai 读 isActive 的时序），
                // 否则「stop 之后注册、backToIdle 置 IDLE 之前读 state」亚窗漏防
                currentTurn?.let { turn ->
                    turn.sealed = true
                    if (turn.utteranceForwarded) fireDoneOnce(turn)
                }
                wozaiJob?.cancel()
                wakeAck?.stop()
                currentPlayer?.stop()
                pipelineJob?.cancel()
                bubble.hide()
                _wakeAckActive.value = false // S8-7：唤醒被打断复位，防下次 SPEAKING 漏 TALKING
                backToIdle("barge-in cancel")
            }
        }
    }

    /** 部分回复/正常成功终态：恰好一次（user 未上屏不补发——上了屏才欠终态）。 */
    private fun fireDoneOnce(turn: TurnFlags) {
        if (turn.utteranceForwarded && turn.terminalFired.compareAndSet(false, true)) {
            transcriptSink?.onAssistantDone()
        }
    }

    /** 失败终态：恰好一次（同 CAS 防线）。 */
    private fun fireErrorOnce(turn: TurnFlags, message: String) {
        if (turn.utteranceForwarded && turn.terminalFired.compareAndSet(false, true)) {
            transcriptSink?.onAssistantError(message)
        }
    }

    /** 按住说话松开：说话中则喂静音块触发判停产出当前句；未说话直接回 IDLE。 */
    fun stopListening() {
        if (state != VoiceStateRouter.State.LISTENING) return
        if (vad.isSpeaking) {
            for (i in 0 until VadDetector.SILENCE_END_TICKS) {
                val ev = vad.feed(ByteArray(VadDetector.BLOCK_BYTES))
                if (ev is VadDetector.Event.Utterance) {
                    onUtterance(ev)
                    return
                }
            }
        }
        vad.stop()
        val lvl = vad.currentMaxLevel()
        logger("manual release, no utterance, vad maxLevel=%.4f (≈0.0001 未馈入/系统层, 0.01-0.05 阈值, >0.05 VAD逻辑)".format(lvl))
        backToIdle("manual release, no utterance") // critic-G P2：必须走 backToIdle 复位 kwsPaused，否则 S5 唤醒词永久失效
        status("已结束（未检出有效语音）")
    }

    // ---------------- 麦块分发 ----------------

    /**
     * S5：KWS 常开时由前台服务调用——IDLE 态启动采麦循环，块经 router 进 [kwsSink]。
     * 无 KWS 订阅时块照常被丢（等价现状），仅服务已接 sink 才有实际消费。
     */
    fun startIdleCapture() {
        idleCaptureWanted = true
        if (state != VoiceStateRouter.State.IDLE) return
        ensureCollectLoop()
    }

    /**
     * S5：KWS 服务停止时释放 IDLE 采麦。
     * 意愿登记制（审计 P1 修复）：对话进行中只撤意愿不动采集（采集归对话管线），
     * 回 IDLE 由 [transition] 统一按意愿 teardown——旧实现在 state != IDLE 时直接
     * return，服务 onDestroy 先 kwsSink=null 再 stop，对话结束回 IDLE 后 collectJob
     * 挂活收块、IDLE 路由到 null sink 全丢 = 麦克风孤儿空采（绿点常亮无消费者）。
     * 必须连 collectJob 一起收：只停 capture 会让 collectJob 挂活在空 flow 上，
     * 之后 ensureCollectLoop 见 job active 不再重启 capture，采麦静默坏死。
     */
    fun stopIdleCapture() {
        idleCaptureWanted = false
        if (state != VoiceStateRouter.State.IDLE) {
            logger("idle capture stop deferred (conversation owns capture), teardown on IDLE")
            return
        }
        teardownIdleCapture()
    }

    /** KWS 常开采麦意愿（stop 对话中只撤意愿，回 IDLE 统一执行的裁决源）。 */
    @Volatile
    private var idleCaptureWanted = false

    /** 测试缝：采麦消费循环是否活着（孤儿空采回归断言用）。 */
    internal val collectLoopActive: Boolean get() = collectJob?.isActive == true

    private fun teardownIdleCapture() {
        collectJob?.cancel()
        collectJob = null
        capture.stop()
    }

    /** 校准/诊断用：当前采麦流块广播（无论是否在跑都可订阅；未跑则无块）。 */
    val captureBlocks: kotlinx.coroutines.flow.SharedFlow<ByteArray> get() = capture.blocks

    /** 校准用：采麦是否已在跑（KWS 常开期间为 true，校准复用同一条流防双开抢麦）。 */
    val captureRunning: Boolean get() = capture.isRunning

    private fun ensureCollectLoop() {
        if (collectJob?.isActive == true) return
        capture.start(scope)
        collectJob = scope.launch(Dispatchers.Default) {
            capture.blocks.collect { block -> routeBlock(block) }
        }
    }

    private fun routeBlock(block: ByteArray) {
        when (router.route(state)) {
            VoiceStateRouter.Sink.KWS -> kwsSink?.invoke(block) // S5 挂点，当前无订阅即丢
            VoiceStateRouter.Sink.DROP -> Unit // processing/speaking 丢弃（半双工第一层）
            VoiceStateRouter.Sink.VAD -> when (val ev = vad.feed(block)) {
                is VadDetector.Event.Utterance -> {
                    logger("VAD utterance, ${ev.pcm.size} samples, maxLevel=%.4f".format(ev.maxLevel))
                    onUtterance(ev)
                }
                is VadDetector.Event.Timeout -> {
                    // 一值定层（坑 #19）：maxLevel≈0.0001 系统层（权限/麦），0.01-0.05 阈值问题
                    logger("VAD listen timeout, maxLevel=%.4f".format(ev.maxLevel))
                    transition(VoiceStateRouter.State.IDLE, "listen timeout")
                    status("30s 未说话，回 idle（maxLevel=%.4f）".format(ev.maxLevel))
                    kwsPaused = false
                }
                null -> Unit
            }
        }
    }

    // ---------------- 对话管线 ----------------

    // internal 而非 private：JVM 管线单测直接注入 VAD utterance 事件驱动管线
    // （绕开 AudioRecord，纯逻辑可测；与 VadDetectorTest 直造 Event 同策）
    internal fun onUtterance(ev: VadDetector.Event.Utterance) {
        if (pipelineJob?.isActive == true) return
        transition(VoiceStateRouter.State.PROCESSING, "asr+llm")
        status("识别中…")
        val pcm = shortsToBytes(ev.pcm)
        pipelineJob = scope.launch(Dispatchers.IO) { runPipeline(pcm) }
    }

    private suspend fun runPipeline(pcm: ByteArray) {
        // 每轮换守卫实例：cancel 后旧管线 blocking 残跑时，其回调闭包持旧实例，
        // 新一轮换实例后旧轮迟到写（delta/终态/多轮迁移）全部被屏蔽
        val turn = TurnFlags()
        currentTurn = turn
        val asrStart = System.currentTimeMillis()
        // 1. ASR（VAD 截尾后干净语音，16k PCM 包 WAV 头）
        val userText = when (val r = transcribeAny(pcm)) {
            is AsrClient.Result.Error -> {
                logger("pipeline abort at ASR: ${r.message}")
                // 封轮（cancel 先于 ASR 返回）：静默落地，错误泡/状态行都属幽灵输出
                if (turn.sealed || currentTurn !== turn) return
                status("ASR 失败：${r.message}")
                bubbleHidePolicy.onBubbleShown() // 错误泡同走 12s 自动收（critic ②）
                bubble.show("没听清：${r.message}")
                backToIdle("asr failed")
                return
            }
            is AsrClient.Result.Success -> r.text
        }
        logger("ASR text: $userText")
        // 2. 唤醒词前缀兜底剥除；纯标点/空前缀不送对话（坑 #21）
        val stripped = VoiceStateRouter.stripWakePrefix(userText)
        if (stripped.isEmpty() || stripped.none { it.isLetterOrDigit() }) {
            // strip-empty 死路修复（2026-08-05 真机 19:09 四轮实证）：用户只说了一遍唤醒词
            // （前缀被剥离后只剩标点/空——听感异常诱发的重复喊）。旧行为静默回 IDLE，用户
            // 感知=唤不醒；改为重播「我在」继续聆听（等效"再叫一遍再应一遍"），30s 无语音
            // 仍超时自愈。守卫四问：sealed/旧轮残跑不得重播（cancel 过境后幽灵「我在」）；
            // wakeAck=null 降级维持旧 IDLE 路径；未剥过前缀的垃圾文本（纯标点）同样维持。
            val onlyWakeWord = stripped.none { it.isLetterOrDigit() } && userText.trim() != stripped
            if (onlyWakeWord && !turn.sealed && currentTurn === turn) {
                // 2026-08-10 根因修复：纯唤醒词改回 IDLE（让 KWS 接管），不再 playAckThenListen。
                // 旧行为重播我在继续聆听 → 用户在聆听期反复喊唤醒词时陷「ASR→strip空→重播」
                // 死循环（15:23-24 日志 8 轮实证），永不回 IDLE，KWS 无法接管 → 感知"唤不醒"。
                // 回 IDLE 后 KWS 重接管，用户再喊即正常唤醒（有我在反馈），不依赖此处的应答音。
                logger("wake-prefix strip 后为空（纯唤醒词），回 IDLE 让 KWS 接管: $userText")
                backToIdle("wake-only utterance")
            } else {
                logger("wake-prefix strip 后无有效文本，不送对话: $userText")
                status("未检出有效指令")
                backToIdle("empty after strip")
            }
            return
        }
        logger("ASR->LLM send latency=${System.currentTimeMillis() - asrStart}ms")
        status("小朵思考中…")
        // 封轮检查（critic P2-③）：cancel 在 ASR 在飞期间封轮后，残跑管线不得再上屏——
        // user 一旦入列就欠一个终态回调（voiceActive 永挂同源），此处落地即返
        if (turn.sealed || currentTurn !== turn) {
            logger("turn sealed before utterance forward, abort silently")
            return
        }
        // 流式上屏：user 立即入列（AG-UI 调用之前；失败轮上面已 return 零触发）
        transcriptSink?.onUserUtterance(stripped)
        turn.utteranceForwarded = true
        // check→forward 窗口封轮补枪（critic P2-③ 竞态闭环）：cancel 恰在上屏瞬间封轮时
        // 终态补发职责落回本路径（fireDoneOnce CAS 保证恰好一次，后到者让位）
        if (turn.sealed) {
            fireDoneOnce(turn)
            return
        }

        // 异常兜底：user 已上屏后，任何非取消异常逃逸（bubble 层 RuntimeException / chat 内残余
        // 非 IOException 等）都视同失败轮——补发 onAssistantError 让 VM 标 error 并复位守卫窗口，
        // 防终态回调被跳过导致 voiceActive 永久悬挂。terminalFired CAS 防正常终态后再异常双触发。
        // backlog #10：sentences 提升到 try 外——chat 内异常逃逸时 catch 分支必须 close，
        // 否则 ttsConsumer 悬挂空 channel，每异常轮泄漏一个挂起协程
        val sentences = Channel<String>(4) // 句队列：容量 4 背压（坑 #5）
        try {
            // 3. AG-UI 流式 → 逐字符分句 → 句队列
            val splitter = SentenceSplitter()
            val ttsConsumer = scope.async(Dispatchers.IO) { consumeSentences(sentences, turn) }
            // S8-5：气泡文字改 delta 流式直出（撤句级 PCM 时序 reveal）。
            // bubbleStarted 记首轮 show 认领 round 令牌（OverlayBubbleSink round 机制），后续 append 续打。
            var bubbleStarted = false
            val chat = voiceChat(stripped) { delta ->
                // barge-in 迟到 delta 防线：封轮（被打断）后残跑管线的 delta 全丢——
                // 否则 VM 侧 done 后又收到 delta 会再造占位 assistant，幽灵消息且永无终态。
                // S8-5 回归守卫：此入口统一拦截 transcriptSink 与 bubble 两路同源 delta，
                // cancel 后迟到 delta 不再上屏/出泡（对等屏蔽闭环——删此 → 残文本继续上屏）
                if (turn.sealed) return@voiceChat
                transcriptSink?.onAssistantDelta(delta) // App 聊天页逐 delta 实时上屏（原文，清洗归适配侧）
                // S8-5 回归守卫：sink 回调可阻塞，cancel 可在入口 sealed 检查与此处之间落地——
                // bubble 操作前必须 double-check，否则 hide 后被迟到 show/append 顶出幽灵气泡
                // （删此 → BargeInTest.cancelDuringSinkCallbackDoesNotGhostBubble → 红）
                if (turn.sealed) return@voiceChat
                // S8-5：气泡直接消费 delta 流式上屏（与 App 聊天页同节奏），不再等句级 TTS 时序
                if (bubbleStarted) {
                    bubble.append(delta)
                } else {
                    bubble.show(delta) // 首帧认领本轮 bubble round 令牌（后续 append 带同 round）
                    bubbleHidePolicy.onBubbleShown() // 本轮弹过气泡，回 IDLE 时延时收
                    bubbleStarted = true
                }
                for (c in delta) {
                    for (s in splitter.feed(c)) {
                        runBlocking { sentences.send(s) }
                    }
                }
            }
            splitter.flush()?.let { s -> sentences.send(s) } // 残留收尾
            sentences.close()
            ttsConsumer.await()

            when (chat) {
                is AguiClient.ChatResult.Success -> {
                    logger(
                        "AG-UI done, reply ${chat.text.length} chars, firstToken=${chat.firstTokenLatencyMs}ms, attempts=${chat.attempts}",
                    )
                    fireDoneOnce(turn)
                }
                is AguiClient.ChatResult.Error -> {
                    logger("AG-UI failed: ${chat.message}")
                    // 封轮（critic P2-⑨）：cancel 已 hide+backToIdle，残跑管线拿到 Error 时
                    // 错误泡/状态行属幽灵输出（且 state 已 IDLE，12s 收泡不会调度）
                    if (!turn.sealed) {
                        status("对话失败：${chat.message}")
                        bubbleHidePolicy.onBubbleShown() // 错误泡同走 12s 自动收（critic ②）
                        bubble.show("对话失败：${chat.message}")
                    }
                    fireErrorOnce(turn, chat.message)
                }
            }

            // 4. 多轮：TTS 播完回 LISTENING（重启 VAD + 30s 超时）；超时由 routeBlock 回 IDLE。
            // currentTurn 一致性检查：旧轮残跑落地时若新一轮已起跑（currentTurn 换主），
            // 不得劫持其状态机（旧轮 vad.start+LISTENING 会踩掉新轮的 PROCESSING）
            // S8-3 回归守卫：cancel 封轮后旧管线残跑至此，state 可能已被 startListening
            // 切到 LISTENING（非 IDLE）--原仅靠 state!=IDLE 拦截失效，须加 !sealed 防残跑
            // 多轮迁移重复 vad.start 干扰新轮采音（删此 -> 残跑重置 VAD pre-roll -> 红）
            if (currentTurn === turn && state != VoiceStateRouter.State.IDLE && !turn.sealed) {
                vad.start()
                transition(VoiceStateRouter.State.LISTENING, "multi-turn, VAD restart")
                status("继续聆听…（30s 无语音退出）")
            }
        } catch (e: CancellationException) {
            sentences.close() // 幂等（正常路径已 close）；取消拆解也让 ttsConsumer 落地
            throw e // 协程取消是正常拆解（scope 回收 / barge-in），不算失败轮
        } catch (e: Throwable) {
            sentences.close() // #10：防 ttsConsumer 悬挂空 channel（Channel.close 幂等可重复调）
            if (!turn.terminalFired.get()) {
                logger("pipeline exception, treated as failed turn: ${e.message}")
            }
            fireErrorOnce(turn, "管线异常: ${e.message}")
            throw e
        }
    }

    /** 逐句消费：消毒 → TTS → 播放。流水线版（合成与播放解耦）：
     *  生产者按句合成 PCM 入音频队列，消费者按序播放——播 N 句时生产者预合成 N+1/N+2，
     *  N 播完 N+1 已就绪，消除句间合成空隙（旧版串行合成→播→合成，每句间 ~0.7s 静音=断词）。
     *  S8-5：reveal 时序退役，气泡文字改由 delta 回调流式上屏，本路径仅 TTS 合成与播放。 */
    private suspend fun consumeSentences(sentences: Channel<String>, turn: TurnFlags) {
        val audioQueue = Channel<ByteArray>(2) // 容量2：播放时预取下两句 PCM
        // 生产者：消毒 → 合成 → 入队（合成是阻塞 HTTP，后台跑不挡播放）
        val producer = scope.async(Dispatchers.IO) {
            try {
                for (original in sentences) {
                    if (turn.sealed) break // barge-in 出口
                    val clean = TtsSanitizer.sanitize(original)
                    if (clean.isEmpty()) {
                        logger("sanitize 后无 alnum，跳过合成: $original")
                        continue
                    }
                    when (val r = synthesizeAny(clean)) {
                        is TtsClient.Result.Audio -> audioQueue.send(r.pcm) // 队满背压，天然限速
                        is TtsClient.Result.Error ->
                            logger("TTS 单句失败，降级跳过播放（气泡文字不受影响）: ${r.message}")
                    }
                }
            } finally {
                audioQueue.close() // 生产完毕/取消 → 关队列，消费者自然收尾
            }
        }
        // 消费者：单 player 流式跨句复用同一 AudioTrack（消除逐句重建 ~120ms 空隙）
        val player = VoicePlayer()
        currentPlayer = player // barge-in 停播挂点（整轮一个，不再逐句覆盖）
        try {
            for (pcm in audioQueue) {
                // 窗口二查（critic P1-①）：synthesize 阻塞期间 cancel 可过境，进 play 前必须再查岗
                // S8-3 回归守卫：sealed 残跑退出（避免幽灵播放）
                if (state == VoiceStateRouter.State.IDLE || currentTurn !== turn || turn.sealed) break
                if (state == VoiceStateRouter.State.PROCESSING) {
                    transition(VoiceStateRouter.State.SPEAKING, "first audio")
                }
                // 单句播放异常（AudioTrack 故障）不中止后续句：释放废 track，下句 play 重建
                try {
                    player.play(pcm)
                } catch (e: Exception) {
                    logger("播放异常（气泡文字不受影响）: ${e.message}")
                    player.reset()
                }
            }
            runCatching { player.finish() } // 收尾等播完 + 释放
        } finally {
            player.reset() // 兜底释放（finish/异常/barge-in；幂等）
            if (currentPlayer === player) currentPlayer = null
            producer.cancel() // 消费者退出（含 barge-in/异常）→ 取消生产者防挂（队满 send 阻塞）
        }
    }

    private fun backToIdle(reason: String) {
        transition(VoiceStateRouter.State.IDLE, reason)
        kwsPaused = false
    }

    private fun transition(to: VoiceStateRouter.State, reason: String) {
        state = to
        _stateFlow.value = to
        // 回归守卫：回 IDLE 且 KWS 意愿已撤 → 收采麦循环（删此 → 对话中 stopIdleCapture
        // 后 collectJob 挂活 = 麦克风孤儿空采 → VoiceSessionManagerIdleCaptureTest 红）
        if (to == VoiceStateRouter.State.IDLE && !idleCaptureWanted && collectJob != null) {
            teardownIdleCapture()
        }
        // S5 收泡：回 IDLE 且本轮弹过气泡 → 播完 12s 收；回 LISTENING 的多轮轮次不收
        if (to == VoiceStateRouter.State.IDLE && bubbleHidePolicy.onReturnToIdle()) {
            bubble.hideLater(BubbleHidePolicy.DELAY_MS)
        }
        // 对话状态→精灵行映射（知识包 §4.1 CONVERSATION_VISUAL）：listening→waiting /
        // processing→review / speaking→talking / idle→idle；服务未运行时 companion no-op
        // S8-7：唤醒路径 SPEAKING(wozai) wakeAckActive=true 时跳过 TALKING--视觉由
        // startListening 的 playPetOnceThen(JUMPING->WAITING) 接管；TTS 回复走通用 TALKING
        if (!(to == VoiceStateRouter.State.SPEAKING && _wakeAckActive.value)) {
            PetOverlayService.setPetState(
                when (to) {
                    VoiceStateRouter.State.LISTENING -> PetFrameTable.WAITING
                    VoiceStateRouter.State.PROCESSING -> PetFrameTable.REVIEW
                    VoiceStateRouter.State.SPEAKING -> PetFrameTable.TALKING
                    VoiceStateRouter.State.IDLE -> PetFrameTable.IDLE
                },
            )
        }
        logger("state -> $to ($reason)")
    }

    private fun status(msg: String) {
        statusListener?.invoke(msg)
    }

    private fun shortsToBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            out[2 * i] = (samples[i].toInt() and 0xFF).toByte()
            out[2 * i + 1] = (samples[i].toInt() shr 8 and 0xFF).toByte()
        }
        return out
    }

    companion object {
        private const val TAG = "VoiceSession"
    }
}
