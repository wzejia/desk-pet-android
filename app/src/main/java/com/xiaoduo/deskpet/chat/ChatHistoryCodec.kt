package com.xiaoduo.deskpet.chat

import com.xiaoduo.deskpet.api.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * 聊天历史 JSON 编解码（纯 Kotlin + org.json，JVM 可单测；文件 IO 在 Android 侧 ChatStore）。
 *
 * 选型理由：单会话单流水、体量小（几十~几百条），Room 需动 gradle 引入 kapt/ksp 与
 * 运行时依赖，成本远超收益；单个 JSON 文件整存整取 + 状态迁移在解码时一次完成，
 * 满足 R2（杀进程回看 / 清空）且零新依赖。
 *
 * 状态迁移（杀进程遗留）：解码时 SENDING → ERROR（用户消息可重发），
 * STREAMING → DONE（已有部分文本视为定稿，空文本记 ERROR）。
 * 坏 JSON / 非数组 → emptyList（绝不因历史文件损坏崩 App）。
 */
object ChatHistoryCodec {

    fun encode(messages: List<ChatMessage>): String {
        val arr = JSONArray()
        for (m in messages) {
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("role", m.role.wire)
                    .put("text", m.text)
                    .put("status", m.status.wire)
                    .put("ts", m.ts)
                    .apply { if (m.wireId != null) put("wireId", m.wireId) },
            )
        }
        return arr.toString()
    }

    fun decode(json: String): List<ChatMessage> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ChatMessage>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isBlank()) continue
            val text = o.optString("text", "")
            var status = ChatMessage.Status.fromWire(o.optString("status", "done"))
            // 杀进程遗留中间态迁移：sending→error（可重发）；streaming→done（有文本）/error（空）
            status = when (status) {
                ChatMessage.Status.SENDING -> ChatMessage.Status.ERROR
                ChatMessage.Status.STREAMING ->
                    if (text.isBlank()) ChatMessage.Status.ERROR else ChatMessage.Status.DONE
                else -> status
            }
            out.add(
                ChatMessage(
                    id = id,
                    role = ChatMessage.Role.fromWire(o.optString("role", "assistant")),
                    text = text,
                    status = status,
                    ts = o.optLong("ts", 0L),
                    wireId = o.optString("wireId", "").takeIf { it.isNotBlank() },
                ),
            )
        }
        return out
    }
}
