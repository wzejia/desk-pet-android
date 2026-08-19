package com.xiaoduo.deskpet.chat

/**
 * 会话（多会话扩 scope：implementation-notes 2026-07-31 用户三问拍板，推翻契约 R2
 * 「不做多会话管理」）。纯 Kotlin 零 Android import（spec 领域层铁律）。
 *
 * 标题规则（拍板①）：首条用户消息截断，零成本不用平台生成；无用户消息时兜底 [FALLBACK_TITLE]。
 * [titleManual] = true 时标题为用户手动命名（ADR 0002 路线 C），ChatStore.saveMessages
 * 跳过 deriveTitle，手动名不被后续消息覆盖。
 */
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val titleManual: Boolean = false,
) {
    companion object {
        const val FALLBACK_TITLE = "新会话"

        /** 标题截断长度：超出取前 N 字 + 「…」。 */
        const val TITLE_MAX_CHARS = 20

        private val WHITESPACE = Regex("\\s+")

        /**
         * 标题 = 首条 user 消息（压缩连续空白后）截断；
         * 无 user 消息或首条 user 纯空白 → [FALLBACK_TITLE]。
         */
        fun deriveTitle(messages: List<ChatMessage>): String {
            val first = messages.firstOrNull { it.role == ChatMessage.Role.USER }
                ?.text?.replace(WHITESPACE, " ")?.trim()
                ?: return FALLBACK_TITLE
            if (first.isEmpty()) return FALLBACK_TITLE
            return if (first.length > TITLE_MAX_CHARS) first.take(TITLE_MAX_CHARS) + "…" else first
        }
    }
}
