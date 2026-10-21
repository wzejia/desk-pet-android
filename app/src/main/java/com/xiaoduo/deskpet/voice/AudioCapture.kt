package com.xiaoduo.deskpet.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import com.xiaoduo.deskpet.util.PetLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.math.max

/**
 * 单条采麦流（Android IO 层，知识包 §3.2/§3.5 + 踩坑 #33：stream 常开 + 状态机门控，
 * KWS 与 VAD 复用同一条流避免双流抢麦）。
 *
 * - VOICE_COMMUNICATION 优先（硬件 AEC，坑 #35 外放回声），构造失败 fallback DEFAULT 记日志
 * - 16kHz/Int16/mono，读循环按 100ms 块（3200 字节）攒齐后推 [blocks]
 * - 读循环只推队列（tryEmit 非阻塞），消费/解码全在订阅侧协程（坑 #33/#51：回调里只推队列）
 * - SharedFlow 广播 = 多订阅者扇出（S5 KWS 挂同一条流）
 *
 * 调用方须先确保持有 RECORD_AUDIO 运行时权限（设置页语音自检走真实申请路径）。
 */
class AudioCapture(
    /**
     * S18（rk3568 声学诊断）：采麦源模式。
     * 0=VOICE_COMMUNICATION（默认，手机硬件 AEC 防外放回声，坑 #35）；
     * 1=VOICE_RECOGNITION（绕开通话预处理链）；2=UNPROCESSED（HAL/codec 级 AGC 的最后一试，
     * 板子 rk809 AGC 释放极慢--说话/播放后噪声底抬到 0.08-0.34 顶满 VAD 8s 不判停，
     * 实测换 VOICE_RECOGNITION 校准分布仍钉死 0.046-0.049=AGC 在更底层）。
     * 各模式构造失败均 fallback DEFAULT 记日志。
     */
    private val sourceMode: Int = 0,
    /**
     * 自愈重启钩 seam（诊断报告 §7.1.3）：连续 [TERMINAL_START_FAILURE_THRESHOLD] 轮采麦
     * 启动终态失败时回调（fire 即复位，见 [TerminalStartFailureDetector]）。null=不挂钩
     * （既有测试/旧调用零改动）；装配层（VoiceClientFactory）接冷却检查 + killProcess
     * 自尽 → START_STICKY 自动重拉（红线：只准 killProcess 零惩罚，严禁抛未捕获异常）。
     */
    private val onTerminalStartFailure: (() -> Unit)? = null,
) {

    /** 100ms 采麦块 + 采集时刻（S40：producer 侧盖戳，消费停顿后仍可判块时代）。缓冲
     *  64 块 ≈ 6.4s，满则丢最旧——与 VAD pre-roll deque 同策（保最新音频）。 */
    val stampedBlocks: SharedFlow<StampedBlock> get() = _stamped

    /** 旧消费者口径（纯 PCM 字节块）：UI 声波/校准 RMS 订阅零改动。 */
    val blocks: Flow<ByteArray> get() = _stamped.map { it.pcm }

    private val _stamped = newBlocksFlow()

    @Volatile
    var isRunning = false
        private set

    /** 实际生效的音频源（VOICE_COMMUNICATION=7 / DEFAULT=1），诊断日志用。 */
    @Volatile
    var usedSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION
        private set

    private var job: Job? = null
    private var record: AudioRecord? = null

    /** start 双调门闩（backlog #8b）：见 [OnceGate] 类注释。 */
    private val startGate = OnceGate()

    /** 当前持有门闩的令牌（stop() 与读循环 finally 共享；stop 后新 start 令牌必不同）。 */
    @Volatile
    private var gateToken: Long? = null

    /**
     * ROB-1：start/stop 生命周期串行锁。两者对 job/gateToken 的 check-then-act 必须
     * 互斥——否则并发 stop 可插在 start 的 tryAcquire→launch 之间（读到旧 job=null，
     * 释放刚占的闩）→ 丢 stop（麦克风滞留）或旧循环未死新循环又起（双捕获抢麦）。
     * job/gateToken 从此只在锁内读写（isRunning 保持 @Volatile 供跨线程查询）。
     */
    private val lifecycleLock = Any()

    /**
     * 自愈重启钩判定器（诊断报告 §7.1.3，纯逻辑，零 Android import，JVM 可测；样式照
     * [ZeroBlockDetector]）：连续 [TERMINAL_START_FAILURE_THRESHOLD] 轮「采麦启动终态失败」判
     * 「僵尸 input/长病态」，[TerminalStartFailureDetector.onTerminalFailure] 返回 true 恰一次
     * 并内部归零（**fire 即复位**——自尽场景进程即死无副作用；冷却场景复位后重新攒证据，
     * 两场景统一口径，不存在「冷却内脏计数」问题）。成功进录音态由调用方 [TerminalStartFailureDetector.reset]
     * 清零（真实录到音才算病愈；无关 stop/start 不清证据链）。
     *
     * 计数点与承载：readLoop 启动终态失败分支计数、成功进录音态处复位（AudioCapture 内
     * 回归守卫注释锚定）；判定器实例挂 AudioCapture 字段（终态失败终结 readLoop，实例级
     * 才跨轮攒得上）。seam 默认 null 时只计数不外呼，零行为变化（既有测试零改动）。
     *
     * 回归守卫：AudioCaptureTerminalStartFailureDetectorTest（2 轮不触发/第 3 轮触发/fire 后
     * 须重新计满/成功复位/跨段不凑数/阈值常数钉死 3/冷却常数 5min 且 ≥60s；删 fire 复位 →
     * fire 后重计组红，删成功复位 → 成功复位组红，改阈值 → 常数锚红）。
     */
    private val terminalFailureGuard = TerminalStartFailureDetector()

    /**
     * 软件自动增益判定器（SoftwareAgc 票 2026-10-17，讯飞逆向等价物；类与常量区在文件尾）。
     * **实例级承载**（照 terminalFailureGuard 先例）：EMA 噪声底/增益平滑态是「房间声学属性」
     * 非流证据，stop/start 不复位，进程死随进程湮灭。OnceGate 保证单线程访问，无锁。
     */
    private val agc: SoftwareAgc? = if (SOFTWARE_AGC_ENABLED) SoftwareAgc() else null

    fun start(scope: CoroutineScope) {
        // 旧写法 if (isRunning) return 的检查与 job 置位间有竞态窗口：双调各过检查
        // 起双读循环抢麦（critic-#4 P3，既有问题）。门闩把检查+占位收敛成一次原子操作；
        // ROB-1 再加生命周期串行锁：与 stop 的 cancel/release 序列互斥。
        synchronized(lifecycleLock) {
            val token = startGate.tryAcquire() ?: return
            gateToken = token
            job = scope.launch(Dispatchers.IO) {
                try {
                    readLoop()
                } finally {
                    // 读循环自然终结（权限缺失/初始化失败）也放行重启。
                    // 带令牌 release：若 stop→start2 已换主，本过期 release 自动失效，
                    // 不会错放 start2 的闩（backlog #11 理论窗：job1 finally 晚于 start2）。
                    startGate.release(token)
                }
            }
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            job?.cancel()
            job = null
            // 协程可能未及开跑即被取消（上面 finally 不执行），此处兜底释放；双放幂等
            gateToken?.let { startGate.release(it) }
            gateToken = null
        }
    }

    @SuppressLint("MissingPermission") // 权限由调用方（设置页）真实申请路径保证
    private fun buildRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            VadDetector.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = maxOf(minBuf, VadDetector.BLOCK_BYTES * 8)
        // 源候选：mode 决定首选源（S57 纯函数解析，映射单点维护——判定与构造不分叉；
        // expectedSourceFor 返回值与 AudioSource 常数逐一相等：7=VOICE_COMMUNICATION /
        // 6=VOICE_RECOGNITION / 9=UNPROCESSED，AudioRecord 构造直接吃 int，不设二次映射——
        // 二次映射会在补档位时静默分叉，见 ce-review 20261020 #9），
        // 初始化失败降级 DEFAULT（坑 #35 注释）。
        val preferred = expectedSourceFor(sourceMode)
        val candidates = listOf(preferred, MediaRecorder.AudioSource.DEFAULT)
        for (source in candidates) {
            val r = runCatching {
                AudioRecord(
                    source,
                    VadDetector.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                )
            }.getOrNull()
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                if (source == MediaRecorder.AudioSource.DEFAULT) {
                    PetLogger.w(TAG, "VOICE_COMMUNICATION 不可用，fallback DEFAULT（无硬件 AEC）")
                }
                usedSource = source
                return r
            }
            r?.release()
        }
        PetLogger.e(TAG, "AudioRecord 初始化失败（两种源均不可用）")
        return null
    }

    private suspend fun CoroutineScope.readLoop() {
        val zeroGuard = ZeroBlockDetector()
        var selfHeals = 0
        while (isActive) {
            // 启动序列（形态 A 有限重试；自愈重建复用同一路径）。null=终态失败（权限缺失/
            // 构造两源均败/N 次未进录音态），各自已记日志，照旧 return 由 finally 放行门闩。
            val r = startRecordWithRetry()
            if (r == null) {
                // 自愈重启钩回归守卫：轮级终态失败计数，统一走 [onCaptureStartOutcome]
                // seam（接线裁决序列由 AudioCaptureWiringSeamTest 钉死；判定本体在
                // [TerminalStartFailureDetector]，纯逻辑 JVM 可测可变异；readLoop 调用点
                // 属 Android 域 JVM 不可达，由审查+真机取证兜底）。barge-in cancel
                // 走协作取消不经过此分支（守卫四问 #3）；fire 即复位：自尽场景进程即死无
                // 副作用，冷却场景复位后重新攒证据（两场景统一口径）。
                if (onCaptureStartOutcome(terminalFailureGuard, startedOk = false)) {
                    PetLogger.e(
                        TAG,
                        "连续 $TERMINAL_START_FAILURE_THRESHOLD 轮采麦启动终态失败，触发自愈重启钩" +
                            "（疑似僵尸 input：killProcess 自尽 → START_STICKY 自动重拉）",
                    )
                    onTerminalStartFailure?.invoke()
                }
                return
            }
            var selfHeal = false
            try {
                isRunning = true
                // 自愈重启钩回归守卫：成功进录音态复位（同一 [onCaptureStartOutcome] seam，
                // AudioCaptureWiringSeamTest 接线锚）——真实录到音
                // 才算病愈，无关 stop/start 不清证据链。
                onCaptureStartOutcome(terminalFailureGuard, startedOk = true)
                PetLogger.i(TAG, "capture start, source=$usedSource, 16kHz/mono/Int16, block=3200B")
                // S57 漂移告警：pref 期望源 vs 实际 active source 不一致即 WARN（含两值）。
                // 诊断纪律（本仓 e5b6e11/593595f gotcha）：环境噪声结论必须先排除配置漂移——
                // 同一个 0.19 先查 pref 再怪房间。
                // 每次采麦启动至多一行（非逐块）；stop→start 重启复检属预期，不去重——
                // 「改 pref 重启 → WARN 可见」复现路径正依赖每次启动重查；
                // HOTFIX-2 自愈重建亦属同口径「重启复检」，不去重。
                if (isSourceDrift(sourceMode, usedSource)) {
                    PetLogger.w(
                        TAG,
                        "mic_source 漂移：pref mode=$sourceMode 期望 source=${expectedSourceFor(sourceMode)}，" +
                            "实际 active source=$usedSource（Settings 只写 pref，采集源构造时固化，改后需重启才生效）",
                    )
                }
                // 只推队列：攒满一块即 emit（DROP_OLDEST 下 tryEmit 恒 true，消费侧慢则旧块被挤掉
                // = 保最新音频，绝不在读循环里干活）
                pumpBlocks(
                    read = { buf, off, len -> r.read(buf, off, len) },
                    emit = { block ->
                        probeBlock(r, block) // 未 arm 时仅一次 volatile 读，零行为变化
                        // HOTFIX-2 回归守卫：形态 B 逐块全零判定、达阈置自愈，统一走
                        // [onZeroBlockOutcome] seam（接线由 AudioCaptureWiringSeamTest 钉死；
                        // 判定本体在 [ZeroBlockDetector]/[isAllZeroPcm16]，纯逻辑 JVM 可测
                        // 可变异；readLoop 调用点属 Android 域，审查+真机取证兜底）
                        if (onZeroBlockOutcome(zeroGuard, block)) {
                            selfHeal = true
                            selfHeals++
                            PetLogger.w(
                                TAG,
                                "连续 $ZERO_BLOCK_SELF_HEAL_THRESHOLD 块（3.0s）全零判定采麦流断流，" +
                                    "第 $selfHeals 次自愈：release 后延迟 ${CAPTURE_REBUILD_DELAY_MS}ms 重建",
                            )
                        }
                        // SoftwareAgc 回归守卫（软件 AGC 票 2026-10-17）：emit 单点恰一次原地
                        // 增益——顺序写死禁挪：probeBlock/zeroGuard 在前吃原始块（采集侧探针
                        // 与物理流层语义不变），增益后块才 tryEmit（VAD/ASR/KWS 全流统一吃
                        // 增益后音频，用户拍板）。挪到 tryEmit 之后=漏增益，挪到 probeBlock
                        // 之前=探针口径漂移。全零块在 [SoftwareAgc.process] 内直通（零交集）。
                        // （删此 process 调用 → SoftwareAgcTest 远场收敛组红；判定本体在 [SoftwareAgc]，
                        // 纯逻辑 JVM 可测可变异。集成点属 Android import 不可达域：守卫注释+
                        // critic 审查+真机取证，照 mic-source 票口径。）
                        val tick = agc?.process(block)
                        if (tick != null) {
                            if (tick.reportStatus) {
                                PetLogger.i(TAG, "agc nf=%.4f g=%.2f".format(tick.noiseFloor, tick.gain))
                            }
                            if (tick.weakChain) {
                                PetLogger.w(
                                    TAG,
                                    "agc 弱链路：nf=%.4f 时增益顶上限 g=%.2f——×8 仍不达目标响度，疑似链路衰减/距离过远".format(
                                        tick.noiseFloor,
                                        tick.gain,
                                    ),
                                )
                            }
                        }
                        // S40：producer 侧盖戳（nanoTime 单调域，与 VSM 接缝水位同域）
                        _stamped.tryEmit(StampedBlock(block, System.nanoTime()))
                    },
                    stopWhen = { selfHeal }, // 触发自愈即停泵退出，转 release→延迟→重建
                )
                // ROB-2：非取消退出（未取消即返回）= read 连续失败达阈值，留一行定层日志。
                // 该路径保持终态不重建（read 层持续报错与「正常返回但内容死零」不同病灶，
                // 重建无效），退出语义与既有 ROB-2 一致。
                if (!selfHeal) {
                    if (isActive) {
                        PetLogger.e(TAG, "read 连续失败达 $MAX_CONSECUTIVE_READ_FAILURES 次，采麦循环退出（AudioRecord 异常态）")
                    }
                    return
                }
            } finally {
                isRunning = false
                runCatching { r.stop() }
                r.release()
                record = null
                PetLogger.i(TAG, "capture stop")
            }
            // HOTFIX-2 形态 B 自愈续段（票据时序：release 先行 → delay → 重建）：上方 finally
            // 已释放旧流；delay 是唯一挂起点，job 被 cancel 即协作取消、穿此处干净终止
            //（record 已释放不泄漏），不得带病重建。
            delay(CAPTURE_REBUILD_DELAY_MS)
        }
    }

    /**
     * HOTFIX-2 形态 A 启动序列：至多 [MAX_START_ATTEMPTS] 次「构造 → startRecording →
     * 复核 recordingState」。ROB-1 守卫语义原样保留：无参 startRecording() 返回 void（带 int 返回值的
     * 重载需 MediaSyncEvent，板端 HAL 异常态下照样可能静默失败），用 recordingState
     * 复核：未进 RECORDSTATE_RECORDING 视为启动失败，照旧置 isRunning=true 会让
     * 上层以为在采（静默 no-op 流）。
     * 未进录音态：WARN（含 state 与第 N/3 次）→ stop/release → delay 重建重试；终态
     * （返回 null，均保持既有日志语义）：权限缺失 / 构造两源均不可用（buildRecord 已记
     * ERROR）/ N 次全败。重试窗被 cancel：唯一挂起点 delay 协作取消，此刻 record 已
     * release 置 null，无泄漏。
     */
    private suspend fun startRecordWithRetry(): AudioRecord? {
        var attempt = 1
        while (true) {
            val r = try {
                buildRecord()
            } catch (se: SecurityException) {
                // RECORD_AUDIO 被拒：一值定层日志（VAD 超时 maxLevel≈0.0001 同形态，坑 #19）
                PetLogger.e(TAG, "RECORD_AUDIO 权限缺失，采麦无法启动: ${se.message}")
                return null
            } ?: return null
            record = r
            r.startRecording()
            if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                return r
            }
            PetLogger.w(
                TAG,
                "startRecording 未进入录音态（state=${r.recordingState}），启动尝试 $attempt/$MAX_START_ATTEMPTS 失败，release 后延迟重建",
            )
            // 形态 A 票据时序（fork 同款）：release 先行给 AudioFlinger 回收窗口，再裁决
            // 重试/放弃——重试与终态两出口共用本次释放，成功路径由 readLoop finally 释放，
            // 各一次不重复（ce-review 20261020 #2：移植曾丢此三行，逐次失败泄漏 native input）。
            runCatching { r.stop() }
            r.release()
            record = null
            when (startRecordRetryOutcome(attempt)) {
                StartRecordRetry.GIVE_UP -> {
                    PetLogger.e(TAG, "startRecording 连续 $MAX_START_ATTEMPTS 次尝试均未进入录音态，本轮采麦不启动")
                    return null
                }
                StartRecordRetry.RETRY -> {}
            }
            attempt++
            delay(CAPTURE_REBUILD_DELAY_MS)
        }
    }

    // ---------------- S11-6 延迟取证探针（只探不修，总开关 AudioProbe.armed） ----------------

    /**
     * getTimestamp 支持性闩：null=未探测；false=设备不支持（首行打 NA(unsupported) 后
     * 不再调用，省 syscall）。仅读循环线程访问，无需 volatile。
     */
    private var timestampSupported: Boolean? = null

    /**
     * 采集侧探针：armed 且块过门时打一行 `cap rms=… lag=…`（tag AudioProbe）。
     * lag = 采集时刻（TIMEBASE_MONOTONIC 时间戳）→此刻入队前观察时刻 = 系统音频通路
     * （HAL/DSP）积压，与 KwsCore 侧 `feed rms=…` 行时间戳同域对照即得 app 中段积压。
     * 门限取 [EnergyGate.DEFAULT_GATE] 而非用户校准门：诊断用固定口径，feed 侧按真实
     * 动态门，两侧 rms 值原样落日志可互配。
     * runCatching 兜底：探针永不向上抛——只探不修票，探针本身不得成为新故障源。
     *
     * S52 历史注记：2026-09-18 AGC 压制取证时曾临时改 gate=0f 全量落行（需看
     * 0.001~0.015 float 域低值段包络，门控会滤掉病灶频段）；取证毕已回退默认门。
     * 再需低值段包络时临时改回 0f，随取证票立即收回（armed 全量 ≈10 行/s，不可带
     * 入量产/长期分支）。
     */
    private fun probeBlock(r: AudioRecord, block: ByteArray) {
        if (!AudioProbe.armed) return
        val rms = EnergyGate.rmsOf(block)
        if (!AudioProbe.shouldLog(rms, EnergyGate.DEFAULT_GATE)) return
        val lagText = runCatching { captureLagText(r) }
            .getOrElse { "NA(error:${it.message})" }
        PetLogger.i(PROBE_TAG, "cap rms=%.4f lag=%s".format(rms, lagText))
    }

    private fun captureLagText(r: AudioRecord): String {
        if (timestampSupported == false) return "NA" // 不支持已闩，不再尝试
        val ts = AudioTimestamp()
        // 时基取 TIMEBASE_MONOTONIC：与 System.nanoTime() 同域，差值即系统通路积压毫秒数
        // （票据草稿写的 TIMECAPTURE 在 SDK 不存在，真实常量仅 TIMEBASE_MONOTONIC/BOOTTIME；
        //   AudioRecord.getTimestamp 返回 int 状态码——boolean 是 AudioTrack 的签名，勿混）
        return if (r.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
            timestampSupported = true
            "${AudioProbe.lagMs(System.nanoTime(), ts.nanoTime)}ms"
        } else {
            // 首行 NA(unsupported) 并闩死：过门块远在流启动后，此时 false 视为设备不支持
            timestampSupported = false
            "NA(unsupported)"
        }
    }

    companion object {
        internal const val TAG = "AudioCapture" // top-level pumpBlocks 同文件取用（ROB-2 退出日志）

        /** 延迟取证探针日志 tag（grep 口径：`cap rms=` / `feed rms=` / `keyword hit:`）。 */
        private const val PROBE_TAG = "AudioProbe"

        /** 块缓冲容量：64 块 ≈ 6.4s 消费侧 stall 容忍。 */
        internal const val BLOCKS_BUFFER_CAPACITY = 64

        /**
         * 块广播流工厂：溢出丢最旧（DROP_OLDEST）。
         * 语义与 VadDetector pre-roll deque（容量 6，满 removeFirst）一致——保最新音频：
         * 消费侧 stall 恢复后立即接上「现在」，判停静音尾与 KWS 新音频不被 6.4s 陈块拖延。
         * 注意：DROP_OLDEST 下 tryEmit 恒 true，发射侧无法感知丢弃（无计数器，勿凭记忆补回）。
         */
        internal fun newBlocksFlow(): MutableSharedFlow<StampedBlock> =
            MutableSharedFlow(
                extraBufferCapacity = BLOCKS_BUFFER_CAPACITY,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        /**
         * pref 档位 → 期望采麦源常数（纯 Kotlin，零 Android import，JVM 可测）。
         * 常数表（上游 e5b6e11/593595f gotcha 带入）：0→7(VOICE_COMMUNICATION) /
         * 1→6(VOICE_RECOGNITION) / 2→9(UNPROCESSED)，未知档位归 7（与旧 when-else 同语义）。
         * [buildRecord] 首选源直接走本函数——映射单点维护，判定与构造永不分叉。
         */
        internal fun expectedSourceFor(sourceMode: Int): Int = when (sourceMode) {
            1 -> 6   // VOICE_RECOGNITION
            2 -> 9   // UNPROCESSED
            else -> 7 // VOICE_COMMUNICATION（含默认档）
        }

        /**
         * 漂移判定（纯 Kotlin，JVM 可测）：pref 档位解析出的期望源 ≠ 实际 active source
         * （[usedSource]）→ true，采麦启动处打 WARN（含两值）。
         *
         * S57 注记（本仓实况）：1723d7b 的采麦源只读锁在本仓仍然生效（SettingsScreen 显示
         * 「已锁定」，2026-09-21 判停事故定案）；本移植只搬入纯函数诊断面，设置页不做任何
         * 解锁改动，变更走 adb 改 pref + 重启 App。判定覆盖「构造时固化的 pref 档位」与
         * 活性源的分叉（如初始化失败静默 fallback DEFAULT）；Settings 改 pref 不重建采集源
         * （构造时固化，改后需重启才生效），重启后新实例按新 pref 构造，以本告警界定。
         *
         * S57 回归守卫：AudioCaptureSourceDriftTest 漂移触发/一致不告警两组锚
         * （反转本判定 → 漂移组红）。
         */
        internal fun isSourceDrift(prefMode: Int, activeSource: Int): Boolean =
            expectedSourceFor(prefMode) != activeSource
    }
}

