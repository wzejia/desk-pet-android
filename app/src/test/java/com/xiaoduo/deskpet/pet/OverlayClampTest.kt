package com.xiaoduo.deskpet.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OverlayClamp 单测（真机 P2 返工后语义）：
 * 精灵在窗内居中 → 钳窗体中心 = 钳精灵中心，每轴窗中心必须钳在屏内 [0, screen]，
 * 保证精灵本体至少半身可见、可辨可拖；窗口比屏幕大时居中兜底。
 * （旧「窗体留 margin 把手」语义：把手可落在精灵透明区，肉眼等同消失，已废弃。）
 */
class OverlayClampTest {

    private val winW = 200
    private val winH = 300
    private val screenW = 1080
    private val screenH = 2400

    @Test
    fun `position inside screen is unchanged`() {
        val (x, y) = OverlayClamp.clamp(300, 900, winW, winH, screenW, screenH)
        assertEquals(300, x)
        assertEquals(900, y)
    }

    @Test
    fun `extreme negative clamps window center to top-left screen edge`() {
        // 窗中心钳到 0 → x = -winW/2, y = -winH/2（精灵半身可见）
        val (x, y) = OverlayClamp.clamp(-9999, -9999, winW, winH, screenW, screenH)
        assertEquals(-winW / 2, x)
        assertEquals(-winH / 2, y)
    }

    @Test
    fun `overflow right and bottom clamps window center to screen edge`() {
        // 窗中心钳到 screen → x = screenW - winW/2, y = screenH - winH/2
        val (x, y) = OverlayClamp.clamp(99999, 99999, winW, winH, screenW, screenH)
        assertEquals(screenW - winW / 2, x)
        assertEquals(screenH - winH / 2, y)
    }

    @Test
    fun `exact boundary values are kept`() {
        // 边界：窗中心恰在 0 / screen 上，不动
        val (x1, y1) = OverlayClamp.clamp(-winW / 2, screenH - winH / 2, winW, winH, screenW, screenH)
        assertEquals(-winW / 2, x1)
        assertEquals(screenH - winH / 2, y1)
        val (x2, y2) = OverlayClamp.clamp(screenW - winW / 2, -winH / 2, winW, winH, screenW, screenH)
        assertEquals(screenW - winW / 2, x2)
        assertEquals(-winH / 2, y2)
    }

    @Test
    fun `window larger than screen falls back to center`() {
        val (x, y) = OverlayClamp.clamp(500, 500, winW = 2000, winH = 5000, screenW, screenH)
        assertEquals((screenW - 2000) / 2, x)
        assertEquals((screenH - 5000) / 2, y)
    }

    @Test
    fun `small offscreen drag with center on screen is preserved`() {
        // 拖出左缘 30px 但窗中心（70）仍在屏内：保持用户意图不被吸回
        val (x, y) = OverlayClamp.clamp(-30, 0, winW, winH, screenW, screenH)
        assertEquals(-30, x)
        assertEquals(0, y)
    }

    // ---- clampedBubbleLeft（S3 返工#2：气泡贴边不裁字）----

    @Test
    fun `bubble centered over pet when room allows`() {
        // 宠物居中：natural = 540 - 150 = 390，在 [8, 772] 内不动
        assertEquals(390, OverlayClamp.clampedBubbleLeft(petCenterX = 540, bubbleW = 300, screenW, margin = 8))
    }

    @Test
    fun `bubble shifts right when pet hugs left edge`() {
        // natural = 60 - 150 = -90（会被屏缘裁字）→ 钳到 margin
        assertEquals(8, OverlayClamp.clampedBubbleLeft(petCenterX = 60, bubbleW = 300, screenW, margin = 8))
    }

    @Test
    fun `bubble shifts left when pet hugs right edge`() {
        // natural = 1020 - 150 = 870 > max = 1080 - 8 - 300 = 772 → 钳到 772
        assertEquals(772, OverlayClamp.clampedBubbleLeft(petCenterX = 1020, bubbleW = 300, screenW, margin = 8))
    }

    @Test
    fun `bubble wider than screen falls back to center`() {
        assertEquals(
            (screenW - 2000) / 2,
            OverlayClamp.clampedBubbleLeft(petCenterX = 540, bubbleW = 2000, screenW, margin = 8),
        )
    }

    // ---- 真机 P2：显示尺寸变化（分辨率切换/分屏/折叠）后按新 metrics 重 clamp ----
    // 场景来源：OPPO PJD110 实测，持久化绝对 px (540,1414)，窗 617x668；
    // wm size 720x1600 后旧位置精灵整体出屏（像素均值=纯壁纸色，肉眼不可见）。

    @Test
    fun `position persisted at 1080x2400 reclamps into 720x1600 with sprite half visible`() {
        val (x, y) = OverlayClamp.clamp(
            540, 1414, winW = 617, winH = 668,
            screenW = 720, screenH = 1600,
        )
        // 窗中心 (540+308, 1414+334) = (848,1748) 钳到 (720,1600) → 窗左上 (412,1266)
        assertEquals(412, x)
        assertEquals(1266, y)
        // 精灵在窗内居中 → 精灵中心 = 窗中心在屏缘上，本体至少半身可见
        assertEquals(720, x + 617 / 2)
        assertEquals(1600, y + 668 / 2)
    }

    @Test
    fun `reclamped window center always stays on screen per axis`() {
        // 多组收缩尺寸 × 极端持久化位置：clamp 后窗中心每轴必须在 [0, screen] 内
        // （窗比屏宽时走居中兜底，中心 = screen/2 同样满足）
        val cases = listOf(
            Pair(720, 1600), // wm size 720x1600
            Pair(540, 1200), // 分屏半宽
            Pair(1440, 3168), // 原生全尺寸（不 clamp 也应满足）
        )
        val positions = listOf(-99999 to -99999, 99999 to 99999, 540 to 1414, 0 to 0)
        for ((sw, sh) in cases) {
            for ((px, py) in positions) {
                val (x, y) = OverlayClamp.clamp(px, py, winW = 617, winH = 668, sw, sh)
                val cx = x + 617 / 2
                val cy = y + 668 / 2
                assertTrue("centerX=$cx on screen@${sw}x${sh} from ($px,$py)", cx in 0..sw)
                assertTrue("centerY=$cy on screen@${sw}x${sh} from ($px,$py)", cy in 0..sh)
            }
        }
    }

    @Test
    fun `window wider than shrunken screen falls back to center instead of throwing`() {
        // 窗口比新屏还宽：coerceIn 上下界反转，必须走居中兜底
        val (x, y) = OverlayClamp.clamp(
            540, 1414, winW = 617, winH = 668,
            screenW = 360, screenH = 640,
        )
        assertEquals((360 - 617) / 2, x)
        assertEquals((640 - 668) / 2, y)
    }

    @Test
    fun `bubble left reclamps to shrunken screen width`() {
        // 宠物中心 849（旧 1080 屏位置）在 720 屏上：气泡 300 宽，natural=699 > max=412 → 钳回屏内
        assertEquals(
            720 - 8 - 300,
            OverlayClamp.clampedBubbleLeft(petCenterX = 849, bubbleW = 300, screenW = 720, margin = 8),
        )
    }
}
