package com.xiaoduo.deskpet.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S58b 自回声判定单测（纯逻辑）。
 * 病灶（2026-09-21 20:07 板端实证）：multi-turn 重聆听瞬间喇叭余音被 VAD 捕获，
 * ASR 转出上一轮回答尾巴「或档案管理帮助吗？」→ 幽灵轮吃掉聆听窗口（感知「只监听一小会」）。
 */
class VoiceSessionManagerSelfEchoTest {

    private val lastReply = "我可以帮您查询档案、借阅归还档案，或档案管理帮助吗？"

    @Test
    fun `S58b 回复尾巴转写判为自回声`() {
        assertTrue(VoiceSessionManager.isSelfEcho("或档案管理帮助吗。", lastReply))
        assertTrue(VoiceSessionManager.isSelfEcho("或档案管理帮助吗", lastReply))
    }

    @Test
    fun `S58b 去标点对抗 ASR 与 TTS 标点差异`() {
        // ASR 收尾加句号 / TTS 原文用问号——去标点后子串仍命中
        assertTrue(VoiceSessionManager.isSelfEcho("档案管理帮助吗。", lastReply))
    }

    @Test
    fun `S58b 用户真实新指令不误杀`() {
        assertFalse(VoiceSessionManager.isSelfEcho("今天的天气怎么样", lastReply))
        assertFalse(VoiceSessionManager.isSelfEcho("借阅2025年的合同档案", lastReply))
    }

    @Test
    fun `S58b 短文本与无历史不判定`() {
        assertFalse(VoiceSessionManager.isSelfEcho("好的", lastReply)) // <4 有效字符
        assertFalse(VoiceSessionManager.isSelfEcho("或档案管理帮助吗", null)) // 无历史
        assertFalse(VoiceSessionManager.isSelfEcho("或档案管理帮助吗", "")) // 空历史
    }
}
