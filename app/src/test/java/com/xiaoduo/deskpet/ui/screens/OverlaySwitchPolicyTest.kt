package com.xiaoduo.deskpet.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 变异锚（审计 ocr[79] 开关失步）：悬浮窗开关失步根源 = 未授权时请求开，写 pref 的副作用被阻止，
 * 而旧 RowSwitch `state = it` 无条件乐观回写 → Switch 显示 ON 与外部真源（pref=false）永久失步。
 * 修复 = RowSwitch 无状态化 + 本判定把「被阻止路径零写副作用」锁成纯函数契约：
 * 未授权请求开 → 只跳权限页（不落 pref、不启服务，UI 无乐观假象）。
 * 守卫被删（未授权也放行 ENABLE）→ BLOCKED 用例红。
 */
class OverlaySwitchPolicyTest {

    @Test
    fun `request on without permission routes to permission page with zero write side effects`() {
        val action = OverlaySwitchPolicy.decide(requestOn = true, canDrawOverlays = false)
        assertEquals(OverlaySwitchAction.BLOCKED_TO_PERMISSION, action)
        // 阻止路径不得等价任何「写」动作（失步根源约束）
        assertNotEquals(OverlaySwitchAction.ENABLE, action)
        assertNotEquals(OverlaySwitchAction.DISABLE, action)
    }

    @Test
    fun `request on with permission enables`() {
        assertEquals(
            OverlaySwitchAction.ENABLE,
            OverlaySwitchPolicy.decide(requestOn = true, canDrawOverlays = true),
        )
    }

    @Test
    fun `request off disables regardless of permission`() {
        assertEquals(
            OverlaySwitchAction.DISABLE,
            OverlaySwitchPolicy.decide(requestOn = false, canDrawOverlays = true),
        )
        assertEquals(
            OverlaySwitchAction.DISABLE,
            OverlaySwitchPolicy.decide(requestOn = false, canDrawOverlays = false),
        )
    }
}
