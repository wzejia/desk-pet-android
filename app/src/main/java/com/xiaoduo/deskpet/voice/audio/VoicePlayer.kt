package com.xiaoduo.deskpet.voice.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.xiaoduo.deskpet.App
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.voice.RevealScheduler
import java.io.IOException

/**
 * TTS 播放（Android IO 层，知识包 §3.2/§3.3）：
 * - AudioTrack 24kHz/Int16/mono MODE_STREAM，**阻塞写 = 按播放速度天然背压**
 * - 收尾等播放头追平写入帧数 + 0.05s 再 stop/release（§3.3 latency + 0.05s 收尾）
 * - 播放失败抛 IOException 由上层降级纯文字 reveal（坑 #36：静默兜底降级）
 * - S7-e barge-in：[stop] 置打断标志 + 停硬件，写循环与收尾等待每轮查标志，
 *   置位即抛 IOException("stopped") 走上层既有 catch 降级路径；释放仍在 finally
 *
 * 两种用法：
 * - **单段** [playBlocking]：建 track→播→收尾→释放，一次性（唤醒应答 wozai 用）。
 * - **流式** [play]/[finish]：跨多句复用同一 AudioTrack（首次 play 建+play，后续 play 续写，
 *   finish 收尾释放），消除逐句重建 track 的 ~120ms 句间空隙。单句播放异常可 [reset] 后下句重建。
 */
class VoicePlayer {

    /** 打断标志（@Volatile：stop 由 UI/会话线程调，写循环在 IO 线程查）。 */
    @Volatile
    private var stopped = false

    /** S24 App 内播报音量（0..1，默认 1=跟随系统）：治板子满格音量致麦克风削波
     *  （2026-09-02 实证：系统音量 15→9 后 inRms 0.8~1.3 削波区降到 0.4 线性区，
     *  播报期唤醒 0/2min → 7/90s）。buildTrack 统一施加，播放/流式双路径覆盖；
     *  不动系统音量——密集架自带播报（同媒体流）保持满格。
     */
    var volume: Float = 1f

    /** S23 参考分接头：已播 PCM 按播放头位置推给回声消除器（NlmsEchoCanceller）作
     *  NLMS 参考。null = 无订阅零开销。回调在配速线程触发，实现方须轻量（memcpy 级）。 */
    var referenceListener: ((pcm: ByteArray, sampleRate: Int) -> Unit)? = null

    // ---- S23 参考配速器（pacer）----
    // 教训（2026-09-02 板子遥测 refBacklog 103万->601万 实证）：参考必须按「播放节奏」
    // 推送而非「写入节奏」——AudioTrack 应用缓冲 1s + 长句阻塞写会让写入领先真实出声
    // 达秒级，新鲜度窗 400ms 外 93% 麦块直通、偶处理的参考又是被环覆盖过的陈旧数据，
    // NLMS 全程减垃圾信号=零效果。配速器按 playbackHeadPosition 每 10ms 放对应 PCM，
    // 参考与声学回声严格同轴（残余管道延迟由 NLMS 滤波窗吸收）。
    private val refLock = Object()
    private val refPending = ArrayDeque<ByteArray>()
    private var refPendingOff = 0

    @Volatile
    private var refPacerRunning = false
    private var refPacer: Thread? = null

    /** 在途 AudioTrack 引用（stop 需直接停硬件加速打断，不等下一轮写循环）。 */
    @Volatile
    private var activeTrack: AudioTrack? = null

    /** 流式 track（跨句复用）+ 累计已写帧数（收尾等播完用）。null = 未开播/已释放。 */
    @Volatile
    private var streamTrack: AudioTrack? = null
    private var streamWrittenFrames = 0L

    /**
     * CONC-3：流式生命周期锁。streamTrack/streamWrittenFrames（非 volatile Long）由 IO
     * 线程 play()/finish() 写，stop()/reset() 可来自 UI/会话线程——无锁并发下：双
     * AudioTrack（泄漏一个）、写已 release 轨道抛 ISE、drainUntil 空转 wall-clock 死线、
     * 32 位域 Long 撕裂。建轨/记账/释放三类转换点收进同一把锁；阻塞写（writeAll/drain）
     * 在锁外（不背锁做慢 IO），写完回锁内带身份复核记账（轨已换主则弃记）。
     */
    private val streamLock = Any()

    /** 打断在途播放（幂等；未在播仅置标志——开播即查，先于 cancel 的 stop 也生效）。 */
    fun stop() {
        stopped = true
        runCatching { activeTrack?.stop() }
    }

