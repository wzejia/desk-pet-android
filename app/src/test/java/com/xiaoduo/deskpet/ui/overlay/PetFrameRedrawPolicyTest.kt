package com.xiaoduo.deskpet.ui.overlay

import com.xiaoduo.deskpet.pet.FrameAnimator
import com.xiaoduo.deskpet.pet.PetFrameTable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 变异锚（审计 ocr[307]）：PetView Choreographer 循环原无条件 invalidate()（静止态也每帧重绘）。
 * 判定：源矩形（行+帧列）相同 ⇔ 待绘画面相同（同 sheet 同帧尺寸）→ 跳过；矩形变化 → 必须重绘。
 * 判定被删（恒 true = 现状无条件重绘）→ 「静止跳过」用例红。
 *
 * S13 红线锚（2026-08-24 定案）：setState 重置 frameIndex=0 且必变行或列 → 切换后首帧必绘；
 * 同矩形跳过仅发生在画面像素本就相同的场合（如同行 0 帧↔0 帧别名），无可感知差异。
 */
class PetFrameRedrawPolicyTest {

    @Test
    fun `identical source rect skips invalidate`() {
        assertFalse(PetFrameRedrawPolicy.shouldInvalidate(10, 20, 10, 20))
    }

    @Test
    fun `column advance invalidates`() {
        // 同行推进一帧：sourceLeft = frameIndex * 192
        assertTrue(PetFrameRedrawPolicy.shouldInvalidate(0, 0, 192, 0))
    }

    @Test
    fun `row switch invalidates`() {
        // 跨状态换行：sourceTop = row * 208
        assertTrue(PetFrameRedrawPolicy.shouldInvalidate(0, 0, 0, 208))
    }

    @Test
    fun `both changed invalidates`() {
        assertTrue(PetFrameRedrawPolicy.shouldInvalidate(192, 0, 0, 208))
    }

    @Test
    fun `sentinel first draw always invalidates`() {
        // 首绘/重挂哨兵 (-1,-1) 不与任何合法矩形相等 → 必绘
        assertTrue(PetFrameRedrawPolicy.shouldInvalidate(-1, -1, 0, 0))
    }

    @Test
    fun `s13 state switch first frame after switch is never swallowed`() {
        val animator = FrameAnimator(PetFrameTable.IDLE)
        var drawnLeft = -1
        var drawnTop = -1
        fun step(): Boolean {
            val inv = PetFrameRedrawPolicy.shouldInvalidate(
                drawnLeft, drawnTop, animator.sourceLeft, animator.sourceTop,
            )
            if (inv) {
                drawnLeft = animator.sourceLeft
                drawnTop = animator.sourceTop
            }
            return inv
        }
        assertTrue(step()) // 首绘：idle row0 帧0
        assertFalse(step()) // 静止：同矩形跳过（S13 静止形态不变）
        animator.setState(PetFrameTable.TALKING) // row0 帧0：矩形与 idle 相同 → 像素相同，允许跳过
        assertFalse(step())
        animator.tick(animator.perFrameMs + 1) // 推进到 TALKING 帧1
        assertTrue(step()) // 新帧必现（状态动画不被吞）
        animator.setState(PetFrameTable.RUNNING_RIGHT) // 换行（row1）→ sourceTop 变
        assertTrue(step()) // 切换首帧必绘（S13 即时性红线）
        assertFalse(step()) // RUNNING 帧内静止期跳过
    }
}
