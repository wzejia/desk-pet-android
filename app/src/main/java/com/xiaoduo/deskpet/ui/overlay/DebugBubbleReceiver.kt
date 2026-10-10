package com.xiaoduo.deskpet.ui.overlay

import com.xiaoduo.deskpet.api.BubbleOwner
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.xiaoduo.deskpet.PetOverlayService
import com.xiaoduo.deskpet.util.PetLogger

/**
 * S3 验收期调试入口：adb 触发气泡（气泡的真实调用方 S2/S4 走 PetOverlayService 服务级 API）。
 * 用法：
 *   adb shell am broadcast -a com.xiaoduo.deskpet.DEBUG_BUBBLE --es text "你好" com.xiaoduo.deskpet
 *   adb shell am broadcast -a com.xiaoduo.deskpet.DEBUG_BUBBLE_APPEND --es text "，世界" com.xiaoduo.deskpet
 *   adb shell am broadcast -a com.xiaoduo.deskpet.DEBUG_BUBBLE_HIDE com.xiaoduo.deskpet
 *
 * 仅 debug 包生效（S3 返工#6：receiver exported=true，release 上必须拒绝外部触发）。
 * 注：等价 BuildConfig.DEBUG——AGP 8.5.2 默认不生成 BuildConfig 且 gradle 文件禁碰，
 * 用 ApplicationInfo.FLAG_DEBUGGABLE 判定，语义一致。
 */
class DebugBubbleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val svc = PetOverlayService.instance
        if (svc == null) {
            PetLogger.w(TAG, "overlay service not running, action=${intent.action} dropped")
            return
        }
        when (intent.action) {
            ACTION_SHOW -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: DEFAULT_TEXT
                PetLogger.i(TAG, "debug showBubble: ${text.length} chars")
                // 认领新 debug round（接管气泡），round 存 companion 供后续 append/hide 广播复用
                debugRound = svc.showBubble(BubbleOwner.DEBUG, text)
            }
            ACTION_APPEND -> {
                val delta = intent.getStringExtra(EXTRA_TEXT) ?: ""
                PetLogger.i(TAG, "debug appendBubble: ${delta.length} chars")
                svc.appendBubble(BubbleOwner.DEBUG, debugRound, delta)
            }
            ACTION_HIDE -> {
                PetLogger.i(TAG, "debug hideBubble")
                svc.hideBubble(BubbleOwner.DEBUG, debugRound)
            }
        }
    }

    companion object {
        private const val TAG = "DebugBubble"
        const val ACTION_SHOW = "com.xiaoduo.deskpet.DEBUG_BUBBLE"
        const val ACTION_APPEND = "com.xiaoduo.deskpet.DEBUG_BUBBLE_APPEND"
        const val ACTION_HIDE = "com.xiaoduo.deskpet.DEBUG_BUBBLE_HIDE"
        const val EXTRA_TEXT = "text"
        private const val DEFAULT_TEXT = "你好，我是小朵，很高兴见到你！"

        /** manifest receiver 每次广播新实例，round 存进程级 companion。 */
        @Volatile
        private var debugRound = 0
    }
}
