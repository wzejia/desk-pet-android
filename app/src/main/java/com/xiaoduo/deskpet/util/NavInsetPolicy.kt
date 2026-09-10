package com.xiaoduo.deskpet.util

/**
 * 底部系统导航栏 inset 决策（S31，纯函数供 JVM 单测）。
 *
 * 背景：rk3568_s（10.0.18.47，Android 11 softwinner ROM）实测——窗口 edge-to-edge
 * 时 insets 派发断裂，Compose `WindowInsets.navigationBars` 报 0，而系统三键导航栏
 * 窗口照常盖在内容上（BottomNavRow 下 1/3 被遮挡、热区下半失灵）。
 *
 * 策略：有效 inset = max(派发值, 三键兜底值)。兜底仅在三键模式（navigation_mode=0）
 * 启用，取 framework `navigation_bar_height` 资源（ROM 想瞒也瞒不住的静态事实）；
 * 手势模式不兜底——手势条极矮，且资源值是三键高度，垫了会在手势设备顶出空白。
 */
object NavInsetPolicy {

    /** Settings.Secure navigation_mode：0=三键按钮。 */
    const val NAV_MODE_BUTTONS = 0

    /** Settings.Secure navigation_mode：1=手势。 */
    const val NAV_MODE_GESTURE = 1

    /** 有效底部导航 inset（px）：派发值与三键兜底取大。 */
    fun effectiveBottomInset(dispatchedPx: Int, navigationMode: Int, resourceBarHeightPx: Int): Int {
        val fallback = if (navigationMode == NAV_MODE_BUTTONS) resourceBarHeightPx else 0
        return maxOf(dispatchedPx, fallback)
    }
}