/**
 * 一次性占位门闩（纯逻辑，JVM 可测）：把「检查 + 占位」收敛为一次 synchronized，
 * 消灭 check-then-act 竞态窗口。首个 [tryAcquire] 成功者拿到所有权令牌并负责干活，
 * 并发/重复调用者拿到 null 直接放弃；收尾、失败或停止时 [release] 持令牌放行下一轮。
 *
 * 所有权令牌（backlog #11，critic 处方）：令牌递增长度 Long、每次 acquire 必不同；
 * [release] 仅当令牌等于当前持有者才放，过期令牌 release 自动失效（no-op）。
 * 这钉住了「stop→start2 之后旧 job 的 finally 才执行」的理论窗：旧 job 的过期
 * release 不会错放新 start 持有的闩，并发 start3 无可乘之机。
 *
 * 用于 BLOCKED.md backlog #8 两处既有竞态：
 * - [AudioCapture.start]：isRunning 检查与 job 置位间的双调窗口会起双读循环抢麦（#8b）
 * - VoiceForegroundService KWS init：重复 onStartCommand 起双 init 线程，后到收尾者
 *   覆盖 engine 引用、先到者引擎永不 release（#8a）
 */
internal class OnceGate {
    private val lock = Any()
    private var holder: Long = 0L // 0 = 未被持有；>0 = 当前持有者令牌
    private var nextToken: Long = 0L

