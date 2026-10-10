package com.xiaoduo.deskpet.chat

/**
 * think 标签清洗（implementation-notes 2026-07-30 裁决：平台 think 未关，回复混入
 * 字面量 </think> 与 <think>...</think> 块，显示层与气泡/TTS 送入前统一剥除；
 * 气泡显示原句原则的例外：think 标签不是内容）。
 *
 * 纯 Kotlin 零 Android import，可 JVM 单测。流式安全：未闭合块与 delta 边界的
 * 半截标签（如 "</th"、"<thi"）一律先藏起来，等后续 delta 补齐再判定。
 */
object ThinkCleaner {

    private const val OPEN = "<think>"
    private const val CLOSE = "</think>"
    // P3-T5：正则内 <think>/</think> 改引上述常量（模板展开后与原字面量逐字相同）
    private val BLOCK = Regex("$OPEN[\\s\\S]*?$CLOSE")

    /**
     * 剥除 think 内容：
     * 1) 完整 <think>...</think> 块全部剥除（可多块）；
     * 2) 未闭合的 "<think..." 起到结尾全部截断（流式中 think 段尚未走完）；
     * 3) 游离 </think> 字面量剥除（平台 think 未关的实测形态）；
     * 4) 尾部半截开/闭标签（delta 边界切开）暂存截断。
     */
    fun clean(raw: String): String {
        if (raw.isEmpty()) return raw
        var s = BLOCK.replace(raw, "")
        val open = s.indexOf("<think")
        if (open >= 0) s = s.substring(0, open)
        s = s.replace(CLOSE, "")
        s = dropTrailingPartial(s, CLOSE)
        s = dropTrailingPartial(s, OPEN)
        return s
    }

    /** 尾部若是 tag 的前 1..len-1 个字符（delta 切开的半截标签，含裸 "<"），先截掉等后续补齐。 */
    private fun dropTrailingPartial(s: String, tag: String): String {
        val max = minOf(tag.length - 1, s.length)
        for (len in max downTo 1) {
            if (s.endsWith(tag.substring(0, len))) return s.dropLast(len)
        }
        return s
    }
}
