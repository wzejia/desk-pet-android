package com.xiaoduo.deskpet.chat

/** 会话操作纯规则（零 Android import，JVM 单测）。 */
object SessionRules {

    /**
     * S48 会话闲置超时阈值：最后一条消息 10 分钟无后续即视为闲置。
     * 常量硬编码不设配置项（用户拍板 2026-09-17）。
     */
    const val SESSION_IDLE_TIMEOUT_MS = 10 * 60 * 1000L

    /**
     * S48 惰性超时轮转判定：最后一条消息距今超过 [timeoutMs] 则应先静默新建会话再继续本轮。
     * 空会话（无任何消息，lastMessageTs=null）永不触发——新建出的空会话没有消息时间戳，
     * 天然杜绝「空会话闲置后再次轮转出空会话」的循环（用户拍板 2026-09-17）。
     */
    fun shouldRotateForIdle(lastMessageTs: Long?, now: Long, timeoutMs: Long = SESSION_IDLE_TIMEOUT_MS): Boolean =
        lastMessageTs != null && now - lastMessageTs > timeoutMs

    /**
     * 删除会话后的当前会话回落（票据规则）：
     * 删的不是当前会话 → 当前不变；删的是当前 → 回落到最近更新（updatedAt 最大）的其余会话；
     * 删完无剩余 → null（调用方造新空会话）。
     */
    fun currentAfterDelete(sessions: List<ChatSession>, deletedId: String, currentId: String?): String? {
        if (deletedId != currentId) return currentId
        return sessions.filter { it.id != deletedId }.maxByOrNull { it.updatedAt }?.id
    }
}
