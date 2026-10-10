package com.xiaoduo.deskpet.chat

import com.xiaoduo.deskpet.api.ChatMessage
/**
 * 搜索命中摘要：会话 + 摘要文本。
 * 摘要 = 首条命中消息（压缩空白后截断）；仅标题命中时摘要为标题本身。
 */
data class SessionSummary(
    val session: ChatSession,
    val snippet: String,
)

/**
 * 会话全文搜索（拍板②：范围 = 标题 + 消息全文）。纯 Kotlin 零 Android import。
 * 数据量小（几十~几百条/会话），内存过滤即可，不造索引库。
 * 大小写不敏感；空/纯空白 query → 空结果；返回顺序 = 入参顺序（调用方按 updatedAt 倒序喂入）。
 */
object SessionSearch {

    const val SNIPPET_MAX_CHARS = 30

    private val WHITESPACE = Regex("\\s+")

    fun search(
        sessions: List<Pair<ChatSession, List<ChatMessage>>>,
        query: String,
    ): List<SessionSummary> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return sessions.mapNotNull { (session, messages) ->
            val hit = messages.firstOrNull { it.text.contains(q, ignoreCase = true) }
            when {
                hit != null -> SessionSummary(session, truncate(hit.text))
                session.title.contains(q, ignoreCase = true) -> SessionSummary(session, session.title)
                else -> null
            }
        }
    }

    private fun truncate(text: String): String {
        val t = text.replace(WHITESPACE, " ").trim()
        return if (t.length > SNIPPET_MAX_CHARS) t.take(SNIPPET_MAX_CHARS) + "…" else t
    }
}
