package com.xiaoduo.deskpet.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ROB-11：悬浮窗设置跳转候选顺序（纯逻辑件）。
 * 精确 overlay 设置页优先；OEM 精简系统可能无该 action 的解析目标 → 兜底应用详情页。
 */
class PermissionScreenActionsTest {

    @Test
    fun `primary overlay settings action first`() {
        val actions = overlaySettingsActions()
        assertEquals("android.settings.action.MANAGE_OVERLAY_PERMISSION", actions.first())
    }

    @Test
    fun `app details fallback second`() {
        val actions = overlaySettingsActions()
        assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS", actions.last())
    }

    @Test
    fun `candidates non-empty and ordered`() {
        val actions = overlaySettingsActions()
        assertEquals(2, actions.size)
        assertTrue(actions.toSet().size == actions.size)
    }
}
