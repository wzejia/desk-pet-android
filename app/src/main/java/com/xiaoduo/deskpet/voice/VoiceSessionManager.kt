package com.xiaoduo.deskpet.voice

import android.content.Context
import com.xiaoduo.deskpet.PetOverlayService
import com.xiaoduo.deskpet.agui.AguiClient
import com.xiaoduo.deskpet.device.DeviceClient
import com.xiaoduo.deskpet.device.DeviceIntentMatcher
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 语音会话编排（半双工四态，知识包 §3.5 + 对账表状态机节）：
 *
 * idle / listening / processing / speaking；麦块经 [VoiceStateRouter] 分发
 * （idle→KWS 挂点 / listening→VAD / speaking+processing→KWS——S12 唤醒词打断转正，
 * 对话期唤醒命中走 startListening(playWakeAck=true) 先 cancel 再接续）；
 * [kwsPaused] 公开 var = S5 KWS pause flag 挂点（只门 IDLE 分支）；
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
    /** S15：「我在」播完驻留时长（ms），驻留结束换「在听…」聆听提示泡；测试注入小值。 */
    private val listeningHintLingerMs: Long = 2_500L,
    /** S18：VAD 说话电平阈值 provider（环境校准联动；默认 VadDetector 0.05）。 */
    private val vadSpeechLevel: () -> Double = { VadDetector.SPEECH_LEVEL },
    /** S20：MCP 调用结果是否播报（默认 true；false 时含工具调用轮跳过 TTS，文字照上屏）。 */
    private val speakMcpResultProvider: () -> Boolean = { true },
    // S21（用户拍板 2026-09-02，独立开关默认开）：含 MCP 工具调用的轮次结束后回 IDLE
    // （KWS 守门需重新「小朵小朵」唤醒），不走多轮续听——密集架自带播报会被 VAD 当用户
    // 语音收下续出幽灵对话（甚至再触发 MCP 调用）。会话线程服务端保留，重唤醒不丢上下文。
    private val rewakeAfterToolCallProvider: () -> Boolean = { true },
    // S23（P4 提前）：播放期回声消除实验开关（默认开；关=麦块直通 KWS，A/B 对照用）
    private val echoCancelProvider: () -> Boolean = { true },
    // S24：App 内播报音量（治板子满格致麦克风削波；每轮现读，设置页滑杆即时生效）
    private val ttsVolumeProvider: () -> Float = { 1f },
    // S26 设备技能（整理台/净化柜本地意图）：ASR 后先匹配，命中直连设备跳过 LLM。
    // null=禁用（默认；工厂按设置页地址装配）。设备自带播报（playFlag）→ 设备轮按
    // 工具轮待遇（turnSawToolCall 置位，S20 静音/S21 重唤醒语义自动套用）。
    private val deviceSkillMatcher: ((String) -> DeviceIntentMatcher.Command?)? = null,
    private val deviceSkillExecutor: (suspend (DeviceIntentMatcher.Command) -> DeviceClient.SkillResult)? = null,
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
    /** S20：本轮是否出现 MCP 工具调用（TOOL_CALL_START 到达即置位；云端路径无 MCP 恒 false）。 */
    private val turnSawToolCall = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun voiceChat(stripped: String, onDelta: (String) -> Unit): AguiClient.ChatResult {
        turnSawToolCall.set(false) // S20：每轮归零
        // S37（用户拍板 2026-09-11）：语音轮平台 thread 跟随当前文字会话——
        // 「语音轮与文字轮是同一会话的不同输入方式」的平台侧兑现。null/空白回退
        // default（纯悬浮窗无会话兕底，行为同旧版）。
        val threadKey = threadKeyProvider?.invoke()?.takeIf { it.isNotBlank() }
            ?: AguiClient.DEFAULT_THREAD_KEY
        val ctx = appContext ?: return aguiFactory().chat(
            stripped,
            onDelta = onDelta,
            threadKey = threadKey,
            onToolCallEvent = {
                turnSawToolCall.set(true)
                logger("MCP tool call seen (TOOL_CALL_START)")
            },
        )
        if (!NetConfig.llmIsCloud(ctx)) return aguiFactory().chat(
            stripped,
            onDelta = onDelta,
            threadKey = threadKey,
            onToolCallEvent = {
                turnSawToolCall.set(true)
                logger("MCP tool call seen (TOOL_CALL_START)")
            },
        )
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

    /**
     * wozai 播放世代号（S12 唤醒打断唤醒）：每次 playAckThenListen 自增，job 收尾
     * 比对世代识别残跑旧 job——唤醒打断唤醒时旧 wozaiJob 的 finally 可落在新一轮
     * 置 wakeAckActive=true 之后，无条件写会覆盖新一轮信号（世代闭包捕获，无
     * 「job 体自读 wozaiJob 引用」的发布竞态）。
     */
    private val wozaiEpoch = AtomicLong(0)

    /** S15：当前气泡内容为本 VSM 挂的「在听…」提示（transition 离聆听收泡的归属判定）。 */
    @Volatile
    private var listeningHintActive = false

    /** S15：wozai 完成进聆听前置位——transition(LISTENING) 消费一次不当即显提示泡，
     *  「我在」驻留 linger 后由 swap job 顶替（防一闪而过，用户 2026-08-24 拍板）。 */
    @Volatile
    private var suppressListeningHintOnce = false

    /** S15 聆听提示泡：内容顶替式 show（BubbleView 重打，无 hide 间隙）。 */
    private fun showListeningHint() {
        bubble.show(LISTENING_HINT)
        listeningHintActive = true
    }

    /** S15：「我在」驻留 [listeningHintLingerMs] 后换聆听提示泡。守卫：世代变（被新轮
     *  唤醒打断）/已离聆听（cancel 或 ASR 抢跑进 PROCESSING）→ 不补枪。 */
    private fun scheduleListeningHintSwap(epoch: Long) {
        scope.launch {
            delay(listeningHintLingerMs)
            if (wozaiEpoch.get() == epoch && state == VoiceStateRouter.State.LISTENING) {
                showListeningHint()
            }
        }
    }

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

    /**
     * S5 KWS pause flag 挂点（对话期间 true）。只门 router 的 IDLE 分支——对话期回到
     * IDLE 不即解 KWS；SPEAKING/PROCESSING 两态麦块恒进 KWS 不受其门（S12 唤醒词
     * 打断靠这两态持续进 KWS 才可达）。
     */
    var kwsPaused: Boolean
        get() = router.kwsPaused
        set(v) {
            router.kwsPaused = v
        }

    /** S5 KWS 块订阅挂点：IDLE 态麦块回调（单条采麦流扇出）。 */
    @Volatile
    var kwsSink: ((ByteArray) -> Unit)? = null

    /** S23 NLMS 回声消除器：TTS 参考由 ttsConsumer 的 player 分接头推入；
     *  无参考（IDLE/聆听）processBlock 原样直通，零行为变更。 */
    private val echoCanceller = NlmsEchoCanceller()

    // S23 遥测累加器：50 块窗口内进/出 RMS 均值（口径同 vad diag level，可直接对照校准分布）
    private var echoDiagIn = 0.0
    private var echoDiagOut = 0.0

    /** UI 状态行回调（设置页语音自检显示进度；Compose snapshot state 跨线程写安全）。 */
    @Volatile
    var statusListener: ((String) -> Unit)? = null

    /** P1 会话历史出口（挂点同 [kwsSink]/[statusListener] 模式；null = 不记录，行为与接入前一致）。 */
    @Volatile
    var transcriptSink: TranscriptSink? = null

    /**
     * S37（用户拍板 2026-09-11）：语音轮平台 thread 路由 key 来源——返回当前活跃会话 id，
     * 文字/语音同 thread 上下文互通（完整语义见 [voiceChat] 注）。null/返回空白 = 回退
     * [AguiClient.DEFAULT_THREAD_KEY]（纯悬浮窗无会话兕底）。挂点同 transcriptSink
     * （ChatViewModel attach 时一并挂）。
     */
    @Volatile
    var threadKeyProvider: (() -> String?)? = null

    private val vad = VadDetector(speechLevelProvider = vadSpeechLevel)
    private var collectJob: Job? = null
    private var pipelineJob: Job? = null

    /**
     * 按住说话按下 / 唤醒后进入聆听（任意态可入：对话态先打断再接续）。
     * [playWakeAck]=true（仅 KWS 唤醒路径）时走 #9 顺序式「我在」：
     * SPEAKING 播预录应答 → 播完进 LISTENING；
     * 按住说话/自检路径不传参不播（手指即即时反馈，播了反而延迟采麦）。
     */
    fun startListening(playWakeAck: Boolean = false) {
        emptyAsrResumesLeft = EMPTY_ASR_MAX_RESUMES // S19-2：新对话链，续听额度复位
        // S8-3 按住打断 + S12 唤醒词打断（spike 双合格转正，两条路径共用同一打断接续）：
        // speaking/processing 态 = 打断并接续新一轮。复用 cancelCurrentTurn（全同步：
        // 封轮+停 TTS/停「我在」+收泡+部分回复补发 done+回 IDLE），返回后 state==IDLE，
        // 唤醒路径再走「我在」→LISTENING，按住路径直进 LISTENING。
        // S12 前唤醒路径在对话态物理不可达（对话两态麦块恒 DROP）；S12 起
        // SPEAKING/PROCESSING 麦块恒进 KWS，唤醒打断可达（守卫四问 Q1-Q4 答卷见票据）。
        // LISTENING 态唤醒仍物理不可达（VAD 占用麦块）；LISTENING 态按住：已在采音，
        // 下方 if(state!=IDLE)return no-op。
        if (state == VoiceStateRouter.State.SPEAKING || state == VoiceStateRouter.State.PROCESSING) {
            cancelCurrentTurn()
        }
        // S33 聆听盲区：LISTENING 期唤醒路径（playWakeAck=true，KWS 命中回调）= 重唤醒：
        // 终止当前聆听轮（cancelCurrentTurn 的 LISTENING 分支：vad.stop+backToIdle，
        // 已收集音频丢弃）→ 重播「我在」→ 重新聆听，与 S12 打断接续同构。此前非 IDLE
        // 早退 → 聆听期再喊 100% 必漏（14.18 受控测试实锤）。按住路径（playWakeAck=
        // false）保持 no-op：VAD 已在采音，最小侵入（VoiceSessionManagerS33BlindSpotTest 锁）。
        else if (state == VoiceStateRouter.State.LISTENING && playWakeAck) {
            cancelCurrentTurn()
        }
        if (state != VoiceStateRouter.State.IDLE) return
        ensureCollectLoop()
        kwsPaused = true // 半双工第二层：对话期 IDLE 分支暂停 KWS
        val ack = if (playWakeAck) wakeAck else null
        if (ack == null) {
            // 默认路径 + wozai 缺失/损坏降级：直接进聆听
            vad.start()
            transition(VoiceStateRouter.State.LISTENING, "VAD start, timeout 30s")
            status("聆听中…（30s 无语音自动退出）")
            return
        }
        // 顺序式「我在」：SPEAKING(wozai) 期麦块走 KWS（S12）——期间再喊唤醒词 =
        // 唤醒打断唤醒：cancel 后重播「我在」（守卫四问 Q2：每轮需真人再喊，应用自身
        // 音频不含唤醒词且无自馈，对照 08-10 strip 死循环教训；KwsCore 命中即 reset，
        // 同一声词尾不会二次命中）
        playAckThenListen()
    }

    /**
     * 顺序式「我在」→ LISTENING（KWS 唤醒路径与纯唤醒词重听路径共用）。
     * 前置：kwsPaused 已置 true（对话期）。SPEAKING(wozai) 播预录应答 → 播完进 LISTENING。
     * S8-7：入口置 wakeAckActive 让 transition(SPEAKING) 跳过通用 TALKING 映射；
     * S13：唤醒视觉由 JUMPING 一次性改为直接 WAITING 静止（用户 2026-08-24 拍板取消唤醒跳）；
     * wozai 完进 LISTENING 置 false。
     */
    private fun playAckThenListen() {
        val epoch = wozaiEpoch.incrementAndGet()
        _wakeAckActive.value = true
        PetOverlayService.setPetState(PetFrameTable.WAITING)
        transition(VoiceStateRouter.State.SPEAKING, "wake-ack wozai")
        status("我在")
        // S14-1：「我在」应声可视化——悬浮窗气泡同步显示（纯现场反馈，不进消息流）。
        // 收泡保险：cancel 路径 cancelCurrentTurn 收泡 / onBubbleShown 策略兜底；
        // S15 起正常播完不再即收——驻留 linger 后由 swap job 换「在听…」提示泡（防一闪而过）。
        bubble.show("我在")
        bubbleHidePolicy.onBubbleShown()
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
                // S8-7：完成后复位 wakeAckActive（critic P2）--wozai 完时 state 已被
                // onUtterance 抢跑切 PROCESSING 则原 if(state==SPEAKING) 跳过复位致残留，
                // 下次 TTS SPEAKING 被误 gate（不切 TALKING）+ 全屏重复播 jumping。
                // cancel 路径 cancelCurrentTurn 已置 false，此处重复写无害（幂等）。
                // S12 回归守卫（世代比对）：唤醒打断唤醒时旧 wozaiJob 残跑收尾可落在
                // 新一轮置 true 之后——世代已变则本 job 是旧轮残跑，不得复位新一轮信号
                // （删世代比对 → 旧 finally 覆盖 → 第二轮 SPEAKING(wozai) 期信号丢失
                //  → VoiceSessionManagerWakeInterruptTest 唤醒打断唤醒用例红）
                if (wozaiEpoch.get() == epoch) _wakeAckActive.value = false
            }
            // cancel 双防线：cancelCurrentTurn 先 wozaiJob.cancel()（isActive=false 先行可见）
            // 再 wakeAck.stop() 打断 playBlocking；backToIdle 后 state 也非 SPEAKING。
            // 留痕（critic P3-⑥）：双查是 check-then-act，cancel 与 transition 间有微秒窗——
            // 最坏拦不住一次已落地的 LISTENING 迁移（30s 无语音超时自愈回 IDLE），无状态泄漏
            if (isActive && state == VoiceStateRouter.State.SPEAKING) {
                vad.start()
                // S15：置一次性抑制标——transition(LISTENING) 不当即显提示泡，
                // 「我在」驻留 linger 后由 swap job 顶替（内容替换，无 hide 间隙）
                suppressListeningHintOnce = true
                transition(VoiceStateRouter.State.LISTENING, "wozai done, VAD start")
                status("聆听中…（30s 无语音自动退出）")
                scheduleListeningHintSwap(epoch)
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

    /** 测试缝：S33 盲区旁路单测直接驱动麦块分发（体例同 onUtterance internal）。 */
    internal fun routeBlockForTest(block: ByteArray) = routeBlock(block)

    /** S18 声学诊断：聆听期已消费块数（每 10 块打一行电平轨迹）。 */
    @Volatile
    private var vadDiagTicks = 0

    /** S19-2 空 ASR 续听剩余额度（真文本/新对话链复位）。 */
    @Volatile
    private var emptyAsrResumesLeft = EMPTY_ASR_MAX_RESUMES

    /** 测试缝：续听额度读取（S19-2 单测断言用）。 */
    internal val emptyAsrResumesForTest: Int get() = emptyAsrResumesLeft

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
            VoiceStateRouter.Sink.KWS -> {
                // S23：播放期麦块先过 NLMS 消回声再进 KWS（主+影子双引擎同受益）；
                // 无参考/开关关=原样直通（processBlock 内部有新鲜度快路径）
                val out = if (echoCancelProvider()) echoCanceller.processBlock(block) else block
                // S23 遥测：每 50 个消回声块落一行（证实 NLMS 真在跑 + 参考积压/窗能量水位 +
                // 进/出 RMS——AGC 深压制（长播报越久 in 越低）与 NLMS 实效（out<<in=在消）同屏可见）
                if (out !== block) {
                    echoDiagIn += (EnergyGate.rmsOf(block) * (32768f / 8000f)).toDouble()
                    echoDiagOut += (EnergyGate.rmsOf(out) * (32768f / 8000f)).toDouble()
                    if (echoCanceller.processedBlocks % 50 == 0L) {
                        logger(
                            "echo cancel active: ${echoCanceller.statsSnapshot()} " +
                                "inRms=%.4f outRms=%.4f".format(echoDiagIn / 50, echoDiagOut / 50),
                        )
                        echoDiagIn = 0.0
                        echoDiagOut = 0.0
                    }
                }
                kwsSink?.invoke(out) // S5 挂点，当前无订阅即丢
            }
            VoiceStateRouter.Sink.DROP -> Unit // IDLE+kwsPaused 丢弃（对话期回 IDLE 不即解 KWS）
            VoiceStateRouter.Sink.VAD -> {
                // S33 盲区修复：聆听期唤醒词可达——块主投 VAD 判停之外旁路喂 KWS
                // （此前单投 VAD，再喊唤醒词 100% 必漏）。走 NLMS 消「我在」尾回声
                // （零参考直通安全，与 KWS 分支同处理）；命中回调 startListening 走
                // 重唤醒分支。kwsSink 为空（服务未挂）时零开销跳过。
                if (kwsSink != null) {
                    val out = if (echoCancelProvider()) echoCanceller.processBlock(block) else block
                    kwsSink?.invoke(out)
                }
                // S18 声学诊断：聆听期电平轨迹（每 10 块=1s 一行；量化 rk3568 AGC 释放曲线）
                vadDiagTicks++
                if (vadDiagTicks % 10 == 0) {
                    val lvl = EnergyGate.rmsOf(block) * (32768f / 8000f)
                    logger("vad diag level=%.4f".format(lvl))
                }
                when (val ev = vad.feed(block)) {
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
                // S19-2 空 ASR 续听（rk3568 泵态幽灵轮实测 20:57）：纯噪声误触发 utterance
                // 的 ASR 空文本不是「失败」而是「没说话」--重进聆听而非落 IDLE，
                // 上限 [EMPTY_ASR_MAX_RESUMES] 次防持续噪声下无限幽灵循环。
                if (r.message.startsWith(EMPTY_ASR_PREFIX) && emptyAsrResumesLeft > 0) {
                    emptyAsrResumesLeft--
                    logger("empty ASR（纯噪声轮），续听（剩 $emptyAsrResumesLeft 次）")
                    vad.start()
                    transition(VoiceStateRouter.State.LISTENING, "empty asr, resume listening")
                    status("聆听中…（30s 无语音自动退出）")
                    return
                }
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
        emptyAsrResumesLeft = EMPTY_ASR_MAX_RESUMES // S19-2：真文本到达，续听额度复位
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

        // S26-3 设备技能：本地意图命中 → 直连设备（跳过 LLM），结果走同一分句/TTS 管线。
        // 设备自带语音播报（playFlag=true）→ 置工具轮标记，S20 静音/S21 重唤醒自动适用。
        var deviceResult: DeviceClient.SkillResult? = null
        val deviceCmd = deviceSkillMatcher?.invoke(stripped)
        val executor = deviceSkillExecutor
        if (deviceCmd != null && executor != null) {
            logger("device skill hit: $deviceCmd")
            turnSawToolCall.set(true)
            status("正在控制设备…")
            deviceResult = runCatching { executor.invoke(deviceCmd) }
                .onFailure { logger("device skill exception: ${it.message}") }
                .getOrElse { DeviceClient.SkillResult.Fail("设备控制异常，请重试") }
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
            // S26-3：delta 处理抽局部函数——LLM 流式与设备技能结果共用同一条上屏/分句/TTS 路
            fun handleDelta(delta: String) {
                if (turn.sealed) return
                transcriptSink?.onAssistantDelta(delta)
                if (turn.sealed) return
                if (bubbleStarted) {
                    bubble.append(delta)
                } else {
                    bubble.show(delta)
                    bubbleHidePolicy.onBubbleShown()
                    bubbleStarted = true
                }
                for (c in delta) {
                    for (s in splitter.feed(c)) {
                        runBlocking { sentences.send(s) }
                    }
                }
            }
            val deviceSpeech = when (val dr = deviceResult) {
                is DeviceClient.SkillResult.Ok -> dr.speech
                is DeviceClient.SkillResult.Fail -> dr.speech
                null -> null
            }
            val chat: AguiClient.ChatResult = if (deviceSpeech != null) {
                handleDelta(deviceSpeech)
                AguiClient.ChatResult.Success(deviceSpeech, firstTokenLatencyMs = null, attempts = 1)
            } else voiceChat(stripped) { delta ->
                handleDelta(delta)
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
                if (rewakeAfterToolCallProvider() && turnSawToolCall.get()) {
                    // S21：工具轮后需重新唤醒——机器播报时段不进 VAD 续听，KWS 守门
                    backToIdle("tool call turn, re-wake required")
                } else {
                    vad.start()
                    transition(VoiceStateRouter.State.LISTENING, "multi-turn, VAD restart")
                    status("继续聆听…（30s 无语音退出）")
                }
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
                    // S20 MCP 结果静音（用户拍板：密集架自带播报）：含工具调用轮跳过合成，
                    // 文字已随 delta 上屏/上泡不受影响；TOOL_CALL_START 先于文本到达
                    // （探针实测事件序），分句时标记已知
                    if (!speakMcpResultProvider() && turnSawToolCall.get()) {
                        logger("MCP 结果静音开关：含工具调用轮跳过 TTS")
                        continue
                    }
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
        player.volume = ttsVolumeProvider() // S24：App 内播报音量（须在首建 track 前设置）
        // S23：TTS 已写 PCM 推消回声器作 NLMS 参考（自产 PCM=完美参考信号）
        player.referenceListener = { pcm, sr -> echoCanceller.pushReferencePcm(pcm, sr) }
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
        val from = state
        state = to
        _stateFlow.value = to
        // S15 聆听提示泡（用户 2026-08-24：S13 静止化后聆听期零反馈缺口）：
        // 进 LISTENING——wozai 路径被一次性抑制标拦住（驻留 swap job 负责顶替「我在」）；
        // 多轮回听（from==SPEAKING）不显（S5：回复泡跨轮驻留不收，语义不动）；
        // 其余新鲜进入（按住/降级，from==IDLE）即显「在听…」低存在感提示泡。
        if (to == VoiceStateRouter.State.LISTENING) {
            when {
                suppressListeningHintOnce -> suppressListeningHintOnce = false
                from == VoiceStateRouter.State.SPEAKING -> Unit
                else -> showListeningHint()
            }
        }
        // 离 LISTENING 即收提示泡（思考期无泡；回复 delta/错误泡到达时另行 show 顶替）
        if (from == VoiceStateRouter.State.LISTENING &&
            to != VoiceStateRouter.State.LISTENING && listeningHintActive
        ) {
            listeningHintActive = false
            bubble.hide()
        }
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
        // S8-7+S13：唤醒路径 SPEAKING(wozai) wakeAckActive=true 时跳过 TALKING——视觉由
        // playAckThenListen 入口的 setPetState(WAITING) 静止帧承担；TTS 回复走通用 TALKING
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

        /** S15 聆听提示泡文案（低存在感：聆听期悬浮窗持续反馈）。 */
        private const val LISTENING_HINT = "在听…"

        /** S19-2 空 ASR 错误前缀（平台+云端三家统一约定：{prefix}…）。 */
        private const val EMPTY_ASR_PREFIX = "ASR 返回空文本"

        /** S19-2 续听上限（防持续噪声下无限幽灵循环）。 */
        internal const val EMPTY_ASR_MAX_RESUMES = 2
    }
}