    /** 未被持有则占位并返回所有权令牌（调用方负责干活并留存令牌）；已持有返回 null。 */
    fun tryAcquire(): Long? = synchronized(lock) {
        if (holder != 0L) return null
        holder = ++nextToken
        holder
    }

    /** 令牌匹配当前持有者才释放；过期/重复 release 自动失效（no-op）。 */
    fun release(token: Long) = synchronized(lock) {
        if (holder == token) holder = 0L
    }
}

/**
 * 攒块泵（纯逻辑，JVM 可测）：read 攒满一个 3200B 块即 emit，非正返回跳过下轮再读。
 *
 * 判活用协程体自带的 [CoroutineScope.isActive]（Structured Concurrency），**不读外部
 * Job 引用**——旧写法 `while (job?.isActive == true)` 存在启动竞态：launch 返回前协程体
 * 即可开跑（如 UNDISPATCHED / 调度抢占），此时 job 字段尚未赋值读到 null，循环一次不进、
 * 采麦静默不启动（critic-G S4 P3）。
 *
 * ROB-2：read 持续返回非正（AudioRecord 卡错误态/启动失败）不得零退避满速自旋——
 * 连续失败达 [MAX_CONSECUTIVE_READ_FAILURES] 即正常返回（readLoop 的 finally 据此释放
 * AudioRecord 与门闩），重试间 yield 让出。偶发失败（<阈值内恢复）照常续读。
 */
