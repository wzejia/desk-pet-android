package com.xiaoduo.deskpet

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 密集架基础设置「打开小朵」直拉入口（2026-10-09，OverlayLaunchActivity trampoline）的启动策略。
 *
 * 简报反例：
 * - 悬浮窗权限未授予时必须转发主界面（用户能在界面里授权），不许黑屏 finish。
 * - 策略只看权限位：直拉入口不存在「带 extra 与否」的分叉。
 */
class OverlayLaunchPolicyTest {

    @Test
    fun overlayOnly_withPermission_startsOverlayAndFinish() {
        assertEquals(
            OverlayLaunchPolicy.Action.START_OVERLAY_AND_FINISH,
            OverlayLaunchPolicy.resolve(granted = true),
        )
    }

    @Test
    fun overlayOnly_withoutPermission_fallsBackToUi() {
        assertEquals(
            OverlayLaunchPolicy.Action.FORWARD_TO_MAIN,
            OverlayLaunchPolicy.resolve(granted = false),
        )
    }

}
