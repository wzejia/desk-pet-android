package com.xiaoduo.deskpet.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xiaoduo.deskpet.PetOverlayService
import com.xiaoduo.deskpet.PetOverlayState
import com.xiaoduo.deskpet.VoiceForegroundService
import com.xiaoduo.deskpet.agui.AguiClient
import com.xiaoduo.deskpet.agui.AguiClientFactory
import com.xiaoduo.deskpet.net.NetConfig
import com.xiaoduo.deskpet.ui.theme.XiaoduoDimens
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.util.Prefs
import com.xiaoduo.deskpet.voice.AudioCapture
import com.xiaoduo.deskpet.voice.EnergyGate
import com.xiaoduo.deskpet.voice.VoiceClientFactory
import com.xiaoduo.deskpet.voice.WakeAckPrefetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS),
    )
}

@Composable
private fun RowSwitch(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    var state by remember(checked) { mutableStateOf(checked) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(
            checked = state,
            enabled = enabled,
            onCheckedChange = { onChecked(it); state = it },
        )
    }
}

@Composable
private fun TextButtonRow(label: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Button(onClick = onClick) { Text(label) }
    }
}

/**
 * S16 平台智能体选择：行显当前 agent 名，点击展开内联单选列表。
 * 数据 POST /agent/list（443 反代，登录态复用 AguiClient）；失败可重试；
 * 选择即存 Prefs（id=请求头路由 + 显示名离线缓存），thread 连续性与本地历史不动
 * （用户 08-25 拍板：切换 agent 只换提示词/MCP，对话连续）。
 */