// ---------------- HOTFIX-2：USB 麦采麦可靠性双形态修复（纯逻辑判定面，零 Android import，JVM 可测） ----------------

/** HOTFIX-2 形态 B：连续全零块自愈阈值。30 块 × 100ms（3200B@16kHz/mono/Int16）= 3.0s，
 *  与票据「29 块不触发/30 块触发」锚一致（AudioCaptureZeroBlockDetectorTest 钉死）。 */
internal const val ZERO_BLOCK_SELF_HEAL_THRESHOLD = 30

/** HOTFIX-2：启动重试与自愈重建共用的延迟（ms）——给 AudioFlinger 回收线程的窗口（板上实证值）。 */
internal const val CAPTURE_REBUILD_DELAY_MS = 250L

/** HOTFIX-2 形态 A：启动重试至多尝试次数（板上实证平台瞬态，重试可愈）。 */
internal const val MAX_START_ATTEMPTS = 3

/**
 * 精确全零判定（纯逻辑）：块内全 00 字节 ⇔ PCM16 全部样本为数字零（任一非 00 字节即
 * 非零样本：+1=0x0001、-1=0xFFFF）。板上实证：USB 麦死流吐精确全零块（0.0000=数字零，
 * 板载麦真实底噪 0.0068）。空数组按全零（全称量词恒真），测试钉死该口径。
 */
