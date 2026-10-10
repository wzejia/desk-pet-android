package com.xiaoduo.deskpet.ui.screens

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xiaoduo.deskpet.PetOverlayService
import com.xiaoduo.deskpet.PetOverlayState
import com.xiaoduo.deskpet.ui.theme.XiaoduoDimens
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.util.AppLauncher
import com.xiaoduo.deskpet.util.PrefContract
import com.xiaoduo.deskpet.util.Prefs

/**
 * 设置页（S0 迁移自 M1 MainActivity，功能不丢）：
 * 悬浮窗开关（canDrawOverlays 检查 + 未授权跳权限页）/ 缩放 / 气泡开关 /
 * 导出日志（FileProvider ACTION_SEND）/ 端点与账号编辑 / 版本号。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenPermissions: () -> Unit) {
    val context = LocalContext.current

    // 悬浮窗状态：回前台时刷新（用户可能刚从系统授权页回来；pref 也刷新——
    // 悬浮窗菜单「隐藏悬浮窗」落 pref=false 后本页若仍存活，开关态需同步）
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    // P1：开关 UI 态 = 用户偏好 && 授权（「我开着它就该在」，与 App 启动自愈语义一致），
    // 不再绑运行态（force-stop 后服务死但 pref=true，开关不应显示关）。
    var overlayEnabledPref by remember { mutableStateOf(Prefs.overlayEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
                overlayEnabledPref = Prefs.overlayEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = XiaoduoDimens.SpaceM),
        ) {
            // ---- 悬浮窗 ----
            SectionTitle("悬浮窗")
            RowSwitch(
                title = "悬浮窗开关",
                subtitle = if (overlayGranted) "权限已授权" else "未授权悬浮窗权限，打开需先授权",
                checked = overlayGranted && overlayEnabledPref,
                enabled = true,
            ) { checked ->
                if (checked) {
                    if (Settings.canDrawOverlays(context)) {
                        Prefs.setOverlayEnabled(context, true)
                        overlayEnabledPref = true
                        PetLogger.i("Settings", "start overlay service")
                        context.startService(Intent(context, PetOverlayService::class.java))
                    } else {
                        PetLogger.w("Settings", "overlay toggle blocked: permission not granted")
                        overlayGranted = false
                        onOpenPermissions()
                    }
                } else {
                    // 显式关闭：先落 pref=false（P1 自愈不再拉起）再停服务——
                    // 此顺序与 PetOverlayService.onDestroy 注释约定一致（stopService 前自管 pref）
                    Prefs.setOverlayEnabled(context, false)
                    overlayEnabledPref = false
                    PetLogger.i("Settings", "stop overlay service")
                    context.stopService(Intent(context, PetOverlayService::class.java))
                }
            }
            TextButtonRow("悬浮窗权限引导", onOpenPermissions)

            HorizontalDivider(modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS))

            // ---- 链路自检（S1-tail，兼 U6 调试页；置顶免滚动，便于 adb 排雷）----
            SectionTitle("链路自检")
            LinkSelfCheck(context)

            HorizontalDivider(modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS))

            // ---- 语音自检（S4：按住说话全链路，唯一新增区块）----
            SectionTitle("语音自检")
            VoiceSelfCheck(context)

            HorizontalDivider(modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS))

            // ---- 语音唤醒（S5：KWS 常开前台服务 + 能量门控校准入口）----
            SectionTitle("语音唤醒")
            KwsSection(context)
            // S7-d：全屏语音模式转写开关（默认关＝纯 TTS+小朵动画；仿气泡开关纯 pref 写法）
            RowSwitch(
                title = "全屏转写",
                subtitle = "全屏语音模式下在屏幕底部显示回复文字",
                checked = Prefs.fullscreenTranscript(context),
                enabled = true,
            ) { checked ->
                Prefs.setFullscreenTranscript(context, checked)
                PetLogger.i("Settings", "fullscreen_transcript -> $checked")
            }
            // S7-e：AEC spike 取证入口（仅 debug 渲染，release 不可见）
            AecSpikeSection(context)

            HorizontalDivider(modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS))

            // ---- 宠物 ----
            SectionTitle("宠物")
            ScaleSlider(context)
            RowSwitch(
                title = "气泡开关",
                subtitle = "关闭后悬浮窗不再弹出回复气泡",
                checked = Prefs.showBubble(context),
                enabled = true,
            ) { checked ->
                Prefs.setShowBubble(context, checked)
                PetLogger.i("Settings", "show_bubble -> $checked")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS))

            // ---- 端点与账号（R4）----
            SectionTitle("端点与账号")
            AgentPicker(context)
            var speakMcp by remember { mutableStateOf(Prefs.speakMcpResult(context)) }
            RowSwitch(
                title = "MCP 调用结果播报",
                subtitle = "关闭后：含工具调用的回复只上屏不播音（密集架等设备自带播报场景，避免双播）",
                checked = speakMcp,
                enabled = true,
            ) { on ->
                speakMcp = on
                Prefs.setSpeakMcpResult(context, on)
                PetLogger.i("Settings", "speak_mcp_result -> $on")
            }
            var rewakeTool by remember { mutableStateOf(Prefs.rewakeAfterToolCall(context)) }
            RowSwitch(
                title = "工具调用后需重新唤醒",
                subtitle = "开启后：含工具调用的回复结束后回到唤醒词守门（避免设备自带播报被当用户语音继续对话）",
                checked = rewakeTool,
                enabled = true,
            ) { on ->
                rewakeTool = on
                Prefs.setRewakeAfterToolCall(context, on)
                PetLogger.i("Settings", "rewake_after_tool -> $on")
            }
            var echoCancel by remember { mutableStateOf(Prefs.echoCancelEnabled(context)) }
            RowSwitch(
                title = "播放期回声消除（实验）",
                subtitle = "用自身播报作参考信号消回声，提升播报中喊「小朵小朵」打断成功率（板子无 AEC 场景）",
                checked = echoCancel,
                enabled = true,
            ) { on ->
                echoCancel = on
                Prefs.setEchoCancelEnabled(context, on)
                PetLogger.i("Settings", "echo_cancel -> $on")
            }
            // S26 设备技能：整理台+净化柜地址（空=禁用语音控制）
            var deviceUrl by remember { mutableStateOf(Prefs.deviceBaseUrl(context)) }
            Column(Modifier.fillMaxWidth()) {
                Text("设备控制（整理台/净化柜）", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "语音说「打开整理台」「净化柜强力净化」「整理台风速二档」即直连控制；清空地址可禁用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = deviceUrl,
                    onValueChange = {
                        deviceUrl = it
                        Prefs.setDeviceBaseUrl(context, it)
                    },
                    singleLine = true,
                    label = { Text("设备地址") },
                    placeholder = { Text("http://10.0.18.47:8080") },
                    modifier = Modifier.fillMaxWidth(),
                )
                // S31：跳转 APP 目标可自定义（导航行中键/悬浮窗菜单共用；空=默认密集架包）
                var jumpPkg by remember { mutableStateOf(Prefs.jumpTargetPackage(context)) }
                OutlinedTextField(
                    value = jumpPkg,
                    onValueChange = {
                        jumpPkg = it
                        Prefs.setJumpTargetPackage(context, it)
                    },
                    singleLine = true,
                    label = { Text("跳转 APP 包名") },
                    placeholder = { Text(AppLauncher.DEFAULT_TARGET_PACKAGE) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // S41 密集架动画联动：MCP 工具调用时同步触发密集架 App 动画/提示（清空=禁用）
            var shelfUrl by remember { mutableStateOf(Prefs.shelfBaseUrl(context)) }
            Column(Modifier.fillMaxWidth()) {
                Text("密集架动画联动", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "语音/文字触发密集架 MCP 工具（开架/闭架/通风/盘点/借阅等）时，同步让密集架 App 播动画与提示；同机部署保持默认，分机改密集架 IP；清空地址可禁用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = shelfUrl,
                    onValueChange = {
                        shelfUrl = it
                        Prefs.setShelfBaseUrl(context, it)
                    },
                    singleLine = true,
                    label = { Text("密集架同步地址") },
                    placeholder = { Text(PrefContract.DEFAULT_SHELF_BASE_URL) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            var ttsVol by remember { mutableStateOf(Prefs.ttsVolume(context)) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = XiaoduoDimens.SpaceS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("小朵播报音量", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "板子满格音量会打爆麦克风致播报中喊不醒（削波实测）；当前 ${(ttsVol * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = ttsVol,
                        onValueChange = {
                            ttsVol = it
                            Prefs.setTtsVolume(context, it)
                        },
                        valueRange = 0.2f..1f,
                    )
                }
            }
            Spacer(Modifier.height(XiaoduoDimens.SpaceS))
            EndpointEditor(context)

            HorizontalDivider(modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS))

            // ---- 日志 ----
            SectionTitle("日志")
            TextButtonRow("导出日志（系统分享面板）") { exportLogs(context) }

            HorizontalDivider(modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS))

            // ---- 关于 ----
            SectionTitle("关于")
            Text(
                text = "版本：${versionName(context)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = XiaoduoDimens.SpaceL),
            )
        }
    }
}

@Composable
private fun ScaleSlider(context: Context) {
    var scale by remember { mutableFloatStateOf(Prefs.petScale(context)) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "宠物缩放：%.1fx（App 内与悬浮窗同时生效）".format(scale),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = scale,
            onValueChange = {
                scale = it
                Prefs.setPetScale(context, it)
            },
            onValueChangeFinished = {
                PetLogger.i("Settings", "scale changed -> ${Prefs.petScale(context)}")
                // 悬浮窗在跑（活性未过期）则重启应用新尺寸——stale-true 防线读点（spec §4.3）
                if (PetOverlayState.isAlive(SystemClock.elapsedRealtime())) {
                    PetLogger.i("Settings", "restart overlay for new scale")
                    context.stopService(Intent(context, PetOverlayService::class.java))
                    context.startService(Intent(context, PetOverlayService::class.java))
                }
            },
            valueRange = Prefs.MIN_SCALE..Prefs.MAX_SCALE,
        )
    }
}