    /** 同步阻塞播一段裸 PCM（调用方切 IO 线程/协程）。实例单次使用（逐应答各新建）。 */
    @Throws(IOException::class)
    fun playBlocking(pcm: ByteArray) {
        val minBuf = AudioTrack.getMinBufferSize(
            SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = buildTrack(maxOf(minBuf, pcm.size.coerceAtMost(MAX_BLOCK_BUF_BYTES)))
        activeTrack = track
        try {
            track.play()
            PetLogger.i(TAG, "playback-start blocking bytes=${pcm.size}") // §5.4 播报开始锚
            writeAll(track, pcm)
            drainUntil(track, (pcm.size / 2).toLong())
            Thread.sleep(TAIL_MS)
            PetLogger.d(TAG, "played ${pcm.size}B (${pcm.size / 2} frames @24k)")
        } finally {
            activeTrack = null
            runCatching { track.stop() }
            track.release()
        }
    }

    // ---------------- 流式（跨句复用单 AudioTrack）----------------

    /** 流式写一段 PCM：首次自动建 track 并 play，后续续写同一 track（跨句无缝）。
     *  阻塞背压（缓冲满则 write 阻塞）。stopped 或写失败抛 IOException。 */
    @Throws(IOException::class)
    fun play(pcm: ByteArray) {
        val track: AudioTrack = synchronized(streamLock) {
            streamTrack ?: run {
                if (stopped) throw IOException("stopped")
                val minBuf = AudioTrack.getMinBufferSize(
                    SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                )
                val t = buildTrack(maxOf(minBuf, STREAM_BUF_BYTES))
                streamTrack = t
                activeTrack = t
                streamWrittenFrames = 0
                t.play()
                PetLogger.i(TAG, "playback-start stream bytes=${pcm.size}") // §5.4 播报开始锚（首块）
                startRefPacer(t)
                t
            }
        }
        writeAll(track, pcm)
        synchronized(streamLock) {
            if (streamTrack === track) streamWrittenFrames += pcm.size / 2
        }
    }

    /** 收尾：等所有已写帧播完 + 0.05s 余量再释放（整轮 TTS 结束调）。幂等（无 track 即返回）。 */
    @Throws(IOException::class)
    fun finish() {
        val (track, written) = synchronized(streamLock) {
            val t = streamTrack ?: return
            t to streamWrittenFrames
        }
        try {
            drainUntil(track, written)
            Thread.sleep(TAIL_MS)
            PetLogger.d(TAG, "stream played $written frames @24k")
        } finally {
            releaseStream()
        }
    }

    /** 强制释放流式 track（播放异常后下句重建 / finally 兜底 / barge-in 收尾）。幂等。 */
    fun reset() = releaseStream()

    private fun releaseStream() {
        synchronized(streamLock) {
            stopRefPacer()
            val track = streamTrack ?: return
            streamTrack = null
            if (activeTrack === track) activeTrack = null
            runCatching { track.stop() }
            track.release()
            streamWrittenFrames = 0
        }
    }

    // ---------------- S23 参考配速器 ----------------

    private fun startRefPacer(track: AudioTrack) {
        if (referenceListener == null || refPacerRunning) return
        refPacerRunning = true
        refPacer = Thread({
            // CONC-4：任何退出路径（playbackHeadPosition 抛 / sleep 中断 / running 翻 false）
            // 都必须清 refPacerRunning——否则线程死了标志还挂着，startRefPacer 永远早退，
            // refPending 无界增长（~48KB/s）且 AEC 参考静默死亡（NLMS 退化复发）。
            val normal = runCatching { refPacerLoop({ refPacerRunning }, { refPacerRunning = it }, { track.playbackHeadPosition }, ::drainRefPending) { Thread.sleep(10) } }
                .getOrElse { false }
            if (!normal) PetLogger.w(TAG, "ref-pacer 异常退出（头位置抛/中断），running 标志已清，下次 play 可重启")
        }, "ref-pacer").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY // S25b：音频后台线程不得与 UI 主线程同级抢 CPU（同 kws-decode）
            start()
        }
    }