@Composable
private fun AgentPicker(context: Context) {
    var expanded by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf(Prefs.agentDisplayName(context)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var agents by remember { mutableStateOf<List<AguiClient.AgentBrief>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun fetch() {
        loading = true
        error = null
        scope.launch(Dispatchers.IO) {
            val result = AguiClientFactory.create(context).listAgents()
            withContext(Dispatchers.Main) {
                loading = false
                when (result) {
                    is AguiClient.AgentListResult.Ok -> agents = result.agents
                    is AguiClient.AgentListResult.Error -> error = result.message
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    expanded = !expanded
                    if (expanded && agents.isEmpty() && error == null) fetch()
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("平台智能体", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "当前：$displayName（不同 agent 提示词/MCP 不同，切换即生效）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (expanded) "收起" else "选择",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (expanded) {
            when {
                loading -> Text(
                    "正在拉取 agent 列表…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS),
                )
                error != null -> Column {
                    Text(
                        "拉取失败：$error",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { fetch() }) { Text("重试") }
                }
                agents.isEmpty() -> Text(
                    "平台无可用 agent",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS),
                )
                else -> agents.forEach { agent ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                Prefs.setAgent(context, agent.agentId, agent.agentName)
                                displayName = agent.agentName
                                expanded = false
                                PetLogger.i("Settings", "agent selected -> ${agent.agentId}")
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = Prefs.agentId(context) == agent.agentId, onClick = null)
                        Column {
                            Text(agent.agentName, style = MaterialTheme.typography.bodyLarge)
                            agent.agentDescription?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
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
                // 悬浮窗在跑则重启应用新尺寸
                if (PetOverlayState.running) {
                    PetLogger.i("Settings", "restart overlay for new scale")
                    context.stopService(Intent(context, PetOverlayService::class.java))
                    context.startService(Intent(context, PetOverlayService::class.java))
                }
            },
            valueRange = Prefs.MIN_SCALE..Prefs.MAX_SCALE,
        )
    }
}

/** 端点编辑区：密码框 PasswordVisualTransformation，日志不回显明文。 */
@Composable
private fun EndpointEditor(context: Context) {
    var platformBase by remember { mutableStateOf(NetConfig.platformBase(context)) }
    var asrBase by remember { mutableStateOf(NetConfig.asrBase(context)) }
    var ttsBase by remember { mutableStateOf(NetConfig.ttsBase(context)) }
    var account by remember { mutableStateOf(NetConfig.account(context)) }
    var password by remember { mutableStateOf(NetConfig.password(context)) }
    var mode by remember { mutableStateOf(NetConfig.mode(context)) } // 兼容旧
    var llmMode by remember { mutableStateOf(NetConfig.llmMode(context)) }
    var asrMode by remember { mutableStateOf(NetConfig.asrMode(context)) }
    var ttsMode by remember { mutableStateOf(NetConfig.ttsMode(context)) }
    var cloudBase by remember { mutableStateOf(NetConfig.cloudBase(context)) }
    var cloudKey by remember { mutableStateOf(NetConfig.cloudKey(context)) }
    var cloudLlm by remember { mutableStateOf(NetConfig.cloudLlmModel(context)) }
    var cloudAsr by remember { mutableStateOf(NetConfig.cloudAsrModel(context)) }
    var cloudTts by remember { mutableStateOf(NetConfig.cloudTtsModel(context)) }
    var cloudVoice by remember { mutableStateOf(NetConfig.cloudTtsVoice(context)) }
    var asrProv by remember { mutableStateOf(NetConfig.asrCloudProvider(context)) }
    var ttsProv by remember { mutableStateOf(NetConfig.ttsCloudProvider(context)) }
    var volcKey by remember { mutableStateOf(NetConfig.volcSpeechKey(context)) }
    var volcSpeaker by remember { mutableStateOf(NetConfig.volcTtsSpeaker(context)) }
    var aliyunKey by remember { mutableStateOf(NetConfig.aliyunSpeechKey(context)) }
    var aliyunVoice by remember { mutableStateOf(NetConfig.aliyunTtsVoice(context)) }
    var aliyunTtsModel by remember { mutableStateOf(NetConfig.aliyunTtsModel(context)) }
    var aliyunAsrModel by remember { mutableStateOf(NetConfig.aliyunAsrModel(context)) }
    var volcAsrModel by remember { mutableStateOf(NetConfig.volcAsrModel(context)) }
    var volcTtsModel by remember { mutableStateOf(NetConfig.volcTtsModel(context)) }
    var volcTtsResource by remember { mutableStateOf(NetConfig.volcTtsResource(context)) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("各模态独立选择：平台（内网）/ 云端 API（OpenAI 兼容）", style = MaterialTheme.typography.labelLarge)
        @Composable
        fun modalityRow(label: String, get: () -> String, set: (String) -> Unit) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(label, modifier = Modifier.weight(1f))
                RadioButton(selected = get() == NetConfig.MODE_PLATFORM, onClick = { set(NetConfig.MODE_PLATFORM) })
                Text("平台")
                Spacer(modifier = Modifier.padding(start = XiaoduoDimens.SpaceS))
                RadioButton(selected = get() == NetConfig.MODE_CLOUD, onClick = { set(NetConfig.MODE_CLOUD) })
                Text("云端")
            }
        }
        modalityRow("LLM 对话", { llmMode }) { llmMode = it }
        modalityRow("ASR 识别", { asrMode }) { asrMode = it }
        modalityRow("TTS 合成", { ttsMode }) { ttsMode = it }
        Spacer(modifier = Modifier.padding(top = XiaoduoDimens.SpaceS))
        if (llmMode == NetConfig.MODE_CLOUD || asrMode == NetConfig.MODE_CLOUD || ttsMode == NetConfig.MODE_CLOUD) {
            OutlinedTextField(
                value = cloudBase, onValueChange = { cloudBase = it },
                label = { Text("云端 Base URL（如 https://dashscope.aliyuncs.com/compatible-mode/v1）") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = cloudKey, onValueChange = { cloudKey = it },
                label = { Text("API Key（sk-xxx）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = cloudLlm, onValueChange = { cloudLlm = it },
                label = { Text("LLM 模型（如 qwen-plus / gpt-4o-mini）") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = cloudAsr, onValueChange = { cloudAsr = it },
                label = { Text("ASR 模型（如 whisper-1）") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = cloudTts, onValueChange = { cloudTts = it },
                label = { Text("TTS 模型（如 gpt-4o-mini-tts）") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = cloudVoice, onValueChange = { cloudVoice = it },
                label = { Text("TTS 音色（如 alloy）") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Text("ASR/TTS 云厂商（openai 用上方 base/key；volcengine/aliyun 用下方凭证）", style = MaterialTheme.typography.labelMedium)
            @Composable
            fun providerRow(label: String, get: () -> String, set: (String) -> Unit) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, modifier = Modifier.weight(1f))
                    RadioButton(selected = get() == "openai", onClick = { set("openai") }); Text("OpenAI")
                    Spacer(modifier = Modifier.padding(start = XiaoduoDimens.SpaceS))
                    RadioButton(selected = get() == "volcengine", onClick = { set("volcengine") }); Text("火山")
                    Spacer(modifier = Modifier.padding(start = XiaoduoDimens.SpaceS))
                    RadioButton(selected = get() == "aliyun", onClick = { set("aliyun") }); Text("阿里")
                }
            }
            providerRow("ASR 厂商", { asrProv }) { asrProv = it }
            providerRow("TTS 厂商", { ttsProv }) { ttsProv = it }
            if (asrProv == "volcengine" || ttsProv == "volcengine") {
                OutlinedTextField(
                    value = volcKey, onValueChange = { volcKey = it },
                    label = { Text("火山语音 X-Api-Key（UUID）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = volcSpeaker, onValueChange = { volcSpeaker = it },
                    label = { Text("火山 TTS 音色（如 zh_female_qingxinnvsheng_uranus_bigtts）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = volcAsrModel, onValueChange = { volcAsrModel = it },
                    label = { Text("火山 ASR 模型名（默认 bigmodel，与端点耦合）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = volcTtsModel, onValueChange = { volcTtsModel = it },
                    label = { Text("火山 TTS 模型（默认 seed-tts-2.0-standard）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = volcTtsResource, onValueChange = { volcTtsResource = it },
                    label = { Text("火山 TTS resource_id（默认 seed-tts-2.0，与模型耦合）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (asrProv == "aliyun" || ttsProv == "aliyun") {
                OutlinedTextField(
                    value = aliyunKey, onValueChange = { aliyunKey = it },
                    label = { Text("阿里 dashscope key（sk-...，与 LLM 同一个）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = aliyunTtsModel, onValueChange = { aliyunTtsModel = it },
                    label = { Text("阿里 TTS 模型（cosyvoice-v2 / qwen-audio-3.0-tts-flash）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = aliyunAsrModel, onValueChange = { aliyunAsrModel = it },
                    label = { Text("阿里 ASR 模型（qwen-audio-3.0-asr-flash-streaming / paraformer-realtime-v2）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = aliyunVoice, onValueChange = { aliyunVoice = it },
                    label = { Text("阿里 TTS 音色（cosyvoice: longxiaochun_v2；qwen-audio: longanfengyue）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.padding(top = XiaoduoDimens.SpaceS))
        }
        if (mode == NetConfig.MODE_PLATFORM || llmMode == NetConfig.MODE_PLATFORM || asrMode == NetConfig.MODE_PLATFORM || ttsMode == NetConfig.MODE_PLATFORM) {
        OutlinedTextField(
            value = platformBase, onValueChange = { platformBase = it },
            label = { Text("平台地址（登录 :8080 / Agent 走 443 反代）") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = asrBase, onValueChange = { asrBase = it },
            label = { Text("ASR 地址") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = ttsBase, onValueChange = { ttsBase = it },
            label = { Text("TTS 地址") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = account, onValueChange = { account = it },
            label = { Text("账号") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("密码（无默认值，仅存本机）") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.padding(top = XiaoduoDimens.SpaceS))
        } // end if (MODE_PLATFORM)
        Button(onClick = {
            NetConfig.saveAll(
                context, platformBase, asrBase, ttsBase, account, password,
                mode = mode, llmMode = llmMode, asrMode = asrMode, ttsMode = ttsMode,
                cloudBase = cloudBase, cloudKey = cloudKey,
                cloudLlmModel = cloudLlm, cloudAsrModel = cloudAsr,
                cloudTtsModel = cloudTts, cloudTtsVoice = cloudVoice,
                asrCloudProvider = asrProv, ttsCloudProvider = ttsProv,
                volcSpeechKey = volcKey, volcTtsSpeaker = volcSpeaker,
                volcAsrModel = volcAsrModel, volcTtsModel = volcTtsModel, volcTtsResource = volcTtsResource,
                aliyunSpeechKey = aliyunKey, aliyunTtsVoice = aliyunVoice, aliyunTtsModel = aliyunTtsModel,
                aliyunAsrModel = aliyunAsrModel,
            )
            // S9-3：TTS 配置改存后预取唤醒应答音（「保存即合成」；caller 线程仅 CAS
            // 占位零 IO，合成在 daemon 线程，失败静默走兜底链）
            WakeAckPrefetcher.maybePrefetch(context.applicationContext)
            // 日志严禁打凭据（account/password/token 均属凭据，评审抽查点）
            PetLogger.i("Settings", "net config saved, platform=$platformBase asr=$asrBase tts=$ttsBase")
            Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
        }) { Text("保存端点配置") }
    }
}

/**
 * 链路自检（S1-tail，兼 U6 调试页）：一键跑「登录 → 首聊『你好』」全链路。
 * UI 实时显示 AguiClient 进度行与终态（首 token 延迟 / 回复字数 / 错误 msg），
 * 全程经 PetLogger 落文件日志（凭据与 token 不进日志）。
 */
@Composable
private fun LinkSelfCheck(context: Context) {
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("尚未自检") }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth()) {
        Button(
            enabled = !running,
            onClick = {
                if (NetConfig.password(context).isBlank()) {
                    status = "请先在上方填写密码并保存端点配置"
                    return@Button
                }
                running = true
                status = "自检开始…"
                PetLogger.i("SelfCheck", "link self-check start")
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        AguiClientFactory.create(
                            context,
                            logger = { msg ->
                                PetLogger.i("AguiClient", msg)
                                status = msg // Compose snapshot state 跨线程写安全
                            },
                        ).chat("你好")
                    }
                    status = when (result) {
                        is AguiClient.ChatResult.Success -> {
                            val latency = result.firstTokenLatencyMs?.let { "${it}ms" } ?: "未知"
                            PetLogger.i(
                                "SelfCheck",
                                "self-check ok, firstToken=$latency, reply=${result.text.length} chars, attempts=${result.attempts}",
                            )
                            PetLogger.i("SelfCheck", "reply full text: ${result.text}")
                            "成功：首 token $latency，回复 ${result.text.length} 字（尝试 ${result.attempts} 次）"
                        }
                        is AguiClient.ChatResult.Error -> {
                            PetLogger.e("SelfCheck", "self-check failed: ${result.message}")
                            "失败：${result.message}"
                        }
                    }
                    running = false
                }
            },
        ) { Text("发送『你好』测试链路") }
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS),
        )
    }
}

/**
 * 语音自检（S4）：按住说话跑「采麦→VAD→ASR→AG-UI→分句→TTS→播放+气泡 reveal」全链路。
 * RECORD_AUDIO 运行时权限走真实申请路径（ColorOS 禁 shell 授权）；进度经
 * VoiceSessionManager.statusListener 实时上屏，全程 PetLogger 落文件。
 */
@Composable
private fun VoiceSelfCheck(context: Context) {
    var status by remember { mutableStateOf("按住说话进行语音自检") }
    var holding by remember { mutableStateOf(false) }
    val session = remember { VoiceClientFactory.session(context) }
    DisposableEffect(session) {
        session.statusListener = { status = it }
        onDispose { session.statusListener = null }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        PetLogger.i("VoiceCheck", "RECORD_AUDIO runtime permission granted=$granted")
        status = if (granted) "麦克风已授权，请再次按住说话" else "麦克风权限被拒，无法语音自检"
    }

    fun pressDown() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            PetLogger.i("VoiceCheck", "request RECORD_AUDIO runtime permission")
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        holding = true
        PetLogger.i("VoiceCheck", "push-to-talk start")
        session.startListening()
    }

    fun pressUp() {
        if (!holding) return
        holding = false
        PetLogger.i("VoiceCheck", "push-to-talk release")
        session.stopListening()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { /* 点击无操作，语义在 onPress 按住/松开 */ },
            // Button 内建 clickable 会先消费 down 事件，detectTapGestures 默认
            // requireUnconsumed=true 将永远等不到——必须显式 false（真机实证 2026-07-30）
            modifier = Modifier.pointerInput(session) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressDown()
                    waitForUpOrCancellation()
                    pressUp()
                }
            },
        ) { Text(if (holding) "松开结束说话" else "按住说话") }
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS),
        )
    }
}

/**
 * 语音唤醒（S5）：KWS 常开开关（唯一拉起路径，禁止自启）+ 能量门控阈值显示 + 校准入口。
 * 权限走真实用户申请路径：RECORD_AUDIO 必须，POST_NOTIFICATIONS（API 33+）尽量要
 * （被拒服务照跑，通知栏不显示，任务管理器仍可见前台服务）。
 */
@Composable
private fun KwsSection(context: Context) {
    var enabled by remember { mutableStateOf(Prefs.kwsEnabled(context)) }
    var gate by remember { mutableFloatStateOf(Prefs.kwsGate(context)) }
    var status by remember { mutableStateOf<String?>(null) }

    fun startKws() {
        Prefs.setKwsEnabled(context, true)
        ContextCompat.startForegroundService(
            context,
            Intent(context, VoiceForegroundService::class.java),
        )
        enabled = true
        PetLogger.i("Settings", "KWS always-on enabled by user toggle")
    }

    fun stopKws() {
        Prefs.setKwsEnabled(context, false)
        context.stopService(Intent(context, VoiceForegroundService::class.java))
        enabled = false
        PetLogger.i("Settings", "KWS always-on disabled by user toggle")
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // 通知权限被拒服务照跑（通知栏不显示，任务管理器仍可见前台服务）
        PetLogger.i("Settings", "POST_NOTIFICATIONS for KWS granted=$granted")
        startKws()
    }

    fun requestNotifThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startKws()
        }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        PetLogger.i("Settings", "RECORD_AUDIO for KWS granted=$granted")
        if (granted) {
            requestNotifThenStart()
        } else {
            status = "麦克风权限被拒，无法开启唤醒"
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        RowSwitch(
            title = "「小朵小朵」语音唤醒",
            subtitle = "前台服务常驻，音频仅本地识别；唤醒后免按键对话",
            checked = enabled,
            enabled = true,
        ) { checked ->
            if (checked) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    requestNotifThenStart()
                } else {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            } else {
                stopKws()
            }
        }
        Text(
            text = "能量门控阈值：%.3f（环境音量低于此值整块不识别，防静音空转耗电）".format(gate),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.padding(top = XiaoduoDimens.SpaceS))
        var micMode by remember { mutableStateOf(Prefs.micSourceMode(context)) }
        val micModeLabel = when (micMode) {
            Prefs.MIC_SOURCE_RECOGNITION -> "识别（绕开通话链）"
            Prefs.MIC_SOURCE_UNPROCESSED -> "原始（UNPROCESSED）"
            else -> "通话（默认，硬件 AEC）"
        }
        TextButtonRow("采麦源：$micModeLabel（点击切换；重启 App 生效，改后请重新校准）") {
            micMode = (micMode + 1) % 3
            Prefs.setMicSourceMode(context, micMode)
            PetLogger.i("Settings", "mic source mode -> $micMode (effective after app restart)")
        }
        Spacer(modifier = Modifier.padding(top = XiaoduoDimens.SpaceS))
        KwsCalibration(context) { written -> gate = written }
        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS),
            )
        }
    }
}

/**
 * 环境音校准：采集 5 秒环境 RMS 分布 → 建议阈值（p95×2.5，公式见 EnergyGate）→ 一键写入。
 * KWS 常开中复用会话采麦流（单流纪律，防双开抢麦）；未开则临时开一条采满即停。
 */
@Composable
private fun KwsCalibration(context: Context, onGateWritten: (Float) -> Unit) {
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var suggested by remember { mutableStateOf<Float?>(null) }
    var suggestedVad by remember { mutableStateOf(0.05) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth()) {
        Button(
            enabled = !running,
            onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    report = "请先授予麦克风权限再校准"
                    return@Button
                }
                running = true
                report = "采集中…请保持环境安静 5 秒"
                scope.launch {
                    val rmsValues = collectAmbientRms(context, CALIBRATION_SECONDS)
                    if (rmsValues.isEmpty()) {
                        report = "未采到音频（检查麦克风权限或占用）"
                    } else {
                        val stats = EnergyGate.statsOf(rmsValues)
                        val s = EnergyGate.suggestThreshold(stats)
                        suggested = s
                        // S18：VAD 说话阈值同步建议（板子噪声底骑 0.05 线致判停失效）
                        val vadLevel = EnergyGate.suggestVadSpeechLevel(stats)
                        suggestedVad = vadLevel
                        report = "样本 ${stats.count} 块：中位 %.4f / p95 %.4f / 峰值 %.4f → 建议 %.3f"
                            .format(stats.median, stats.p95, stats.max, s, vadLevel)
                        PetLogger.i(
                            "Settings",
                            "kws calibration: n=${stats.count} median=${stats.median} " +
                                "p95=${stats.p95} max=${stats.max} suggest=$s vad=$vadLevel",
                        )
                    }
                    running = false
                }
            },
        ) { Text(if (running) "校准采集中…" else "环境音校准（5 秒）") }
        report?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = XiaoduoDimens.SpaceS),
            )
        }
        suggested?.let { s ->
            Button(onClick = {
                Prefs.setKwsGate(context, s)
                Prefs.setVadSpeechLevel(context, suggestedVad)
                val written = Prefs.kwsGate(context)
                val vadWritten = Prefs.vadSpeechLevel(context)
                onGateWritten(written)
                PetLogger.i("Settings", "kws gate written: $written, vad level written: $vadWritten")
                Toast.makeText(
                    context,
                    "已写入：能量门 %.3f / 说话阈值 %.3f".format(written, vadWritten),
                    Toast.LENGTH_SHORT,
                ).show()
            }) { Text("写入建议阈值（门 %.3f + 说话 %.3f）".format(s, suggestedVad)) }
        }
    }
}

private const val CALIBRATION_SECONDS = 5

private suspend fun collectAmbientRms(context: Context, seconds: Int): List<Float> {
    val session = VoiceClientFactory.session(context)
    val out = mutableListOf<Float>()
    if (session.captureRunning) {
        // KWS 常开中：复用同一条采麦流（知识包 §3.5 单流纪律）
        collectRmsFor(seconds, session.captureBlocks, out)
    } else {
        val capture = AudioCapture(sourceMode = Prefs.micSourceMode(context))
        val ioScope = CoroutineScope(Dispatchers.IO)
        try {
            capture.start(ioScope)
            collectRmsFor(seconds, capture.blocks, out)
        } finally {
            capture.stop()
            ioScope.cancel()
        }
    }
    return out
}

private suspend fun collectRmsFor(
    seconds: Int,
    blocks: kotlinx.coroutines.flow.SharedFlow<ByteArray>,
    out: MutableList<Float>,
) {
    try {
        withTimeout(seconds * 1000L) {
            blocks.collect { block -> out.add(EnergyGate.rmsOf(block)) }
        }
    } catch (te: TimeoutCancellationException) {
        // 采满时长正常收尾
    }
}

private fun versionName(context: Context): String =    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

/** 导出日志（偏差 D 裁决补做，迁移自 M1）：系统分享面板，FileProvider 授权。 */
private fun exportLogs(context: Context) {
    val files = PetLogger.logFiles().filter { it.exists() }
    if (files.isEmpty()) {
        Toast.makeText(context, "暂无日志文件", Toast.LENGTH_SHORT).show()
        return
    }
    val uris = files.map {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
    }
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uris[0])
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    PetLogger.i("Settings", "export logs, ${uris.size} file(s)")
    runCatching { context.startActivity(Intent.createChooser(send, "导出日志")) }
        .onFailure {
            PetLogger.e("Settings", "export logs failed", it)
            Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_LONG).show()
        }
}
