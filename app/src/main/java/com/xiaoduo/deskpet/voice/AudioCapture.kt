package com.xiaoduo.deskpet.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.xiaoduo.deskpet.util.PetLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
class AudioCapture {

    /** 100ms PCM 块广播流（缓冲 64 块 ≈ 6.4s，满则丢最旧——与 VAD pre-roll deque 同策，保最新音频）。 */
    val blocks: SharedFlow<ByteArray> get() = _blocks

    private val _blocks = newBlocksFlow()

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

    fun start(scope: CoroutineScope) {
        // 旧写法 if (isRunning) return 的检查与 job 置位间有竞态窗口：双调各过检查
        // 起双读循环抢麦（critic-#4 P3，既有问题）。门闩把检查+占位收敛成一次原子操作。
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

    fun stop() {
        job?.cancel()
        job = null
        // 协程可能未及开跑即被取消（上面 finally 不执行），此处兜底释放；双放幂等
        gateToken?.let { startGate.release(it) }
        gateToken = null
    }

    @SuppressLint("MissingPermission") // 权限由调用方（设置页）真实申请路径保证
    private fun buildRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            VadDetector.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = maxOf(minBuf, VadDetector.BLOCK_BYTES * 8)
        // VOICE_COMMUNICATION 优先（硬件 AEC）；初始化失败降级 DEFAULT（坑 #35 注释）
        for (source in listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.DEFAULT,
        )) {
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
        val r = try {
            buildRecord()
        } catch (se: SecurityException) {
            // RECORD_AUDIO 被拒：一值定层日志（VAD 超时 maxLevel≈0.0001 同形态，坑 #19）
            PetLogger.e(TAG, "RECORD_AUDIO 权限缺失，采麦无法启动: ${se.message}")
            return
        } ?: return
        record = r
        try {
            r.startRecording()
            isRunning = true
            PetLogger.i(TAG, "capture start, source=$usedSource, 16kHz/mono/Int16, block=3200B")
            // 只推队列：攒满一块即 emit（DROP_OLDEST 下 tryEmit 恒 true，消费侧慢则旧块被挤掉
            // = 保最新音频，绝不在读循环里干活）
            pumpBlocks(
                read = { buf, off, len -> r.read(buf, off, len) },
                emit = { block -> _blocks.tryEmit(block) },
            )
        } finally {
            isRunning = false
            runCatching { r.stop() }
            r.release()
            record = null
            PetLogger.i(TAG, "capture stop")
        }
    }

    companion object {
        private const val TAG = "AudioCapture"

        /** 块缓冲容量：64 块 ≈ 6.4s 消费侧 stall 容忍。 */
        internal const val BLOCKS_BUFFER_CAPACITY = 64

        /**
         * 块广播流工厂：溢出丢最旧（DROP_OLDEST）。
         * 语义与 VadDetector pre-roll deque（容量 6，满 removeFirst）一致——保最新音频：
         * 消费侧 stall 恢复后立即接上「现在」，判停静音尾与 KWS 新音频不被 6.4s 陈块拖延。
         * 注意：DROP_OLDEST 下 tryEmit 恒 true，发射侧无法感知丢弃（无计数器，勿凭记忆补回）。
         */
        internal fun newBlocksFlow(): MutableSharedFlow<ByteArray> =
            MutableSharedFlow(
                extraBufferCapacity = BLOCKS_BUFFER_CAPACITY,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
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
 */
internal suspend fun CoroutineScope.pumpBlocks(
    read: suspend (buf: ByteArray, offset: Int, length: Int) -> Int,
    emit: (ByteArray) -> Unit,
) {
    val buf = ByteArray(VadDetector.BLOCK_BYTES)
    var filled = 0
    while (isActive) {
        val n = read(buf, filled, buf.size - filled)
        if (n <= 0) continue // ERROR_* 或 0：不推队列，下轮再读
        filled += n
        if (filled == buf.size) {
            filled = 0
            emit(buf.copyOf())
        }
    }
}
