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
        // 源候选：mode 决定首选源（S57 纯函数解析，映射单点维护——判定与构造不分叉），
        // 初始化失败降级 DEFAULT（坑 #35 注释）。
        val preferred = when (expectedSourceFor(sourceMode)) {
            6 -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            9 -> MediaRecorder.AudioSource.UNPROCESSED
            else -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        }
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
                return
            }
            var selfHeal = false
            try {
                isRunning = true
                PetLogger.i(TAG, "capture start, source=$usedSource, 16kHz/mono/Int16, block=3200B")
                // 只推队列：攒满一块即 emit（DROP_OLDEST 下 tryEmit 恒 true，消费侧慢则旧块被挤掉
                // = 保最新音频，绝不在读循环里干活）
                pumpBlocks(
                    read = { buf, off, len -> r.read(buf, off, len) },
                    emit = { block ->
                        probeBlock(r, block) // 未 arm 时仅一次 volatile 读，零行为变化
                        // HOTFIX-2 回归守卫：形态 B 逐块全零判定、达阈置自愈（删此 →
                        // AudioCaptureZeroBlockDetectorTest 触发组红；判定本体在
                        // [ZeroBlockDetector]/[isAllZeroPcm16]，纯逻辑 JVM 可测可变异）
                        if (zeroGuard.onBlock(isAllZeroPcm16(block))) {
                            selfHeal = true
                            selfHeals++
                            PetLogger.w(
                                TAG,
                                "连续 $ZERO_BLOCK_SELF_HEAL_THRESHOLD 块（3.0s）全零判定采麦流断流，" +
                                    "第 $selfHeals 次自愈：release 后延迟 ${CAPTURE_REBUILD_DELAY_MS}ms 重建",
                            )
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
     * 上层以为在采（静默 no-op 流）。失败即失败返回，finally 释放 record 与门闩。
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
            if (attempt >= MAX_START_ATTEMPTS) {
                PetLogger.e(TAG, "startRecording 连续 $MAX_START_ATTEMPTS 次尝试均未进入录音态，本轮采麦不启动")
                return null
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
         * S57 裁剪注记（有意偏离上游 1723d7b）：不把设置页麦源改只读——v22 采麦源三档是
         * 诊断基建，只告警不禁改。判定覆盖「构造时固化的 pref 档位」与活性源的分叉
         * （如初始化失败静默 fallback DEFAULT）；Settings 改 pref 不重建采集源（构造时固化，
         * 改后需重启才生效），重启后新实例按新 pref 构造，两者以本告警+注释界定。
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
