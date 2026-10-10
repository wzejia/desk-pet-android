package com.xiaoduo.deskpet.voice.pipeline

/**
 * TTS 朗读前消毒（纯逻辑）。气泡显示用原句，仅合成输入用清洗结果（听觉/视觉分离）。
 *
 * 规则照抄对账表消毒节（= 桌面端 sidecar._sanitize_for_tts HEAD）：
 * 白名单标点 + 文字数字空白保留，其余剥除（markdown 结构符号 / emoji）；
 * 压缩连续空白（split 后单空格 join）；清洗后无 alnum → 返回空串（跳过合成，防服务端 500）。
 */
object TtsSanitizer {

    /** 与桌面端 sidecar._TTS_KEEP_PUNCT 逐字符一致（含弯引号/破折号/省略号/单位符号）。 */
    private val KEEP_PUNCT: Set<Char> = (
        "，。！？；：、,.!?;:" +
            "「」『』“”‘’'\"()（）【】[]《》〈〉" +
            "—…·-–/~%℃°+"
        ).toSet()

    // Python isspace() 含 NBSP 及全部 Zs/Zl/Zp 类目；Kotlin isWhitespace 不含 NBSP，
    // 叠加 isSpaceChar 对齐（\\p{Z} = Unicode separator 类目，供压缩正则用）。
    private val WHITESPACE_RUN = Regex("[\\s\\p{Z}]+")

    /**
     * 清洗句子供 TTS 朗读。
     * @return 清洗结果；无可发音字符（无 alnum）时返回空串，调用方跳过合成。
     */
    fun sanitize(text: String): String {
        val filtered = text.filter {
            it.isLetterOrDigit() || it.isWhitespace() || Character.isSpaceChar(it) || it in KEEP_PUNCT
        }
        val cleaned = filtered.split(WHITESPACE_RUN).filter { it.isNotEmpty() }.joinToString(" ")
        return if (cleaned.any { it.isLetterOrDigit() }) cleaned else ""
    }
}
