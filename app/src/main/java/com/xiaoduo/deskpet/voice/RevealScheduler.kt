package com.xiaoduo.deskpet.voice

/**
 * 边念边出字 reveal 的纯函数计算器（不做真实延时，Android 层按返回值协程 delay）。
 *
 * 公式照抄知识包 §3.3 / 桌面端 sidecar._reveal_text：
 * 每句精确时长 = len(pcm)/2/24000 秒，按 字数 均分逐字显示；
 * TTS 单句失败 / 播放异常（无 PCM）→ 纯文字按预估语速 4.5 字/秒 reveal。
 */
object RevealScheduler {

    const val TTS_SAMPLE_RATE = 24000
    const val BYTES_PER_SAMPLE = 2
    const val FALLBACK_CHARS_PER_SEC = 4.5

    /** 每字显示间隔（ms）。pcmBytes <= 0 视为无音频 → 降级语速。 */
    fun perCharDelayMs(charCount: Int, pcmBytes: Int): Double {
        require(charCount > 0) { "charCount 必须 > 0" }
        if (pcmBytes <= 0) return fallbackPerCharDelayMs()
        val durationMs = pcmBytes.toDouble() / BYTES_PER_SAMPLE / TTS_SAMPLE_RATE * 1000.0
        return durationMs / charCount
    }

    /** TTS 失败/无 PCM 降级：4.5 字/秒 → 1000/4.5 ms 每字。 */
    fun fallbackPerCharDelayMs(): Double = 1000.0 / FALLBACK_CHARS_PER_SEC

    /** 整句逐字 delay 序列（ms）；pcmBytes 传 null/0 → 全句降级语速；空句返回空表。 */
    fun delaysFor(sentence: String, pcmBytes: Int?): List<Double> {
        if (sentence.isEmpty()) return emptyList()
        val perChar =
            if (pcmBytes == null || pcmBytes <= 0) fallbackPerCharDelayMs()
            else perCharDelayMs(sentence.length, pcmBytes)
        return List(sentence.length) { perChar }
    }

    /** (charIndex) -> delayMs 接口。当前为均匀切分（无字级时间戳），索引保留给将来字级权重。 */
    fun delayForChar(charIndex: Int, charCount: Int, pcmBytes: Int?): Double {
        require(charIndex in 0 until charCount) { "charIndex $charIndex 越界（charCount=$charCount）" }
        return if (pcmBytes == null || pcmBytes <= 0) fallbackPerCharDelayMs()
        else perCharDelayMs(charCount, pcmBytes)
    }
}
