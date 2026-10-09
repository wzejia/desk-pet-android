package com.xiaoduo.deskpet

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import com.xiaoduo.deskpet.util.PetLogger

/**
 * 密集架基础设置「打开小朵」直拉悬浮窗入口（2026-10-09 二次修复）。
 *
 * 一版实现（MainActivity extra + finish-before-setContent）真机仍见「页面跳转再关闭」：
 * ①不透明 Activity 启动/收场的任务切换动画肉眼可见；②ColorOS 对跨应用 service start
 * 按「后台启动」静默拒绝（进程明明前台仍判 bg，AMS W: Background start not allowed），
 * 密集架侧 startService 抛 IllegalStateException 后只能回退开 APP——跳转复现。
 *
 * 本入口 = 透明无动画的专用 trampoline：Activity 处于 resumed 时 startService 万无一失，
 * finish 前后零视觉（可感知的只有精灵窗出现）。无悬浮窗权限时转发主界面走授权引导。
 * manifest 侧 signature 权限把守：非同签名调用方 SecurityException，密集架侧回退开 APP。
 */
class OverlayLaunchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        suppressTransitions()
        when (OverlayLaunchPolicy.resolve(Settings.canDrawOverlays(this))) {
            OverlayLaunchPolicy.Action.START_OVERLAY_AND_FINISH -> {
                try {
                    startService(Intent(this, PetOverlayService::class.java))
                    PetLogger.i(TAG, "overlay direct launch: PetOverlayService started")
                } catch (e: Throwable) {
                    // 起服务失败不拦入口：仍 finish，日志留痕排雷
                    PetLogger.e(TAG, "overlay direct launch: start service failed: ${e.message}")
                }
                finish()
            }
            OverlayLaunchPolicy.Action.FORWARD_TO_MAIN -> {
                PetLogger.i(TAG, "overlay direct launch: no overlay permission, forward to main")
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }

    /** 本入口零过渡：开/收场动画全部压成 0 帧（任务切换动画由调用方 ActivityOptions 同步压 0）。 */
    private fun suppressTransitions() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        private const val TAG = "OverlayLaunch"

        /** 无权限→转正门（用户在主界面里授权）；有权限→起悬浮窗服务并收场。 */
        internal fun resolvePolicy(granted: Boolean): OverlayLaunchPolicy.Action =
            if (granted) OverlayLaunchPolicy.Action.START_OVERLAY_AND_FINISH
            else OverlayLaunchPolicy.Action.FORWARD_TO_MAIN
    }
}

/** 直拉入口的处置策略（纯逻辑，OverlayLaunchPolicyTest 锁行为）。 */
internal object OverlayLaunchPolicy {
    internal enum class Action { START_OVERLAY_AND_FINISH, FORWARD_TO_MAIN }

    internal fun resolve(granted: Boolean): Action =
        if (granted) Action.START_OVERLAY_AND_FINISH else Action.FORWARD_TO_MAIN
}
