package com.xiaoduo.deskpet.chat

import org.json.JSONArray
import org.json.JSONObject

/** 会话索引：当前会话 id + 全部会话元数据（消息本体在 {id}.json，见 ChatStore）。 */
data class SessionIndex(
    val currentId: String?,
    val sessions: List<ChatSession>,
)

/**
 * 会话索引 JSON 编解码（纯 Kotlin + org.json，JVM 可单测；文件 IO 在 ChatStore）。
 * 坏 JSON / 非对象 / 缺 sessions → 空索引（绝不因索引损坏崩 App；消息文件各自独立不受牵连）。
 */
object SessionIndexCodec {

    fun encode(index: SessionIndex): String {
        val arr = JSONArray()
        for (s in index.sessions) {
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("createdAt", s.createdAt)
                    .put("updatedAt", s.updatedAt)
                    .put("titleManual", s.titleManual),
            )
        }
        return JSONObject()
            .put("current", index.currentId ?: JSONObject.NULL)
            .put("sessions", arr)
            .toString()
    }

    fun decode(json: String): SessionIndex {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return SessionIndex(null, emptyList())
        val arr = o.optJSONArray("sessions") ?: return SessionIndex(null, emptyList())
        val sessions = ArrayList<ChatSession>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val id = s.optString("id", "")
            if (id.isBlank()) continue
            sessions.add(
                ChatSession(
                    id = id,
                    title = s.optString("title", ChatSession.FALLBACK_TITLE),
                    createdAt = s.optLong("createdAt", 0L),
                    updatedAt = s.optLong("updatedAt", 0L),
                    // 向后兼容：旧索引无此字段，缺省 false（继续走 deriveTitle）
                    titleManual = s.optBoolean("titleManual", false),
                ),
            )
        }
        val current = o.optString("current", "").takeIf { it.isNotBlank() }
        return SessionIndex(current, sessions)
    }
}
