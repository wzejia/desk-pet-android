package com.xiaoduo.deskpet.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * S31 悬浮窗手势修复：点按判定与拖拽启动阈值解耦。
 *
 * 背景：板子 1x 密度（density=160，dp=px），拖拽启动 touchSlop=12px≈1.7mm——真人按压
 * 手指触点自然漂移 2-3mm 必超，旧逻辑误置 dragging 后 UP 走拖拽分支、tap 记录被吞，
 * 双击永不成立（板子实测「双击打断失败」根因；模拟器注入 tap 无 MOVE 事件故通过，
 * 验证环境掩盖了缺陷）。
 *
 * 修复：UP 净位移 ≤ [TapGesture.MAX_DRIFT_PX]（32px≈5mm，漂移级）视为点按（即使
 * dragging 已置位也按 tap 处理并回弹微动）；>32px 才是真拖拽，走吸附/落盘。
 */
class TapGestureTest {

    private fun isTap(dx: Float, dy: Float) = TapGesture.isTap(dx, dy)

    @Test
    fun `zero drift is a tap`() {
        assertTrue(isTap(0f, 0f))
    }

    @Test
    fun `finger drift of a few mm is still a tap`() {
        // 1x 密度下 2-3mm ≈ 12-20px，真人按压典型漂移
        assertTrue(isTap(12f, 12f))            // ~17px
        assertTrue(isTap(20f, 0f))
        assertTrue(isTap(-15f, 18f))
    }

    @Test
    fun `drift exactly at threshold is a tap`() {
        assertTrue(isTap(32f, 0f))             // 边界含（≤）
        assertTrue(isTap(0f, 32f))
    }

    @Test
    fun `drift beyond threshold is a drag`() {
        assertFalse(isTap(33f, 0f))
        assertFalse(isTap(0f, 100f))
    }

    @Test
    fun `diagonal drift is measured euclidean`() {
        // (24,24) 欧氏距离 ~33.9px > 32 → 真拖拽
        assertFalse(isTap(24f, 24f))
        // (22,22) ~31.1px ≤ 32 → 点按
        assertTrue(isTap(22f, 22f))
    }

    @Test
    fun `threshold constant sanity`() {
        // 阈值必须 > 拖拽 slop(12px)，且远小于屏宽——改值需同步注释
        assertTrue(TapGesture.MAX_DRIFT_PX > 12.0)
        assertTrue(TapGesture.MAX_DRIFT_PX < 200.0)
    }

    @Suppress("unused")
    private fun sqrtRef(x: Double) = sqrt(x)
}
