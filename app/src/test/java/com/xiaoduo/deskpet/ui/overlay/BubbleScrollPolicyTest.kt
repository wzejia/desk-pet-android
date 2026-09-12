package com.xiaoduo.deskpet.ui.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S39 气泡滚动跟随策略（纯 JVM）：打字机逐字更新时的自动滚底与用户手动滚动的仲裁。
 *
 * 背景：BubbleView 打字机每 tick 程序滚到底——用户上滑查看历史内容会被下一 tick
 * 立即拽回（根本拉不动）。策略：用户触摸期间不自动滚；松手时若视口贴底则恢复
 * 跟随（新内容继续拽底），未贴底则保持（用户看完自己回底，回贴底后自动恢复）。
 */
class BubbleScrollPolicyTest {

    @Test
    fun `initial state auto-follows new content`() {
        val p = BubbleScrollPolicy()
        assertTrue("新内容到达应自动滚底（默认贴底跟随）", p.shouldAutoFollow())
    }

    @Test
    fun `dragging suspends auto-follow`() {
        val p = BubbleScrollPolicy()
        p.onDragStart()
        assertFalse("用户拖动中不得拽底（否则拉不动）", p.shouldAutoFollow())
    }

    @Test
    fun `release at bottom resumes auto-follow`() {
        val p = BubbleScrollPolicy()
        p.onDragStart()
        p.onDragEnd(atBottom = true)
        assertTrue("松手时贴底应恢复跟随", p.shouldAutoFollow())
    }

    @Test
    fun `release not at bottom keeps viewport until user returns to bottom`() {
        val p = BubbleScrollPolicy()
        p.onDragStart()
        p.onDragEnd(atBottom = false)
        assertFalse("松手未贴底应保持视口（新内容继续打字但不拽底）", p.shouldAutoFollow())
        // 用户下滑回贴底（下一次 dragStart→dragEnd(atBottom=true)）后恢复
        p.onDragStart()
        p.onDragEnd(atBottom = true)
        assertTrue("回贴底后应恢复跟随", p.shouldAutoFollow())
    }

    @Test
    fun `drag end without moving at bottom stays following`() {
        val p = BubbleScrollPolicy()
        p.onDragStart()
        p.onDragEnd(atBottom = true)
        p.onDragStart()
        p.onDragEnd(atBottom = true)
        assertTrue("反复点按贴底状态应保持跟随", p.shouldAutoFollow())
    }
}
