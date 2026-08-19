package com.xiaoduo.deskpet.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音泡收泡判定（用户拍板 2026-07-30：播完 12s 收）：
 * 回 IDLE 且本轮弹过气泡 → 收一次；多轮回 LISTENING 不收；未弹泡不收；重复 IDLE 不重复收。
 */
class BubbleHidePolicyTest {

    @Test
    fun `delay is 12 seconds per user decision`() {
        assertEquals(12_000L, BubbleHidePolicy.DELAY_MS)
    }

    @Test
    fun `idle after bubble shown schedules hide once`() {
        val p = BubbleHidePolicy()
        p.onBubbleShown()
        assertTrue(p.onReturnToIdle())
        // 重复 IDLE 转移不重复收
        assertFalse(p.onReturnToIdle())
    }

    @Test
    fun `idle without bubble does not hide`() {
        val p = BubbleHidePolicy()
        assertFalse(p.onReturnToIdle())
    }

    @Test
    fun `multi-turn keeps bubble until final idle`() {
        val p = BubbleHidePolicy()
        p.onBubbleShown()
        // 多轮：回到 LISTENING 不触碰判定（本类无 LISTENING 入口——只有 IDLE 才问），
        // 语义钉死：只要没回 IDLE，判定状态保持，最终回 IDLE 时仍收
        assertTrue(p.onReturnToIdle())
    }

    @Test
    fun `new round after hide re-arms policy`() {
        val p = BubbleHidePolicy()
        p.onBubbleShown()
        assertTrue(p.onReturnToIdle())
        // 新一轮弹泡后再回 IDLE → 再收一次
        p.onBubbleShown()
        assertTrue(p.onReturnToIdle())
    }
}
