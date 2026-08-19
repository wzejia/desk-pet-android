package com.xiaoduo.deskpet.chat

/** 会话操作纯规则（零 Android import，JVM 单测）。 */
object SessionRules {

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