internal fun isAllZeroPcm16(block: ByteArray): Boolean {
    for (b in block) if (b.toInt() != 0) return false
    return true
}

/**
 * HOTFIX-2 形态 A 启动重试裁决（纯逻辑，JVM 可测）：attempt 未达上限继续重试，
 * 达上限放弃（GIVE_UP 由调用方记终态 ERROR 并返回 null）。
 * 接线锚：AudioCaptureWiringSeamTest（1→RETRY / 2→RETRY / 3→GIVE_UP + 常数钉死）。
 */
internal enum class StartRecordRetry { RETRY, GIVE_UP }

internal fun startRecordRetryOutcome(attempt: Int, maxAttempts: Int = MAX_START_ATTEMPTS): StartRecordRetry =
    if (attempt >= maxAttempts) StartRecordRetry.GIVE_UP else StartRecordRetry.RETRY

/**
 * HOTFIX-2 回归守卫 seam（纯逻辑，JVM 可测）：readLoop 启动序列的统一裁决点——
 * 成功进录音态（startedOk=true）复位终态失败计数（真实录到音才算病愈）；
 * 终态失败（startedOk=false）攒证据，连续达 [TERMINAL_START_FAILURE_THRESHOLD] 轮
 * 返回 true 恰一次（fire 即复位，语义见 [TerminalStartFailureDetector]）。
 * 接线锚：AudioCaptureWiringSeamTest（败×3→fire / 败败成→不 fire 且计数清 / 成→复位）。
 * readLoop 内两处调用点属 Android 域 JVM 不可达，由审查+真机取证兜底。
 */
