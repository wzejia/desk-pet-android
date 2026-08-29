package com.xiaoduo.deskpet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S17 全屏布局计算器单测（纯 JVM，设备矩阵 + 不变量性质测试）。
 * 三合一适配（用户 2026-08-29 拍板）：跨区字幕 + 比例系统化 + 列宽钳制。
 * 黄金值来源：designs/xiaoduo-motion-preview/字幕适配方案对比.html「★ 推荐」变体。
 */
class FullscreenVoiceLayoutTest {

    private fun a(w: Float, h: Float) = FullscreenVoiceLayout.anchors(w, h)

    /** 1. OPPO 手机 360×792：核心回归保障--宠物几何与 S14-2 验收值一致（零回归） */
    @Test
    fun `phone portrait keeps s14 geometry`() {
        val a = a(360f, 792f)
        assertEquals(223f, a.petWidthDp, 1f)       // 0.62×360（高约束 329/上限 440 均不生效）
        assertEquals(212f, a.petTopDp, 2f)          // 42% 中心 - 半高
        assertEquals(96f, a.subtitleMaxHeightDp, 6f) // 跨区实际值（旧固定 112 的近似）
        assertEquals(312f, a.subtitleColumnWidthDp, 1f) // 360-48（520 钳制不生效）
        assertEquals(1f, a.fontScale, 0.001f)
    }

    /** 2. rk3568 板子 1080×1920：宠物钳 440、字幕跨区放大 ~4 倍、列宽/字号钳到位 */
    @Test
    fun `board big screen expands subtitle zone and clamps column`() {
        val a = a(1080f, 1920f)
        assertEquals(440f, a.petWidthDp, 0.1f)      // 上限生效（0.62×宽=670 被钳）
        assertEquals(418f, a.subtitleMaxHeightDp, 6f) // 跨区 = 宠物底+16 -> 状态区顶-12
        assertEquals(520f, a.subtitleColumnWidthDp, 0.1f)
        assertEquals(1.3f, a.fontScale, 0.001f)
    }

    /** 3. 平板横屏 960×540：宠物按高约束缩到 224dp 且上让位，64dp 字幕兜底区不压宠物 */
    @Test
    fun `tablet landscape shrinks pet by height cap and yields upward`() {
        val a = a(960f, 540f)
        assertEquals(224f, a.petWidthDp, 1f)        // 0.45×高 等比约束生效
        val zoneTop = 540f - a.subtitleBottomDp - a.subtitleMaxHeightDp
        assertEquals(64f, a.subtitleMaxHeightDp, 0.1f) // 跨区不足 -> 兜底
        assertEquals(a.petBottomDp + 16f, zoneTop, 0.1f) // 上让位后恰好让出 16dp 间距
        assertTrue(a.petTopDp < 105f)               // 自 42% 中心位置上移
        assertEquals(520f, a.subtitleColumnWidthDp, 0.1f)
    }

    /** 4. 极端横条 1280×400：垂直放不下时缩宠物到可容纳，布局不破 */
    @Test
    fun `extreme wide bar shrinks pet to fit`() {
        val a400 = a(1280f, 400f)
        assertEquals(0f, a400.petTopDp, 0.1f)
        val zoneTop = 400f - a400.subtitleBottomDp - a400.subtitleMaxHeightDp
        assertEquals(a400.petBottomDp + 16f, zoneTop, 0.1f)
        assertEquals(64f, a400.subtitleMaxHeightDp, 0.1f)
    }

    /** 5. 不变量性质测试：设备矩阵遍历（竖/横/方/极端），四大不变量恒成立 */
    @Test
    fun `invariants hold across device matrix`() {
        val widths = listOf(320f, 360f, 412f, 600f, 720f, 800f, 960f, 1080f, 1280f)
        val heights = listOf(320f, 400f, 540f, 640f, 792f, 800f, 960f, 1080f, 1280f, 1920f, 2160f)
        var checked = 0
        for (w in widths) {
            for (h in heights) {
                val a = a(w, h)
                // ① 宠物在屏内且不压字幕区（间距 ≥16dp）
                assertTrue("petTop<0 @${w}x$h}", a.petTopDp >= 0f)
                assertTrue("pet exceeds width @${w}x$h}", a.petWidthDp <= w)
                val zoneTop = h - a.subtitleBottomDp - a.subtitleMaxHeightDp
                assertTrue("pet overlaps subtitle @${w}x$h", a.petBottomDp + 16f <= zoneTop + 0.5f)
                // ② 字幕区不压状态区（间距 ≥12dp）
                val zoneBottom = h - a.subtitleBottomDp
                val statusZoneTop = h - a.statusBottomDp - FullscreenVoiceLayout.STATUS_ZONE_HEIGHT_DP
                assertTrue("subtitle overlaps status @${w}x$h", zoneBottom + 12f <= statusZoneTop + 0.5f)
                // ③ 字幕区高度在 [64, 28% 屏高]；列宽在 (0, 520]
                assertTrue(a.subtitleMaxHeightDp in 64f..0.28f * h + 0.5f)
                assertTrue(a.subtitleColumnWidthDp > 0f && a.subtitleColumnWidthDp <= 520f + 0.5f)
                // ④ 字号缩放钳制
                assertTrue(a.fontScale in 1f..1.3f)
                checked++
            }
        }
        assertTrue(checked > 80)
    }
}
