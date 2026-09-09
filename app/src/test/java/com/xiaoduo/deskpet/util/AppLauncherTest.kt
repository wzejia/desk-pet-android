package com.xiaoduo.deskpet.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S27-2：密集架跳转路由决策（纯 Kotlin，无 Android 依赖）。
 * 锁定兜底顺序：标准 LAUNCHER 入口优先 → 显式 fallback 组件次之 → 双无即失败。
 * Android 壳（PackageManager 查询 + startActivity）在 AppLauncher.start()，模拟器/板子实证。
 */
class AppLauncherTest {

    @Test
    fun `launcher entry present wins over fallback`() {
        assertEquals(
            AppLauncher.Route.VIA_LAUNCHER,
            AppLauncher.decide(launcherActivity = "com.ys.main.MainActivity", fallbackActivity = null),
        )
    }

    @Test
    fun `launcher entry beats fallback when both present`() {
        assertEquals(
            AppLauncher.Route.VIA_LAUNCHER,
            AppLauncher.decide(
                launcherActivity = "com.ys.main.MainActivity",
                fallbackActivity = "com.ys.main.LegacyActivity",
            ),
        )
    }

    @Test
    fun `fallback component used when launcher entry absent`() {
        assertEquals(
            AppLauncher.Route.VIA_FALLBACK,
            AppLauncher.decide(launcherActivity = null, fallbackActivity = "com.ys.main.MainActivity"),
        )
    }

    @Test
    fun `fail when neither launcher nor fallback`() {
        assertEquals(AppLauncher.Route.FAIL, AppLauncher.decide(null, null))
        assertEquals(AppLauncher.Route.FAIL, AppLauncher.decide("", "  "))
    }

    @Test
    fun `default target package is a plausible android package id`() {
        // Android 包名：点分段合法即可（Java 规范建议小写，但厂商包名可能含大写，如
        // compactShelf——系统/启动器不强制小写，monkey 实证可启动）；
        // 写错会导致 queries/启动静默失效
        val p = AppLauncher.DEFAULT_TARGET_PACKAGE
        assertTrue("package must be non-blank", p.isNotBlank())
        assertTrue(
            "package must match android id convention: $p",
            p.matches(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")),
        )
    }

    @Test
    fun `failure toast message is non-blank`() {
        assertTrue(AppLauncher.FAILURE_TOAST.isNotBlank())
    }

    @Test
    fun `effective target falls back to default when sp empty or blank`() {
        assertEquals(AppLauncher.DEFAULT_TARGET_PACKAGE, AppLauncher.effectiveTarget(""))
        assertEquals(AppLauncher.DEFAULT_TARGET_PACKAGE, AppLauncher.effectiveTarget("   "))
    }

    @Test
    fun `effective target uses sp override when present`() {
        assertEquals("com.example.app", AppLauncher.effectiveTarget("com.example.app"))
        assertEquals("com.example.app", AppLauncher.effectiveTarget("  com.example.app  "))
    }
}
