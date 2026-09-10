package com.xiaoduo.deskpet.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S31 板子 10.0.18.47 实测：rk/softwinner ROM（Android 11）窗口 edge-to-edge 时
 * insets 派发断裂——Compose WindowInsets.navigationBars 报 0，但系统导航栏窗口
 * （三键，56px）照常盖在内容上，BottomNavRow 下 1/3 被遮挡。
 *
 * 修复策略：有效底部 inset = max(派发值, 三键兜底值)。兜底仅在 navigation_mode=0
 * （三键）时启用，取 framework navigation_bar_height 资源（不依赖派发链）；
 * 手势模式不兜底（条矮，且资源值是三键高度会误垫空白）。
 */
class NavInsetPolicyTest {

    @Test
    fun `dispatched inset wins when healthy`() {
        // 派发正常（56）+ 三键模式 + 资源 56 → 56，不双垫
        assertEquals(56, NavInsetPolicy.effectiveBottomInset(56, NavInsetPolicy.NAV_MODE_BUTTONS, 56))
        // 派发 32（手势矮条）+ 手势模式 → 32（信任派发）
        assertEquals(32, NavInsetPolicy.effectiveBottomInset(32, NavInsetPolicy.NAV_MODE_GESTURE, 56))
    }

    @Test
    fun `broken dispatch falls back to resource height on button mode`() {
        // 核心：派发 0 + 三键 + 资源 56 → 56 兜底
        assertEquals(56, NavInsetPolicy.effectiveBottomInset(0, NavInsetPolicy.NAV_MODE_BUTTONS, 56))
        // 派发异常偏小（8）+ 三键 + 资源 56 → 取大 56
        assertEquals(56, NavInsetPolicy.effectiveBottomInset(8, NavInsetPolicy.NAV_MODE_BUTTONS, 56))
    }

    @Test
    fun `gesture mode never uses resource fallback`() {
        // 手势模式派发断（0）也不兜底——资源值是三键高度，垫了会顶出 56px 空白
        assertEquals(0, NavInsetPolicy.effectiveBottomInset(0, NavInsetPolicy.NAV_MODE_GESTURE, 56))
    }

    @Test
    fun `no nav bar at all stays zero`() {
        // 无系统栏设备（如桌面模式）：派发 0 + 三键模式但资源高度 0 → 0
        assertEquals(0, NavInsetPolicy.effectiveBottomInset(0, NavInsetPolicy.NAV_MODE_BUTTONS, 0))
    }

    @Test
    fun `larger of dispatched and fallback in button mode`() {
        // 派发值大于资源值（如提示条场景）→ 用派发值
        assertEquals(120, NavInsetPolicy.effectiveBottomInset(120, NavInsetPolicy.NAV_MODE_BUTTONS, 56))
    }
}
