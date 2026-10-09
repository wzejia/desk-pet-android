package com.xiaoduo.deskpet

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 密集架基础设置「打开小朵」改拉悬浮窗（2026-10-09）：MainActivity 收到
 * extra_launch_overlay_only=true 时的启动策略。
 *
 * 简报反例：
 * - 悬浮窗权限未授予时必须回落正常 UI（用户能在界面里授权），不许黑屏 finish。
 * - 非 overlay-only 的常规启动不受影响（返回 FALLBACK_TO_UI，走原 heal+setContent 流程）。
 */
class MainActivityOverlayLaunchPolicyTest {

    @Test
    fun overlayOnly_withPermission_startsOverlayAndFinish() {
        assertEquals(
            MainActivity.OverlayLaunchAction.START_OVERLAY_AND_FINISH,
            MainActivity.resolveOverlayOnlyLaunch(overlayOnly = true, overlayGranted = true),
        )
    }

    @Test
    fun overlayOnly_withoutPermission_fallsBackToUi() {
        assertEquals(
            MainActivity.OverlayLaunchAction.FALLBACK_TO_UI,
            MainActivity.resolveOverlayOnlyLaunch(overlayOnly = true, overlayGranted = false),
        )
    }

    @Test
    fun normalLaunch_withoutExtra_fallsBackToUi() {
        assertEquals(
            MainActivity.OverlayLaunchAction.FALLBACK_TO_UI,
            MainActivity.resolveOverlayOnlyLaunch(overlayOnly = false, overlayGranted = true),
        )
        assertEquals(
            MainActivity.OverlayLaunchAction.FALLBACK_TO_UI,
            MainActivity.resolveOverlayOnlyLaunch(overlayOnly = false, overlayGranted = false),
        )
    }
}
