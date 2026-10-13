package com.xiaoduo.deskpet.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BubbleOwnership 单测（BLOCKED.md backlog #4 四类钉死场景）：
 * ① 文字轮占用中语音 show → 接管（新 round 生效，旧 round 后续 append/hide 全弃）；
 * ② 旧 round 的延迟 hide 在新 round 占用时不得生效；
 * ③ 同 owner 连续两轮；
 * ④ show_bubble=false 时一切 no-op + 立即隐藏（FORCE_HIDE）语义不回归。
 */
class BubbleOwnershipTest {

    // ---------------- ① 跨通道接管 ----------------

    @Test
    fun `voice show takes over during chat round, stale chat calls all dropped`() {
        val o = BubbleOwnership()
        val chatRound = o.claim(BubbleOwner.CHAT)
        // 文字轮占用中：自己的 show/append 受理
        assertEquals(BubbleOwnership.Verdict.APPLY, o.judgePublish(BubbleOwner.CHAT, chatRound, true))
        // 语音接管：新 round 生效
        val voiceRound = o.claim(BubbleOwner.VOICE)
        assertTrue(voiceRound > chatRound)
        assertEquals(BubbleOwnership.Verdict.APPLY, o.judgePublish(BubbleOwner.VOICE, voiceRound, true))
        // 旧 round 后续 append/hide 全弃
        assertEquals(BubbleOwnership.Verdict.DROP_STALE, o.judgePublish(BubbleOwner.CHAT, chatRound, true))
        assertEquals(BubbleOwnership.Verdict.DROP_STALE, o.judgeHide(BubbleOwner.CHAT, chatRound))
        // 反方向同样成立：chat 再接管后 voice 变 stale
        val chatRound2 = o.claim(BubbleOwner.CHAT)
        assertEquals(BubbleOwnership.Verdict.DROP_STALE, o.judgePublish(BubbleOwner.VOICE, voiceRound, true))
        assertEquals(BubbleOwnership.Verdict.DROP_STALE, o.judgeHide(BubbleOwner.VOICE, voiceRound))
        assertEquals(BubbleOwnership.Verdict.APPLY, o.judgeHide(BubbleOwner.CHAT, chatRound2))
    }

    // ---------------- ② 延迟 hide 到点复验 ----------------

    @Test
    fun `stale delayed hide does not fire while new round occupies bubble`() {
        val o = BubbleOwnership()
        val chatRound = o.claim(BubbleOwner.CHAT)
        // 回复完成，排了 12s 延迟隐藏；期间语音占用气泡
        o.claim(BubbleOwner.VOICE)
        // 到点复验：旧 round 的延迟 hide 不得生效
        assertEquals(BubbleOwnership.Verdict.DROP_STALE, o.judgeHide(BubbleOwner.CHAT, chatRound))
        // 同 round 未被接管时到点复验仍受理（单通道 12s 收泡不回归）
        val o2 = BubbleOwnership()
        val r = o2.claim(BubbleOwner.CHAT)
        assertEquals(BubbleOwnership.Verdict.APPLY, o2.judgeHide(BubbleOwner.CHAT, r))
    }

    // ---------------- ③ 同 owner 连续两轮 ----------------

    @Test
    fun `same owner consecutive rounds invalidate previous round`() {
        val o = BubbleOwnership()
        val r1 = o.claim(BubbleOwner.CHAT)
        val r2 = o.claim(BubbleOwner.CHAT)
        assertTrue(r2 > r1)
        assertEquals(BubbleOwnership.Verdict.DROP_STALE, o.judgePublish(BubbleOwner.CHAT, r1, true))
        assertEquals(BubbleOwnership.Verdict.DROP_STALE, o.judgeHide(BubbleOwner.CHAT, r1))
        assertEquals(BubbleOwnership.Verdict.APPLY, o.judgePublish(BubbleOwner.CHAT, r2, true))
        assertEquals(BubbleOwnership.Verdict.APPLY, o.judgeHide(BubbleOwner.CHAT, r2))
        assertTrue(o.isCurrent(BubbleOwner.CHAT, r2))
        assertFalse(o.isCurrent(BubbleOwner.CHAT, r1))
    }

    // ---------------- ④ show_bubble=false 语义 ----------------

    @Test
    fun `bubble disabled forces hide regardless of round validity`() {
        val o = BubbleOwnership()
        val r = o.claim(BubbleOwner.CHAT)
        // 当前 round 也 FORCE_HIDE（no-op + 立即隐藏，不弹泡）
        assertEquals(BubbleOwnership.Verdict.FORCE_HIDE, o.judgePublish(BubbleOwner.CHAT, r, false))
        // 过期 round 同样 FORCE_HIDE（设置翻转前已弹出的泡也要收掉，与旧无条件 hide 语义一致）
        val r2 = o.claim(BubbleOwner.VOICE)
        assertEquals(BubbleOwnership.Verdict.FORCE_HIDE, o.judgePublish(BubbleOwner.CHAT, r, false))
        assertEquals(BubbleOwnership.Verdict.FORCE_HIDE, o.judgePublish(BubbleOwner.VOICE, r2, false))
        // 收泡本身不受开关影响：当前轮 hide 仍 APPLY
        assertEquals(BubbleOwnership.Verdict.APPLY, o.judgeHide(BubbleOwner.VOICE, r2))
        // 开关重新打开后恢复正常判定
        assertEquals(BubbleOwnership.Verdict.APPLY, o.judgePublish(BubbleOwner.VOICE, r2, true))
    }

    // ---------------- 边界：未认领 / round=0 ----------------

    @Test
    fun `unclaimed or zero round never applies`() {
        val o = BubbleOwnership()
        assertEquals(BubbleOwnership.Verdict.DROP_STALE, o.judgePublish(BubbleOwner.CHAT, 0, true))
        assertEquals(BubbleOwnership.Verdict.DROP_STALE, o.judgeHide(BubbleOwner.VOICE, 0))
        assertFalse(o.isCurrent(BubbleOwner.CHAT, 0))
        // 认领后 owner 不匹配也无效（round 全局单调，正常不会撞，防御判定）
        val r = o.claim(BubbleOwner.DEBUG)
        assertFalse(o.isCurrent(BubbleOwner.CHAT, r))
        assertEquals(BubbleOwnership.Verdict.DROP_STALE, o.judgeHide(BubbleOwner.CHAT, r))
    }

    @Test
    fun `claim rounds are monotonically increasing from 1`() {
        val o = BubbleOwnership()
        val owners = listOf(BubbleOwner.CHAT, BubbleOwner.VOICE, BubbleOwner.DEBUG, BubbleOwner.CHAT)
        val rounds = owners.map { o.claim(it) }
        assertEquals(listOf(1, 2, 3, 4), rounds)
    }
}