    /** 按已播帧数从「已写未播」队列放出 PCM 给消回声器；欠账（写滞后于播）不补零。 */
    private fun drainRefPending(needBytes: Int) {
        val listener = referenceListener ?: return
        var need = needBytes
        while (need > 0) {
            val chunk: ByteArray = synchronized(refLock) {
                val head = refPending.firstOrNull()
                if (head == null) {
                    null
                } else {
                    val take = minOf(head.size - refPendingOff, need)
                    val part = head.copyOfRange(refPendingOff, refPendingOff + take)
                    refPendingOff += take
                    if (refPendingOff == head.size) {
                        refPending.removeFirst()
                        refPendingOff = 0
                    }
                    part
                }
            } ?: return
            listener.invoke(chunk, SR)
            need -= chunk.size
        }
    }

    private fun stopRefPacer() {
        refPacerRunning = false
        refPacer?.join(200)
        refPacer = null
        synchronized(refLock) {
            refPending.clear()
            refPendingOff = 0
        }
    }

    // ---------------- 共享原语 ----------------

    /** 阻塞写整段 PCM（缓冲满则 write 阻塞 = 天然背压），每轮查打断标志。 */
    @Throws(IOException::class)
    private fun writeAll(track: AudioTrack, pcm: ByteArray) {
        var off = 0
        while (off < pcm.size) {
            if (stopped) throw IOException("stopped")
            val n = track.write(pcm, off, pcm.size - off)
            if (n < 0) throw IOException("AudioTrack write error $n")
            // S23：入「已写未播」队列，由配速器按播放头位置推送（勿在此直推=写入节奏错位）
            if (referenceListener != null) synchronized(refLock) {
                refPending.addLast(pcm.copyOfRange(off, off + n))
            }
            off += n
        }
    }

    /** 等播放头追平 totalFrames（写返回 ≠ 出声完）+ 3s 上限防死等，每轮查打断。 */
    @Throws(IOException::class)
    private fun drainUntil(track: AudioTrack, totalFrames: Long) {
        val deadlineMs = System.currentTimeMillis() + totalFrames * 1000L / SR + 3000
        while (track.playbackHeadPosition < totalFrames &&
            System.currentTimeMillis() < deadlineMs
        ) {
            if (stopped) throw IOException("stopped")
            Thread.sleep(10)
        }
    }

    private fun buildTrack(bufBytes: Int): AudioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(
                // 2026-08-10 根因：USAGE_MEDIA 在后台被 ColorOS AudioHardening 静音
                // （dumpsys audio: "background playback would be muted for com.xiaoduo.deskpet"）。
                // 小朵常驻后台（悬浮窗/FGS），改 USAGE_ASSISTANT（语音助手应答，语义匹配 +
                // 通常豁免后台静音）绕过；保留 CONTENT_TYPE_SPEECH。
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SR)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { setVolume(volume) } // S24：App 内音量（build 后 play 前设置，双路径共用）

    companion object {
        private const val TAG = "VoicePlayer"
        private const val TAIL_MS = 50L // latency + 0.05s 收尾余量
        private const val MAX_BLOCK_BUF_BYTES = 1 shl 20 // playBlocking 单段缓冲上限 1MB
        /** 流式缓冲：~1s 音频（24kHz mono 16-bit = 48000B），兼顾预取与内存。 */
        private const val STREAM_BUF_BYTES = 48_000

        private val SR get() = RevealScheduler.TTS_SAMPLE_RATE
    }
}

/**
 * 参考配速循环（纯逻辑，JVM 可测；CONC-4）：每 [sleep] 一步查 [trackHead] 推进参考推送，
 * 直到 [running] 翻 false。返回 true=正常退出（running 翻 false），false=异常退出
 * （trackHead 抛 / sleep 中断）。**任何退出路径都必须经 [setRunning] 清 running 标志**——
 * 线程可以死，标志不能留（残留标志 = startRefPacer 永远早退 = 参考死亡 + refPending 无界）。
 */
internal fun refPacerLoop(
    running: () -> Boolean,
    setRunning: (Boolean) -> Unit,
    trackHead: () -> Int,
    drain: (Int) -> Unit,
    sleep: () -> Unit,
): Boolean {
    setRunning(true)
    try {
        var lastHead = 0
        while (running()) {
            val head = try {
                trackHead()
            } catch (t: Throwable) {
                return false // 轨已 release：退出，finally 清标志（线程体记日志）
            }
            val newFrames = head - lastHead // int 差天然 mod 2^32（24k 回卷周期 ~24h）
            if (newFrames > 0) {
                lastHead = head
                drain(newFrames * 2)
            }
            sleep() // 中断向上抛 → finally 清标志
        }
        return true
    } finally {
        setRunning(false)
    }
}
