package com.xiaoduo.deskpet.chat

import java.util.concurrent.atomic.AtomicInteger

/**
 * 聊天回合纯逻辑（S2 返工：ChatViewModel 可测化抽取，零 Android import，JVM 单测）。
 *
 * - 消息状态迁移：appendTurn / applyDelta（SENDING→STREAMING）/ markDone / markError；
 * - 流式追加：原始 delta 先进 [rawBuffers] 累加，再整体过 [ThinkCleaner] 得可见文本——
 *   半截 think 标签跨 delta 拼接无损。旧实现把「清洗结果」当累加基（ChatViewModel 存
 *   cleaned 文本再 clean(cleaned + delta)），尾部被截的半截标签永远丢失：裸 "<" 闪现一帧，
 *   若 delta 逐字符到达，标签碎片（"hink>秘密"）直接漏成字面文本；
 * - 重发决策 [prepareResend]：复用 user 消息携带的 wire messageId（踩坑 #25：同 id 重试，
 *   空回复率才不从 ~20% 涨到 ~50%；升级前旧历史缺 wireId 时现造并回写，后续重发仍同 id）。
 */
class ChatTurnLogic(private val clock: () -> Long = System::currentTimeMillis) {

    private val seq = AtomicInteger(0)

    /** 流式原始累加缓冲：assistantId → 未清洗原文；回合结束（done/error/重发/清空）即移除。 */
    private val rawBuffers = HashMap<String, StringBuilder>()

    /** wire 消息 id：`u_{毫秒}_{seq}`（§2.2 全局唯一；同毫秒靠单调 seq 防撞）。 */
    fun nextWireId(): String = "u_${clock()}_${seq.incrementAndGet()}"

    /** 新回合：用户消息（已带 wireId）与占位 assistant 依次追加到尾部。 */
    fun appendTurn(
        messages: List<ChatMessage>,
        user: ChatMessage,
        assistant: ChatMessage,
    ): List<ChatMessage> = messages + user + assistant

    /**
     * 流式追加：delta 并入原始缓冲 → 整体清洗得可见文本 → 状态置 STREAMING。
     * 目标消息缺失（如对话被清空）原样返回。
     */
    fun applyDelta(messages: List<ChatMessage>, assistantId: String, delta: String): List<ChatMessage> {
        val current = messages.firstOrNull { it.id == assistantId } ?: return messages
        val buf = rawBuffers.getOrPut(assistantId) { StringBuilder(current.text) }
        buf.append(delta)
        val visible = ThinkCleaner.clean(buf.toString())
        return messages.map {
            if (it.id == assistantId) it.copy(text = visible, status = ChatMessage.Status.STREAMING) else it
        }
    }

    /** 回合成功：终稿（AguiClient 累计原文）再过一次清洗 + DONE，释放缓冲。 */
    fun markDone(messages: List<ChatMessage>, assistantId: String, finalText: String): List<ChatMessage> {
        rawBuffers.remove(assistantId)
        val visible = ThinkCleaner.clean(finalText)
        return messages.map {
            if (it.id == assistantId) it.copy(text = visible, status = ChatMessage.Status.DONE) else it
        }
    }

    /** 回合失败：ERROR（已流出文本保留展示），释放缓冲。 */
    fun markError(messages: List<ChatMessage>, assistantId: String): List<ChatMessage> {
        rawBuffers.remove(assistantId)
        return messages.map {
            if (it.id == assistantId) it.copy(status = ChatMessage.Status.ERROR) else it
        }
    }

    /**
     * 回合被打断（barge-in #7 阶段1 键盘首字符）：已流出文本保留标 DONE——
     * 部分回复是有效内容，**不对齐 Error**（error 会出「重发」入口误导重复投递
     * 同一 user 文本拿第二份回复）；DONE 亦使 [prepareResend] 拒绝重发。释放缓冲。
     */
    fun markCancelledDone(messages: List<ChatMessage>, assistantId: String): List<ChatMessage> {
        rawBuffers.remove(assistantId)
        return messages.map {
            if (it.id == assistantId) it.copy(status = ChatMessage.Status.DONE) else it
        }
    }

    /**
     * 重发决策：仅 ERROR 的 assistant 可重发；取其前最近一条 user 的文本与 wireId 重跑。
     * 返回的 messages 已把目标 assistant 清空重置为 SENDING；user 缺 wireId（升级前旧历史）
     * 时由 newWireId 现造并回写。无可重发目标返回 null。
     */
    fun prepareResend(
        messages: List<ChatMessage>,
        assistantId: String,
        newWireId: () -> String = ::nextWireId,
    ): ResendPlan? {
        val idx = messages.indexOfFirst { it.id == assistantId && it.status == ChatMessage.Status.ERROR }
        if (idx < 0) return null
        val userIdx = (idx - 1 downTo 0).firstOrNull { messages[it].role == ChatMessage.Role.USER }
            ?: return null
        val user = messages[userIdx]
        val wireId = user.wireId ?: newWireId()
        rawBuffers.remove(assistantId)
        val updated = messages.mapIndexed { i, m ->
            when {
                i == idx -> m.copy(text = "", status = ChatMessage.Status.SENDING)
                i == userIdx && m.wireId == null -> m.copy(wireId = wireId)
                else -> m
            }
        }
        return ResendPlan(assistantId = assistantId, userText = user.text, wireId = wireId, messages = updated)
    }

    /** 清空对话：丢弃全部流式缓冲。 */
    fun reset() = rawBuffers.clear()

    /** 重发计划：用户原文 + 复用的 wire id + 已重置的消息列表。 */
    data class ResendPlan(
        val assistantId: String,
        val userText: String,
        val wireId: String,
        val messages: List<ChatMessage>,
    )
}
