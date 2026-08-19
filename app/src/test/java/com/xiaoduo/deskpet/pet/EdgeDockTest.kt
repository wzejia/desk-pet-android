package com.xiaoduo.deskpet.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EdgeDock 单测（S10-1 吸附几何，用户四拍板 2026-08-18）：
 * 拖至左右缘松手 -> 半身贴边（窗中心恰在屏缘，OverlayClamp.clamp 不动点）；
 * 单击滑出完全可见贴缘；「当前是否贴边」由几何推导（窗中心 ≤0 / ≥屏宽）。
 * 反例：屏中松手不吸（dockSide=null）、全可见贴缘非贴边态（dockedSideOf=null）。
 * 尺寸取真机 PJD110：屏 1440×3168、精灵窗 645×698（奇数宽，刻意压整除对齐），zone=winW/2=322。
 */
class EdgeDockTest {

    private val winW = 645
    private val winH = 698
    private val screenW = 1440
    private val screenH = 3168
    private val zone = winW / 2 // 322

    // ---- dockSide：松手吸附判定 ----

    @Test
    fun `dock side hits LEFT when center within zone of left edge`() {
        assertEquals(EdgeDock.Side.LEFT, EdgeDock.dockSide(centerX = 0, screenW, zonePx = zone))
        assertEquals(EdgeDock.Side.LEFT, EdgeDock.dockSide(centerX = 100, screenW, zonePx = zone))
    }

    @Test
    fun `dock side hits RIGHT when center within zone of right edge`() {
        assertEquals(EdgeDock.Side.RIGHT, EdgeDock.dockSide(centerX = screenW, screenW, zonePx = zone))
        assertEquals(EdgeDock.Side.RIGHT, EdgeDock.dockSide(centerX = screenW - 100, screenW, zonePx = zone))
    }

    @Test
    fun `center at screen middle docks nowhere`() {
        // 反例样本：屏中松手 -> 两侧都够不着 -> 不吸、原位不动
        assertNull(EdgeDock.dockSide(centerX = screenW / 2, screenW, zonePx = zone))
    }

    @Test
    fun `zone boundary is inclusive`() {
        // 中心恰在 zone 边界 -> 吸（边界恰含）；越界 1px -> 不吸
        assertEquals(EdgeDock.Side.LEFT, EdgeDock.dockSide(centerX = zone, screenW, zonePx = zone))
        assertNull(EdgeDock.dockSide(centerX = zone + 1, screenW, zonePx = zone))
        assertEquals(EdgeDock.Side.RIGHT, EdgeDock.dockSide(centerX = screenW - zone, screenW, zonePx = zone))
        assertNull(EdgeDock.dockSide(centerX = screenW - zone - 1, screenW, zonePx = zone))
    }

    @Test
    fun `screen not wider than 2x zone returns null fallback`() {
        // 屏不宽于 2·zone（=屏不宽于窗）：中心两侧同命中、无从取舍 -> null 兜底不吸
        assertNull(EdgeDock.dockSide(centerX = 720, screenW = 1440, zonePx = 800))
    }

    // ---- dockedX / slideOutX：贴边与滑出坐标 ----

    @Test
    fun `dockedX places window center exactly on screen edge`() {
        val left = EdgeDock.dockedX(EdgeDock.Side.LEFT, winW, screenW)
        assertEquals(-winW / 2, left)
        assertEquals(0, left + winW / 2)
        val right = EdgeDock.dockedX(EdgeDock.Side.RIGHT, winW, screenW)
        assertEquals(screenW - winW / 2, right)
        assertEquals(screenW, right + winW / 2)
    }

    @Test
    fun `slideOutX keeps window fully visible inside screen`() {
        val left = EdgeDock.slideOutX(EdgeDock.Side.LEFT, winW, screenW)
        assertEquals(0, left)
        assertTrue("left=$left not fully on screen", left >= 0 && left + winW <= screenW)
        val right = EdgeDock.slideOutX(EdgeDock.Side.RIGHT, winW, screenW)
        assertEquals(screenW - winW, right)
        assertTrue("right=$right not fully on screen", right >= 0 && right + winW <= screenW)
    }

    @Test
    fun `dockedX is a fixed point of OverlayClamp clamp`() {
        // 贴边坐标是 clamp 的不动点（两侧各一）：持久化、重启恢复、onConfigurationChanged
        // 重钳位三者零新机制自然成立的前提
        val left = EdgeDock.dockedX(EdgeDock.Side.LEFT, winW, screenW)
        assertEquals(left, OverlayClamp.clamp(left, 0, winW, winH, screenW, screenH).first)
        val right = EdgeDock.dockedX(EdgeDock.Side.RIGHT, winW, screenW)
        assertEquals(right, OverlayClamp.clamp(right, 0, winW, winH, screenW, screenH).first)
    }

    // ---- dockedSideOf：贴边态几何推导（无额外状态位）----

    @Test
    fun `dockedSideOf derives side from half-body docked position`() {
        assertEquals(
            EdgeDock.Side.LEFT,
            EdgeDock.dockedSideOf(EdgeDock.dockedX(EdgeDock.Side.LEFT, winW, screenW), winW, screenW),
        )
        assertEquals(
            EdgeDock.Side.RIGHT,
            EdgeDock.dockedSideOf(EdgeDock.dockedX(EdgeDock.Side.RIGHT, winW, screenW), winW, screenW),
        )
    }

    @Test
    fun `dockedSideOf returns null when fully visible flush left`() {
        // 全可见贴缘（x=0，中心=winW/2 在屏内）-> 非贴边态
        assertNull(EdgeDock.dockedSideOf(0, winW, screenW))
    }

    @Test
    fun `dockedSideOf returns null at screen middle`() {
        assertNull(EdgeDock.dockedSideOf((screenW - winW) / 2, winW, screenW))
    }
}
