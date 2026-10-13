package com.xiaoduo.deskpet.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.xiaoduo.deskpet.chat.ChatViewModel
import com.xiaoduo.deskpet.ui.screens.ChatScreen
import com.xiaoduo.deskpet.ui.screens.FullscreenVoiceScreen
import com.xiaoduo.deskpet.ui.screens.PermissionScreen
import com.xiaoduo.deskpet.ui.screens.SettingsScreen

object Routes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val PERMISSION = "permission"
    const val FULLSCREEN_VOICE = "fullscreen_voice"
}

/**
 * 单 Activity + Compose Navigation 三页：Chat（主）/ Settings / Permission
 * （ADR 0002：废弃独立聊天记录页，看历史＝进入会话，会话管理全在侧栏）。
 * chatVm 在 NavHost 外创建（Activity 级）：navigation-compose 目的地内 viewModel() 默认
 * owner 是各自 NavBackStackEntry，各页自建会让聊天页消息流状态分裂。
 */
@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    val chatVm: ChatViewModel = viewModel()
    NavHost(navController = nav, startDestination = Routes.CHAT) {
        composable(Routes.CHAT) {
            // edge-to-edge insets 适配：外层 Box 用主题 background 铺满（状态栏/导航栏条带区
            // 与页面同色）。安全区分工：状态栏不在这里统一垫——抽屉 sheet 需顶到屏幕顶缘
            // （ChatGPT 形态，N2 留痕 D3），改由 ChatScreen 内容侧（PetStatusBar）自垫；
            // 导航栏/键盘仍在此垫，抽屉内容本身有 ModalDrawerSheet 默认 insets 兜底。
            // 其余三页（TopAppBar + Scaffold 默认 safeDrawing）已自处理，不需要包。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        // S31：navigationBarsPadding 移除——导航行自垫 24dp 底距（均匀间距方案，
                        // 原系统 inset 垫高 ~64px 不可控且不一致）；imePadding 保留（键盘抬升）。
                        // 抽屉 sheet 自带 insets 兑底，其余三页在各自 Scaffold 内不受影响。
                        .imePadding(),
                ) {
                    ChatScreen(
                        onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                        onOpenFullscreenVoice = {
                            // launchSingleTop：快速连点 mic 不压两实例（守卫四问#4）
                            nav.navigate(Routes.FULLSCREEN_VOICE) { launchSingleTop = true }
                        },
                        vm = chatVm,
                    )
                }
            }
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenPermissions = { nav.navigate(Routes.PERMISSION) },
            )
        }
        composable(Routes.PERMISSION) {
            PermissionScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.FULLSCREEN_VOICE) {
            // chatVm 复用 Activity 级共享实例：语音轮经 transcriptSink 流入同一 VM，
            // 全屏转写渲染与聊天页消息流同一事实源（S7-d）
            FullscreenVoiceScreen(
                onExit = { nav.popBackStack() },
                chatVm = chatVm,
            )
        }
    }
}
