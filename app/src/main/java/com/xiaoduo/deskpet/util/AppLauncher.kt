package com.xiaoduo.deskpet.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * 密集架 App 跳转（S27）：聊天页导航行中键与悬浮窗长按菜单「打开密集架」共用此入口。
 * 路由决策 [decide] 纯 Kotlin（JVM 单测锁定兜底顺序），[start] 是 Android 壳：
 * PackageManager 查标准 LAUNCHER 入口 → startActivity，失败返回 false 由调用方 toast。
 *
 * 包可见性：targetSdk 30+ 需 manifest <queries> 声明才能看见目标包——本项目用
 * 「LAUNCHER intent 通配」声明（见 AndroidManifest.xml），目标包改名只动 [TARGET_PACKAGE]。
 */
object AppLauncher {

    /**
     * 密集架主应用包名（S31 板子实证）：看护服务与主应用同包，含 LAUNCHER 入口
     * com.sdcard.ui.StartActivity（kiosk 桌面即它）。
     */
    const val TARGET_PACKAGE = "com.ys.ys_receiver"

    /** 无标准 LAUNCHER 入口时的显式组件兜底（kiosk 应用可能没桌面入口）；null=未配置。 */
    val FALLBACK_ACTIVITY_CLASS: String? = null

    const val FAILURE_TOAST = "未找到密集架应用"

    enum class Route { VIA_LAUNCHER, VIA_FALLBACK, FAIL }

    /**
     * 路由决策（纯函数）：launcher 入口优先；无 launcher 入口且配置了 fallback 组件则
     * 显式启动；双无即失败。决策与 Android 类型解耦，JVM 可测。
     */
    fun decide(launcherActivity: String?, fallbackActivity: String?): Route = when {
        !launcherActivity.isNullOrBlank() -> Route.VIA_LAUNCHER
        !fallbackActivity.isNullOrBlank() -> Route.VIA_FALLBACK
        else -> Route.FAIL
    }

    /**
     * 发起跳转。返回是否成功发起（false = 包不可见/无入口/启动异常），调用方负责 toast。
     * NEW_TASK 必带：悬浮窗 Service 路径无任务栈；Activity 路径带之亦无害。
     */
    fun start(context: Context): Boolean {
        val launcher = runCatching {
            context.packageManager.getLaunchIntentForPackage(TARGET_PACKAGE)
        }.getOrNull()
        return when (decide(launcher?.`package`, FALLBACK_ACTIVITY_CLASS)) {
            Route.VIA_LAUNCHER -> startCompat(context, launcher!!)
            Route.VIA_FALLBACK -> startCompat(
                context,
                Intent().setClassName(TARGET_PACKAGE, FALLBACK_ACTIVITY_CLASS!!),
            )
            Route.FAIL -> false
        }
    }

    private fun startCompat(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    /**
     * 跳转 + 失败 toast（聊天页导航行与悬浮窗菜单两入口共用，行为一致）。
     * 返回是否成功发起，调用方按需记日志。
     */
    fun startWithToast(context: Context): Boolean {
        val ok = start(context)
        if (!ok) {
            android.widget.Toast.makeText(context, FAILURE_TOAST, android.widget.Toast.LENGTH_SHORT).show()
        }
        return ok
    }
}
