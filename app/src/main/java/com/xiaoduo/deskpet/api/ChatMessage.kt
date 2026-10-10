package com.xiaoduo.deskpet.api

/**
 * 聊天消息（spec 数据模型表）：单条流水，status 驱动 UI 反馈（R1）。
 * 纯 Kotlin 零 Android import（spec 领域层铁律），序列化见 chat 包 ChatHistoryCodec；chat 不再是唯一消费者，cloud/TextChatRouter 亦消费本类型。
 */
data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String,
    val status: Status,
    val ts: Long,
    /**
     * AG-UI wire 消息 id（仅 user 消息携带）：手动重发必须复用同一 id
     * （踩坑 #25：换 id 重发 = 平台 thread 追加重复消息，空回复率 ~20%→~50%）。
     * 升级前旧历史为 null，首次重发时由 [ChatTurnLogic] 现造并回写。
     */
    val wireId: String? = null,
) {
    enum class Role(val wire: String) {
        USER("user"),
        ASSISTANT("assistant"),
        SYSTEM("system"),
        ;

        companion object {
            fun fromWire(wire: String): Role = entries.firstOrNull { it.wire == wire } ?: ASSISTANT
        }
    }

    enum class Status(val wire: String) {
        SENDING("sending"),
        STREAMING("streaming"),
        DONE("done"),
        ERROR("error"),
        ;

        companion object {
            fun fromWire(wire: String): Status = entries.firstOrNull { it.wire == wire } ?: DONE
        }
    }
}
