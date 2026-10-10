package com.xiaoduo.deskpet.voice.audio

/**
 * WAV 头封装/剥离（纯逻辑，JVM 可测）。
 *
 * 契约依据：知识包 §3.2（ASR 送 16k PCM 包 WAV 头；TTS 每帧完整 WAV 剥头拼 PCM）
 * + 踩坑 #7（多帧 WAV 直接 join 夹 RIFF 头，播放器只播首帧 → 必须剥头拼 PCM）。
 */
object WavCodec {

    private const val RIFF_HEADER_SIZE = 44

    /** PCM 包标准 44 字节 WAV 头（PCM=1，Int16，mono 默认）。 */
    fun pcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArray(RIFF_HEADER_SIZE + pcm.size)
        writeAscii(out, 0, "RIFF")
        writeLeInt(out, 4, 36 + pcm.size)
        writeAscii(out, 8, "WAVE")
        writeAscii(out, 12, "fmt ")
        writeLeInt(out, 16, 16) // fmt chunk size
        writeLeShort(out, 20, 1) // PCM
        writeLeShort(out, 22, channels)
        writeLeInt(out, 24, sampleRate)
        writeLeInt(out, 28, byteRate)
        writeLeShort(out, 32, blockAlign)
        writeLeShort(out, 34, bitsPerSample)
        writeAscii(out, 36, "data")
        writeLeInt(out, 40, pcm.size)
        pcm.copyInto(out, RIFF_HEADER_SIZE)
        return out
    }

    /**
     * 剥 WAV 头取裸 PCM：按 RIFF chunk 遍历定位 data 块（容忍 fmt 扩展等附加 chunk），
     * 非 RIFF/WAVE 或无 data chunk 抛 IllegalArgumentException（调用方转错误 msg 透传）。
     */
    fun stripWavHeader(wav: ByteArray): ByteArray {
        require(wav.size >= 12 && asciiAt(wav, 0) == "RIFF" && asciiAt(wav, 8) == "WAVE") {
            "非 RIFF/WAVE 音频（size=${wav.size}）"
        }
        var off = 12
        while (off + 8 <= wav.size) {
            val id = asciiAt(wav, off)
            val size = leIntAt(wav, off + 4)
            // ocr[41]：无溢出形式——旧 `off + 8 + size <= wav.size` 在 size=0x7FFFFFFF 时
            // 回绕成负值误放行：非 data chunk 的 off 随后回绕负值，asciiAt 抛
            // IndexOutOfBoundsException（破坏「损坏输入一律 IllegalArgumentException」契约）；
            // data chunk 则 copyOfRange 收到 from>to（VM 限上限内 ~2GB 分配）。循环守卫
            // `off + 8 <= wav.size` 已保证右式 ≥0，本式无溢出。变异锚：回退溢出形式 →
            // WavCodecBoundsTest 红。
            require(size >= 0 && size <= wav.size - off - 8) { "WAV chunk $id 越界（size=$size）" }
            if (id == "data") return wav.copyOfRange(off + 8, off + 8 + size)
            off += 8 + size + (size and 1) // chunk 数据按偶数字节对齐
        }
        throw IllegalArgumentException("WAV 无 data chunk（size=${wav.size}）")
    }

    private fun asciiAt(b: ByteArray, off: Int): String =
        String(b, off, 4, Charsets.US_ASCII)

    private fun leIntAt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun writeAscii(out: ByteArray, off: Int, s: String) {
        for (i in s.indices) out[off + i] = s[i].code.toByte()
    }

    private fun writeLeInt(out: ByteArray, off: Int, v: Int) {
        out[off] = (v and 0xFF).toByte()
        out[off + 1] = (v shr 8 and 0xFF).toByte()
        out[off + 2] = (v shr 16 and 0xFF).toByte()
        out[off + 3] = (v shr 24 and 0xFF).toByte()
    }

    private fun writeLeShort(out: ByteArray, off: Int, v: Int) {
        out[off] = (v and 0xFF).toByte()
        out[off + 1] = (v shr 8 and 0xFF).toByte()
    }
}
