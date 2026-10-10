package com.xiaoduo.deskpet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.xiaoduo.deskpet.chat.ChatViewModel
import com.xiaoduo.deskpet.ui.navigation.AppNavHost
import com.xiaoduo.deskpet.ui.theme.XiaoduoTheme
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.util.Prefs

/**
 * 单 Activity 入口（S0）：setContent + Compose Navigation 三页。
 * M1 的设置项/悬浮窗开关/导出日志已迁入 SettingsScreen，权限引导迁入 PermissionScreen。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 状态栏/导航栏透明融合页面：SystemBarStyle.auto 跟随系统深浅色
        // （浅色页→深色图标、深色页→浅色图标），与本 App 主题严格跟系统一致。
        enableEdgeToEdge()
        PetLogger.init(applicationContext)
        PetLogger.i("MainActivity", "onCreate, compose host started")
        handleViewLastRoundIntent(intent)
        healServicesByPrefs()
        setContent {
            XiaoduoTheme {
                AppNavHost()
            }
        }
    }

    /**
     * S8-6：App 已在前台时 startActivity 走 onNewIntent（launchMode 默认 standard
     * 下也会走，因 Activity 已在栈顶复用）。两条入口统一下沉意图到共享 chatVm。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // S14 白屏治理取证锚：singleTask 后所有拉起路径（悬浮窗菜单/KWS 通知/启动器）
        // 统一路由到同一实例——此日志出现即证明未走重建（对比 onCreate）
        PetLogger.i("MainActivity", "onNewIntent -> existing instance reused")
        handleViewLastRoundIntent(intent)
    }

    /**
     * S8-6 解析「查看最近一轮」intent extra：
     * 存在时下沉到共享 [ChatViewModel] 的 pendingScrollMessageId 一次性事件
     * （由 ChatScreen 的 LaunchedEffect 消费为 scrollToItem），并移除 extra 防旋转/重建重入。
     *
     * chatVm 桥接选型（记 implementation-notes）：
     * - 方案①本类用 `ViewModelProvider(this)[ChatViewModel::class.java]` 提前取 Activity 级实例
     *   ——与 AppNavHost 里 `viewModel()` 默认 owner（LocalViewModelStoreOwner = 当前 Activity）
     *   命中同一 ViewModelStore，是同一实例，写 pending 立刻对 ChatScreen 可见。
     * - 方案②（Activity 持标志、AppNavHost/ChatScreen 启动时读）需把标志从 Activity 透传进
     *   Compose 树，要么加 @Composable 参数（污染 chat 域接口）、要么走 CompositionLocal
     *   （新基建不划算）。方案① 0 基建、0 跨边界透传，最简，故选。
     * 时序：onCreate 早于 setContent，但 AndroidViewModel 经 SavedStateViewModelFactory
     * 同步构造无延迟，pending 写入在 setContent 渲染 ChatScreen 前已就位；ChatScreen
     * 的 LaunchedEffect 启动即读到非空 pending 触发滚动。
     */
    private fun handleViewLastRoundIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_VIEW_LAST_ROUND, false) != true) return
        intent.removeExtra(EXTRA_VIEW_LAST_ROUND) // 防配置变更重建时重复触发
        ViewModelProvider(this)[ChatViewModel::class.java].requestScrollToLastRound()
        PetLogger.i("MainActivity", "view last round requested")
    }

    /**
     * P1 服务自愈：App 启动时按用户持久化偏好恢复服务。
     * 背景：force-stop/重装后 START_STICKY 失效，服务全死但 pref 仍为 true（用户期望
     * 「我开着它就该在」）。仅恢复用户显式开过且权限仍在的服务——这是已授权路径的恢复，
     * 非开机自启（不加 BOOT_COMPLETED）。
     * 选 MainActivity.onCreate 而非 App.onCreate：App.onCreate 也会被服务 STICKY 重启
     * 在后台态拉起，此时 startForegroundService 会撞 Android 12+ 后台 FGS 启动限制；
     * Activity 启动必处于前台，拉起合法。onCreate 随配置变更重入也无害（两服务均幂等）。
     */
    private fun healServicesByPrefs() {
        val overlayGranted = Settings.canDrawOverlays(this)
        val overlayPref = Prefs.overlayEnabled(this)
        if (overlayPref && overlayGranted) {
            try {
                startService(Intent(this, PetOverlayService::class.java))
                PetLogger.i("MainActivity", "self-heal: PetOverlayService start requested (pref=true, granted)")
            } catch (e: Exception) {
                // ColorOS 后台/启动限制不静默：透传日志，交给真机日志排雷
                PetLogger.e("MainActivity", "self-heal: start PetOverlayService failed: ${e.message}")
            }
        } else {
            PetLogger.i("MainActivity", "self-heal: skip overlay (pref=$overlayPref, granted=$overlayGranted)")
        }

        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val kwsPref = Prefs.kwsEnabled(this)
        if (kwsPref && micGranted) {
            try {
                ContextCompat.startForegroundService(this, Intent(this, VoiceForegroundService::class.java))
                PetLogger.i("MainActivity", "self-heal: VoiceForegroundService start requested (pref=true, mic granted)")
            } catch (e: Exception) {
                PetLogger.e("MainActivity", "self-heal: start VoiceForegroundService failed: ${e.message}")
            }
        } else {
            PetLogger.i("MainActivity", "self-heal: skip KWS (pref=$kwsPref, micGranted=$micGranted)")
        }
    }

    companion object {
        /** S8-6：悬浮窗「查看最近一轮」菜单拉起 Activity 的 intent extra 键。 */
        const val EXTRA_VIEW_LAST_ROUND = "extra_view_last_round"

    }
}

/** 进程内记录悬浮窗服务运行态（M1 单进程足够）。
 *  活性记账（spec §4.3，2026-10-10）：running 只能经 markStarted/markCleared 变更；
 *  service 周期喂 markHeartbeat 续期，isAlive = 已启动且心跳未过期——
 *  防 service 死于 onDestroy 之外（异常路径未清位）时设置页 stale-true 误判。 */
object PetOverlayState {
    /** service 心跳周期。 */
    const val HEARTBEAT_INTERVAL_MS: Long = 30_000L

    /** 心跳超过该时长未续视为 stale（3 个周期，容忍单次调度抖动）。 */
    const val STALE_AFTER_MS: Long = 90_000L

    @Volatile private var startedFlag = false
    @Volatile private var lastMarkUptimeMs = 0L

    val running: Boolean get() = startedFlag

    fun markStarted(nowUptimeMs: Long) {
        startedFlag = true
        lastMarkUptimeMs = nowUptimeMs
    }

    /** 续活性戳。不写 startedFlag（复活防线，PetOverlayStateTest 锁）——
     *  startedFlag 短路在 isAlive 兜底，此处无需重复守卫。 */
    fun markHeartbeat(nowUptimeMs: Long) {
        lastMarkUptimeMs = nowUptimeMs
    }

    fun markCleared() {
        startedFlag = false
        lastMarkUptimeMs = 0L
    }

    fun isAlive(nowUptimeMs: Long): Boolean =
        startedFlag && nowUptimeMs - lastMarkUptimeMs <= STALE_AFTER_MS
}
