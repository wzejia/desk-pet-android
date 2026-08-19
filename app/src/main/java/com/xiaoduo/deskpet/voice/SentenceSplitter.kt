package com.xiaoduo.deskpet.voice

/**
 * LLM token 流增量分句（纯逻辑）。
 *
 * 参数照抄对账表分句节（= 桌面端 tts_orchestrator.py HEAD）：
 * 切点 `。！？!?;` + `\n`；MIN 4（短于不切，攒到下一个句末）/ MAX 30（无句末强制切）；
 * 长句切口落在 ASCII 字母/数字中间时回退词边界；逐字符喂入；流结束残留收尾。
 *
 * 词边界回退只对多字符 token 可达（逐字符喂时 buf 到 30 即切）——
 * 与桌面端一致，故同时提供 feed(Char) 与 feed(String)（token）两个入口。
 */
class SentenceSplitter {

    companion object {
        const val SENTENCE_END_CHARS = "。！？!?;\n"
        const val SENTENCE_MIN_CHARS = 4
        const val SENTENCE_MAX_CHARS = 30

        /**
         * 长句硬切切点：切口在 ASCII 字母/数字中间（3.14159、GPT-4o、URL）时
         * 回退到词边界，防 TTS 把切断的数字/单词读错。返回应切下的字符数。
         */
        fun splitLongCut(buf: String, maxChars: Int = SENTENCE_MAX_CHARS): Int {
            if (buf.length <= maxChars) return buf.length
            val cut = maxChars
            if (isAsciiAlnum(buf[cut - 1]) && isAsciiAlnum(buf[cut])) {
                for (i in cut - 1 downTo SENTENCE_MIN_CHARS + 1) {
                    if (!isAsciiAlnum(buf[i])) return i + 1
                }
            }
            return cut
        }

        private fun isAsciiAlnum(c: Char): Boolean = c.code < 128 && c.isLetterOrDigit()
    }

    private val buf = StringBuilder()

    /** 逐字符喂入；返回本次喂入触发的 0..n 个成句（已 strip，空句不发）。 */
    fun feed(c: Char): List<String> = feed(c.toString())

    /** 按 token 喂入（与桌面端 split_sentences 的 token 喂法一致）。 */
    fun feed(token: String): List<String> {
        buf.append(token)
        val out = ArrayList<String>()
        while (buf.isNotEmpty()) {
            val endsWithPunct = buf.last() in SENTENCE_END_CHARS
            val piece = when {
                endsWithPunct && buf.length >= SENTENCE_MIN_CHARS -> buf.toString()
                buf.length >= SENTENCE_MAX_CHARS ->
                    buf.substring(0, splitLongCut(buf.toString()))
                else -> break // 攒着，等更多 token
            }
            buf.delete(0, piece.length)
            val text = piece.trim()
            if (text.isNotEmpty()) out.add(text)
        }
        return out
    }

    /** token 流结束：残留作最后一句（strip 后非空才发）。 */
    fun flush(): String? {
        val text = buf.toString().trim()
        buf.clear()
        return text.ifEmpty { null }
    }
}