internal fun onCaptureStartOutcome(guard: TerminalStartFailureDetector, startedOk: Boolean): Boolean {
    if (startedOk) {
        guard.reset()
        return false
    }
    return guard.onTerminalFailure()
}

/**
 * HOTFIX-2 回归守卫 seam（纯逻辑，JVM 可测）：readLoop 逐块喂的全零自愈裁决点——
 * 连续 [ZERO_BLOCK_SELF_HEAL_THRESHOLD] 块全零返回 true 恰一次，非零块归零重计
 * （语义见 [ZeroBlockDetector]）。接线锚：AudioCaptureWiringSeamTest
 * （29 块不触发 / 第 30 块触发 / 非零块打断重计）。
 */
internal fun onZeroBlockOutcome(guard: ZeroBlockDetector, block: ByteArray): Boolean =
    guard.onBlock(isAllZeroPcm16(block))

/**
 * HOTFIX-2 形态 B 断流判定器（纯逻辑，readLoop 逐块喂）：连续 [threshold] 块全零判
 * 「采麦流死了」，[onBlock] 返回 true 恰一次并内部归零——自愈重建后新流须重新计满阈值
 * 才再触发；任何非零块把连续计数归零（偶发数字静音段不误愈）。
 */
internal class ZeroBlockDetector(private val threshold: Int = ZERO_BLOCK_SELF_HEAL_THRESHOLD) {
    private var consecutive = 0

    /** 喂入一块：[allZero]=该块精确全零（[isAllZeroPcm16] 口径）。返回 true=连续达阈，应触发自愈。 */
    fun onBlock(allZero: Boolean): Boolean {
        if (!allZero) {
            consecutive = 0
            return false
        }
        consecutive++
        if (consecutive >= threshold) {
            consecutive = 0
            return true
        }
        return false
    }
}

// ---------------- 自愈重启钩：僵尸 input 自动化兜底（诊断报告 §7.1.3；纯逻辑判定面，零 Android import，JVM 可测） ----------------

/** 自愈重启钩阈值：连续 N 轮采麦启动终态失败即 fire（一轮=一次 readLoop 启动序列终态失败，
 *  内含 [MAX_START_ATTEMPTS]×[CAPTURE_REBUILD_DELAY_MS] 轮内重试——形态 A 平台瞬态由轮内
 *  重试兜住（板上实证 26 块全零即触发），全败才计 1 轮；连续 3 轮全败才是僵尸/长病态信号，
 *  实证僵尸窗 start 必拒、僵尸 input 已被进程死亡清账）。
 *  （AudioCaptureTerminalStartFailureDetectorTest 常数锚钉死，改值即红。） */
internal const val TERMINAL_START_FAILURE_THRESHOLD = 3

/** 自愈重启钩冷却窗（建议 5min，双职能）：①防自杀循环（冷却内 fire 只记 ERROR 不自尽）；
 *  ②保服务存活 ≥60s 使 STICKY 重启退避恒复位 1s（AOSP 7.1.3 线：装配层冷却判定走 pref 戳）。
 *  （AudioCaptureTerminalStartFailureDetectorTest 常数锚钉死 5min 且 ≥60s，改值即红。） */
internal const val SELF_HEAL_RESTART_COOLDOWN_MS = 5 * 60 * 1000L

/**
 * 自愈重启钩判定器（诊断报告 §7.1.3，纯逻辑，零 Android import，JVM 可测；样式照
 * [ZeroBlockDetector]）：连续 [threshold] 轮「采麦启动终态失败」判「僵尸 input/长病态」，
 * [onTerminalFailure] 返回 true 恰一次并内部归零（**fire 即复位**——自尽场景进程即死无
 * 副作用；冷却场景复位后重新攒证据，两场景统一口径，不存在「冷却内脏计数」问题）。
 * 成功进录音态由调用方 [reset] 清零（真实录到音才算病愈；无关 stop/start 不清证据链）。
 *
 * 计数点与承载：readLoop 启动终态失败分支计数、成功进录音态处复位（AudioCapture 内
 * 回归守卫注释锚定）；判定器实例挂 AudioCapture 字段（终态失败终结 readLoop，实例级
 * 才跨轮攒得上）。seam 默认 null 时只计数不外呼，零行为变化（既有测试零改动）。
 */
internal class TerminalStartFailureDetector(
    private val threshold: Int = TERMINAL_START_FAILURE_THRESHOLD,
) {
    private var consecutive = 0

    /** 喂入一轮启动终态失败：返回 true=连续达阈，应 fire 自愈重启钩（内部即复位重新攒证据）。 */
    fun onTerminalFailure(): Boolean {
        consecutive++
        if (consecutive >= threshold) {
            consecutive = 0
            return true
        }
        return false
    }

    /** 成功进录音态复位（双复位语义另一支）：证据链清零重新攒。 */
    fun reset() {
        consecutive = 0
    }
}

