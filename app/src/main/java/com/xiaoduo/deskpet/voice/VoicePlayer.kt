package com.xiaoduo.deskpet.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.xiaoduo.deskpet.util.PetLogger
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

    /** S23 参考分接头：已写 PCM 推给回声消除器（NlmsEchoCanceller）作 NLMS 参考。
     *  null = 无订阅零开销。回调在播放线程同步调，实现方须轻量（环形缓冲 memcpy 级）。 */
    var referenceListener: ((pcm: ByteArray, sampleRate: Int) -> Unit)? = null

    /** 在途 AudioTrack 引用（stop 需直接停硬件加速打断，不等下一轮写循环）。 */
    @Volatile
    private var activeTrack: AudioTrack? = null

    /** 流式 track（跨句复用）+ 累计已写帧数（收尾等播完用）。null = 未开播/已释放。 */
    @Volatile
    private var streamTrack: AudioTrack? = null
    private var streamWrittenFrames = 0L

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
        var track = streamTrack
        if (track == null) {
            if (stopped) throw IOException("stopped")
            val minBuf = AudioTrack.getMinBufferSize(
                SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            track = buildTrack(maxOf(minBuf, STREAM_BUF_BYTES))
            streamTrack = track
            activeTrack = track
            streamWrittenFrames = 0
            track.play()
        }
        writeAll(track, pcm)
        streamWrittenFrames += pcm.size / 2
    }

    /** 收尾：等所有已写帧播完 + 0.05s 余量再释放（整轮 TTS 结束调）。幂等（无 track 即返回）。 */
    @Throws(IOException::class)
    fun finish() {
        val track = streamTrack ?: return
        try {
            drainUntil(track, streamWrittenFrames)
            Thread.sleep(TAIL_MS)
            PetLogger.d(TAG, "stream played $streamWrittenFrames frames @24k")
        } finally {
            releaseStream()
        }
    }

    /** 强制释放流式 track（播放异常后下句重建 / finally 兜底 / barge-in 收尾）。幂等。 */
    fun reset() = releaseStream()

    private fun releaseStream() {
        val track = streamTrack ?: return
        streamTrack = null
        if (activeTrack === track) activeTrack = null
        runCatching { track.stop() }
        track.release()
        streamWrittenFrames = 0
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
            referenceListener?.invoke(pcm.copyOfRange(off, off + n), SR)
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

    companion object {
        private const val TAG = "VoicePlayer"
        private const val TAIL_MS = 50L // latency + 0.05s 收尾余量
        private const val MAX_BLOCK_BUF_BYTES = 1 shl 20 // playBlocking 单段缓冲上限 1MB
        /** 流式缓冲：~1s 音频（24kHz mono 16-bit = 48000B），兼顾预取与内存。 */
        private const val STREAM_BUF_BYTES = 48_000

        private val SR get() = RevealScheduler.TTS_SAMPLE_RATE
    }
}