internal const val MAX_CONSECUTIVE_READ_FAILURES = 50

internal suspend fun CoroutineScope.pumpBlocks(
    read: suspend (buf: ByteArray, offset: Int, length: Int) -> Int,
    emit: (ByteArray) -> Unit,
    stopWhen: () -> Boolean = { false },
) {
    val buf = ByteArray(VadDetector.BLOCK_BYTES)
    var filled = 0
    var failures = 0
    while (isActive) {
        if (stopWhen()) return // HOTFIX-2：自愈等主动停泵出口（默认恒 false，ROB-2 失败计数语义零改动）
        val n = read(buf, filled, buf.size - filled)
        if (n <= 0) { // ERROR_* 或 0：不推队列，重试间让出；连续失败达阈值退出
            failures++
            if (failures >= MAX_CONSECUTIVE_READ_FAILURES) return
            yield()
            continue
        }
        failures = 0
        filled += n
        if (filled == buf.size) {
            filled = 0
            emit(buf.copyOf())
        }
    }
}

// ---------------- 软件自动增益 SoftwareAgc（2026-10-17 立票：讯飞逆向等价物；纯逻辑判定面，零 Android import，JVM 可测） ----------------
// 依据：`.scratch/agc-software/TICKET.md` + SPEC（17.242 MIC 链电平实测：底噪 0.004~0.014 /
// 近场 0.05~0.11 / 远场 0.01~0.04，VAD 固定门限 0.05；同板讯飞远场正常=native speex AGC）。
// 语义：采麦读循环 emit 单点对每块原地增益，VAD/ASR/KWS 全流统一吃增益后音频（用户拍板）；
// VAD 0.05 门限与 S18/S19 校准体系零改动（校准天然工作在增益后域）。出处在类 KDoc。

/** AGC 装配级总开关：板验对照/回退=改这一个值回原始链，不改行为代码（SPEC 决策 #9）。 */
internal const val SOFTWARE_AGC_ENABLED = true

/** maxLevel 域目标响度：近场实测 0.05~0.11 中值偏上，VAD 门限 0.05 之上 +60% 余量。 */
internal const val AGC_TARGET_LEVEL = 0.08f

/** 增益上下限（只放大不衰减）：×8 下底噪 0.004→0.032 仍低于 VAD 门限 0.05（不抬误触），远场 0.02→0.16 越门限。 */
internal const val AGC_MAX_GAIN = 8.0f
internal const val AGC_MIN_GAIN = 1.0f

/** 噪声底 EMA 系数（~20 块收敛）+ 守门因子（仅块响度 ≤ nf×3 时更新——防语音把底噪抬走致增益塌缩）。 */
internal const val AGC_NOISE_FLOOR_ALPHA = 0.05f
internal const val AGC_NOISE_GUARD_FACTOR = 3.0f

/** 增益双侧 EMA 系数 + 相邻块增益变化比上限（防爆音与泵浦感）。 */
internal const val AGC_GAIN_SMOOTH_ALPHA = 0.3f
internal const val AGC_GAIN_MAX_STEP_RATIO = 1.5f

/** 弱链路告警：连续 50 块（5s）语音级且 raw 增益 >×8 被钳 → fire 恰一次，冷却 5min（内存态，不落盘）。 */
internal const val AGC_WEAK_CHAIN_BLOCKS = 50
internal const val AGC_WEAK_CHAIN_COOLDOWN_MS = 5 * 60 * 1000L

/** 状态行周期：每 300 块（30s）reportStatus 恰一次（调用方打 INFO）。 */
internal const val AGC_STATUS_INTERVAL_BLOCKS = 300

/**
 * EnergyGate.rmsOf（float /32768 域）→ maxLevel 域换算系数。
 * maxLevel 域 = min(1.0, RMS_int16/8000)（VadDetector.levelOf/RMS_REF=8000 口径，金线
 * `vad maxLevel`/`maxLevel` 同域可比）；增益是比值、内部自洽，但日志与门限论证统一 maxLevel 域。
 */
internal const val AGC_RMS_FLOAT_TO_MAXLEVEL = 32768f / 8000f

/** [SoftwareAgc.process] 单块结果：tick 信息（调用方按 [AgcTick.reportStatus]/[AgcTick.weakChain] 打日志）。 */
internal data class AgcTick(
    val gain: Float, // 本块实际应用增益（直通=1.0）
    val noiseFloor: Float, // 当前 EMA 噪声底估计（maxLevel 域）
    val reportStatus: Boolean, // 周期状态行到点（调用方打 INFO）
    val weakChain: Boolean, // 链路过弱告警 fire（调用方打 WARN；fire 即内部冷却）
)

/**
 * 软件自动增益判定器（讯飞逆向等价物，纯逻辑，零 Android import，JVM 可测；样式照
 * [ZeroBlockDetector]/[TerminalStartFailureDetector] 家族）。
 *
 * 每非全零块：maxLevel 域响度 → EMA 噪声底（守门：语音块不抬底）→ 目标响度归一增益
 * （钳 [1.0, ×8]）→ 双侧 EMA+步长钳平滑 → PCM16 小端饱和回写（禁溢出翻转）。全零块/
 * 空/短块/enabled=false 直通。算法逐条定案见 `.scratch/agc-software/TICKET.md`（builder
 * 勿改语义）；行为守卫 SoftwareAgcTest（M1-M3 变异锚在下方各标注行，M4 在 process
 * 全零直通早退分支）。
 *
 * 生命周期（守卫四问 #1）：实例挂 AudioCapture 字段跨 readLoop/自愈重建保留——EMA 噪声底
 * 是「房间声学属性」非流证据，stop/start 不复位，进程死随进程湮灭（与 zeroGuard readLoop
 * 局部变量=流死证据随流重攒的差异）。OnceGate 保证任一时刻至多一条活跃读循环 = 单线程
 * 访问，无锁（守卫四问 #3：纯同步计算零挂起点，不引入 delay/yield，cancel 不经过它）。
 * 数学边界（守卫四问 #2）：max(level,1e-6) 除零防护、全路径有界浮点运算，无 NaN 永不抛
 * ——readLoop emit 内抛异常会杀读循环，本判定器不得成为新故障源。
 */
internal class SoftwareAgc(private val enabled: Boolean = true) {
    private var noiseFloor = 0f
    private var nfInitialized = false
    private var gainEma = 1.0f
    private var gainPrev = 1.0f
    private var weakStreak = 0
    private var weakCooldownUntilMs = 0L
    private var sinceStatus = 0

    /**
     * 原地增益一块，返回本次 tick 信息。全零块（[isAllZeroPcm16] 精确全零口径）/空/短块
     * 直通：不改块、不更新任何状态、增益按 1.0 计（保 ZeroBlockDetector「精确全零」判定
     * 语义与 HOTFIX-2 自愈口径零交集）；enabled=false 直通返回当前状态快照不改块。
     */
    fun process(block: ByteArray): AgcTick {
        if (!enabled) return AgcTick(1f, noiseFloor, false, false)
        // SoftwareAgc 回归守卫（M4）：全零直通（删此 → SoftwareAgcTest 全零直通组红：
        // nf 被拖走+增益≠1）。
        if (block.size < 2 || isAllZeroPcm16(block)) {
            return AgcTick(1f, noiseFloor, false, false)
        }
        val level = maxLevelOf(block)
        // 噪声底：初值=首个非全零块响度；仅 ≤ nf×3 的块参与 EMA（守门防语音抬底）。
        if (!nfInitialized) {
            noiseFloor = level
            nfInitialized = true
        } else if (level <= noiseFloor * AGC_NOISE_GUARD_FACTOR) {
            noiseFloor += AGC_NOISE_FLOOR_ALPHA * (level - noiseFloor)
        }
        val gRaw = AGC_TARGET_LEVEL / max(level, 1e-6f) // 除零防护
        // SoftwareAgc 回归守卫（M2）：上限钳制衰减面（删此 → SoftwareAgcTest 超大块
        // 衰减组红：0.9 级被衰减到 0.08。下方步长钳+coerceAtMost 仍钉应用增益 ≤8，
        // 上限面无独立红面——critic M2b 实证，冗余兜底性质）。
        val gTarget = gRaw.coerceIn(AGC_MIN_GAIN, AGC_MAX_GAIN)
        // SoftwareAgc 回归守卫（M3）：双侧 EMA + 相邻步长钳（删此 → SoftwareAgcTest 平滑
        // 两组红：首块增益直跳 ×4/×8，越 1.5 步长界）。
        gainEma += AGC_GAIN_SMOOTH_ALPHA * (gTarget - gainEma)
        val g = gainEma
            .coerceIn(gainPrev / AGC_GAIN_MAX_STEP_RATIO, gainPrev * AGC_GAIN_MAX_STEP_RATIO)
            .coerceAtMost(AGC_MAX_GAIN) // 上限仍 ≤ AGC_MAX_GAIN（EMA 有界不变式外的显式钉死）
        gainPrev = g
        // SoftwareAgc 回归守卫（M1）：增益应用本体（删此 → SoftwareAgcTest 远场收敛组红：
        // 0.02 级输出停在门限下半区）。
        applyGainPcm16(block, g)
        // 弱链路告警：语音级且 raw 增益顶限（×8 仍不达目标响度）连续攒块；fire 恰一次+
        // 冷却 5min（内存态）；条件中断（安静块/增益回落）即清零重攒。
        val speechLevel = level >= noiseFloor * AGC_NOISE_GUARD_FACTOR
        weakStreak = if (speechLevel && gRaw > AGC_MAX_GAIN) weakStreak + 1 else 0
        var weakFire = false
        val now = System.currentTimeMillis()
        if (weakStreak >= AGC_WEAK_CHAIN_BLOCKS && now >= weakCooldownUntilMs) {
            weakStreak = 0
            weakCooldownUntilMs = now + AGC_WEAK_CHAIN_COOLDOWN_MS
            weakFire = true
        }
        // 状态行：每 300 块（30s）恰一次。
        sinceStatus++
        var status = false
        if (sinceStatus >= AGC_STATUS_INTERVAL_BLOCKS) {
            sinceStatus = 0
            status = true
        }
        return AgcTick(g, noiseFloor, status, weakFire)
    }

    /** maxLevel 域响度：min(1, EnergyGate.rmsOf × 换算系数)，与 VadDetector.levelOf 同口径。 */
    private fun maxLevelOf(block: ByteArray): Float =
        minOf(1f, EnergyGate.rmsOf(block) * AGC_RMS_FLOAT_TO_MAXLEVEL)

    /**
     * PCM16 小端原地饱和回写：sample×g 钳 [-32768, 32767] 后写回（Int 域钳制，禁溢出翻转）。
     * 奇数字节块（产线恒 3200B 定长，仅理论边界）处理完整样本对、尾字节原样。
     */
    private fun applyGainPcm16(block: ByteArray, g: Float) {
        var i = 0
        while (i + 1 < block.size) {
            val lo = block[i].toInt() and 0xFF
            val hi = block[i + 1].toInt() shl 8
            val s = (hi or lo).toShort().toInt()
            val v = (s * g).toInt().coerceIn(-32768, 32767)
            block[i] = (v and 0xFF).toByte()
            block[i + 1] = ((v shr 8) and 0xFF).toByte()
            i += 2
        }
    }
}
