package com.xiaoduo.deskpet.ui.screens

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.xiaoduo.deskpet.agui.FileMeta
import com.xiaoduo.deskpet.agui.UploadStatus
import com.xiaoduo.deskpet.chat.FunctionKeyLogic
import com.xiaoduo.deskpet.ui.theme.XiaoduoDimens
import com.xiaoduo.deskpet.voice.EnergyGate
import com.xiaoduo.deskpet.voice.VoiceClientFactory

/**
 * 底部输入条：单圆角矩形容器 + 内部横线分区（2026-08-06 用户拍板定稿——
 * S8-1 曾回滚为两个独立圆角 Surface，观感不佳否决；R3/S8-1 功能修复零回退）：
 * 上区 InputArea（语音=「点击说话」满宽触摸区 / 文字=surfaceVariant 底 TextField）+
 * 下区 FunctionBar（左上传键 / 右多功能键），区间一条 1dp 横线分隔（与顶部横线
 * 同色同粗，outline——Theme 未定义 outlineVariant，M3 默认紫灰与中性色板冲突）；
 * 有待发文件时 chips 行压在容器外上方。
 *
 * - 多功能键真值表见 [FunctionKeyLogic]（纯逻辑 JVM 单测）：输出中=STOP(方块) ＞
 *   上传中=DISABLED ＞ 有内容=SEND ＞ 文字模式=TO_VOICE ＞ 语音模式=TO_KEYBOARD。
 *   SEND 连点第二击会因 text 已清+phase 已 SENDING 落 STOP 分支误取消刚发的轮——
 *   [TAP_DEBOUNCE_MS] 防抖根治（守卫四问④）。
 * - 点按语音（免按住模式 Tap-to-Talk，2026-10-18 拍板替换按住说话）：点一下单路回调
 *   onVoiceTap，分流纯逻辑在 VoiceTapRouter（UI 不判状态）；点按开始 Vibrator 30ms
 *   （原 #2 触觉反馈语义保留）；聆听态视觉（高亮+声波纹+「正在聆听，点击结束」）状态
 *   源=VSM stateFlow（[voiceListening] 由上游 collectAsState 派生，禁本地布尔镜像）；
 *   声波纹订阅配对=LaunchedEffect(voiceListening) 仅聆听期 collect、退出自动 cancel
 *   （守卫四问①）。手势层守卫：禁回退按住/松手/上滑取消语义——结束时机归状态机
 *   （VAD 判停/S51 续听/30s 超时），绑回人手即重新引入病 B 判停竞速吞语音（fork 守卫）；
 *   点按检测以本文件 awaitEachGesture 手势族落地（awaitFirstDown+waitForUpOrCancellation
 *   上-下沿配对），fork detectTapGestures 原型不引入（R2 手势 API 结构零回退）。
 * - text 由 ChatScreen 级 rememberSaveable 持有（barge-in 首字符打断在其 onTextChange），
 *   voiceMode 本组件内 rememberSaveable。
 */
@Composable
fun InputBar(
    busy: Boolean,
    voiceActive: Boolean,
    voiceListening: Boolean,
    files: List<FileMeta>,
    text: String,
    onTextChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onStopTurn: () -> Unit,
    onPickFile: () -> Unit,
    onRemoveFile: (String) -> Unit,
    onVoiceTap: () -> Unit,
) {
    var voiceMode by rememberSaveable { mutableStateOf(true) }
    val uploadInFlight = files.any { it.status == UploadStatus.UPLOADING }
    val action = FunctionKeyLogic.resolve(
        busy = busy,
        voiceActive = voiceActive,
        hasContent = text.isNotBlank(),
        voiceMode = voiceMode,
        uploadInFlight = uploadInFlight,
    )
    var lastTapAt by remember { mutableStateOf(0L) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            if (files.isNotEmpty()) {
                FilesRow(files = files, onRemoveFile = onRemoveFile)
            }
            // 用户拍板（2026-08-06）：两个独立圆角 Surface → 单个圆角矩形容器，
            // 内部一条横线划分 InputArea/FunctionBar 两区（视觉一个整体盒子）。
            // R3 纵向空洞修复（行高 48 + FunctionBar 4dp padding）保留不退；chips 行留容器外上方。
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = XiaoduoDimens.SpaceM,
                        end = XiaoduoDimens.SpaceM,
                        top = XiaoduoDimens.SpaceS,
                        bottom = XiaoduoDimens.SpaceM,
                    ),
                shape = RoundedCornerShape(XiaoduoDimens.CornerInputBar),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column {
                    InputArea(
                        voiceMode = voiceMode,
                        voiceListening = voiceListening,
                        text = text,
                        onTextChange = onTextChange,
                        onVoiceTap = onVoiceTap,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    FunctionBar(
                        action = action,
                        uploadInFlight = uploadInFlight,
                        onPickFile = onPickFile,
                        onAction = {
                            if (action == FunctionKeyLogic.Action.DISABLED) return@FunctionBar
                            // 防抖：SEND 后 400ms 窗口内的第二击一律忽略（防误取消刚发的轮）
                            val now = System.currentTimeMillis()
                            if (now - lastTapAt < TAP_DEBOUNCE_MS) return@FunctionBar
                            lastTapAt = now
                            when (action) {
                                FunctionKeyLogic.Action.STOP -> onStopTurn()
                                FunctionKeyLogic.Action.SEND -> onSend(text)
                                FunctionKeyLogic.Action.TO_VOICE -> voiceMode = true
                                FunctionKeyLogic.Action.TO_KEYBOARD -> voiceMode = false
                                FunctionKeyLogic.Action.DISABLED -> Unit
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 语音/文字两态统一行高（切换不跳变=两态同高，盲测 R3 拍板本质）。
 * S27 期由 48 调 56：48 需精确 height(tight) 才能压住 M3 TextField 内部
 * defaultMinSize(56)，代价是多行文本视口只剩 ~1.4 行（显示不全 bug，用户报告）。
 * 56=M3 单行自然高：两态同高保持 + 多行按内容撑到 maxLines 上限全可见。
 */
private const val INPUT_ROW_HEIGHT_DP = 56

/** 多功能键防抖窗口（守卫四问④）：SEND 连点第二击落 STOP 分支误取消刚发的轮。 */
private const val TAP_DEBOUNCE_MS = 400L

// ---------------- 容器上层 InputArea ----------------

/**
 * 上层 Surface 内容（外边距由 InputBar 两层各自承担，本层不再自加 padding）：
 * 语音=「点击说话」触摸区（idle=surfaceVariant）；文字=surfaceVariant 底 TextField。
 */
@Composable
private fun InputArea(
    voiceMode: Boolean,
    voiceListening: Boolean,
    text: String,
    onTextChange: (String) -> Unit,
    onVoiceTap: () -> Unit,
) {
    if (voiceMode) {
        VoiceHoldArea(
            listening = voiceListening,
            onVoiceTap = onVoiceTap,
        )
    } else {
        TextField(
            value = text,
            onValueChange = onTextChange,
            // S27 修复显示不全 bug（终解，见常量注释）：min=56 与内部 defaultMinSize(56)
            // 一致——单行与语音态同高（不跳变），多行按内容撑到 maxLines 上限全可见。
            modifier = Modifier.fillMaxWidth().heightIn(min = INPUT_ROW_HEIGHT_DP.dp),
            maxLines = 4,
            placeholder = {
                Text(
                    "请输入内容",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            shape = RoundedCornerShape(XiaoduoDimens.CornerInputBar),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

/**
 * 「点击说话」满宽触摸区（免按住模式 Tap-to-Talk，2026-10-18 拍板替换按住说话）：
 * 56dp 与文字态 TextField 同高（盲测 R3/S27 定稿，切换不跳变=两态同高）。
 * 手势语义：点一下=onVoiceTap 单路（分流在 VoiceTapRouter：IDLE/PROCESSING/SPEAKING→
 * 开始聆听，LISTENING→结束聆听有货送货）；结束时机归状态机（VAD 判停/S51 续听/30s 超时）。
 * 聆听态视觉（[listening]：高亮底+声波纹+「正在聆听，点击结束」）状态源=VSM stateFlow
 * （上游 collectAsState 派生，禁本地布尔镜像）。
 * 手势层守卫：禁回退按住/松手/上滑取消语义——绑回人手即重新引入病 B 判停竞速吞语音
 * （删此约束 → 真机金线回归，critic 审查 + 真机语义验收兜底）。合并口径：fork 的
 * detectTapGestures 点按语义以本文件既有 awaitEachGesture 手势族落地（tap 判定=
 * awaitFirstDown + waitForUpOrCancellation 上-下沿配对），手势 API 结构零回退（R2）。
 */
@Composable
private fun VoiceHoldArea(
    listening: Boolean,
    onVoiceTap: () -> Unit,
) {
    val context = LocalContext.current
    // 声波纹是整块覆盖语义（FIFO 两块一替）：mutableStateOf + neverEqualPolicy 单次赋值
    // 驱动失效，免 SnapshotStateList 逐元素写的快照/通知开销（~10-20Hz）
    val levels = remember {
        mutableStateOf(MutableList(WAVE_BAR_COUNT) { 0f }, neverEqualPolicy())
    }
    // 过时闭包守卫（fork critic 实证处方）：pointerInput(Unit) key 不重启，手势闭包捕获的
    // listening 是组合期快照——直捕则永为首次组合值 false，每次「结束聆听」点按都误震动。
    // rememberUpdatedState 让闭包读到最新值；onVoiceTap 路由不受影响（读 session.state 瞬时）。
    val currentListening by rememberUpdatedState(listening)

    // 声波纹订阅配对（守卫四问①）：仅聆听期间 collect captureBlocks（SharedFlow
    // DROP_OLDEST，多订阅者不阻塞采麦管线）；listening 翻 false / 离开组合时
    // LaunchedEffect 自动 cancel——UI 绝不开采麦（采麦开关全在 VoiceSessionManager）
    LaunchedEffect(listening) {
        if (!listening) return@LaunchedEffect
        levels.value = MutableList(WAVE_BAR_COUNT) { 0f }
        VoiceClientFactory.session(context).captureBlocks.collect { block ->
            val level = (EnergyGate.rmsOf(block) / RMS_FULL_SCALE).coerceIn(0f, 1f)
            val next = levels.value
            next.removeAt(0)
            next.add(level)
            levels.value = next
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(INPUT_ROW_HEIGHT_DP.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    // 点按判定=上-下沿配对（上游手势族）：waitForUpOrCancellation 返回非 null
                    // =松手完成点按；null=手势被消费/取消（滚动手势接管等）不触发。
                    val up = waitForUpOrCancellation()
                    if (up != null) {
                        // 开始聆听即时触觉反馈（原 #2 按住震动语义保留）；结束聆听不震
                        if (!currentListening) vibrateShort(context)
                        onVoiceTap()
                    }
                }
            },
        shape = RoundedCornerShape(XiaoduoDimens.CornerInputBar),
        // 聆听高亮=outline 底（沿用原按住态色）；idle=surfaceVariant 独立底色
        // （S8-1 不再透明融入单容器；上游三态 when 随上滑取消语义一并退役）
        color = if (listening) {
            MaterialTheme.colorScheme.outline
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!listening) {
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "点击说话",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "正在聆听，点击结束",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    WaveformBars(
                        levelsState = levels,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** 声波纹：[WAVE_BAR_COUNT] 根同宽圆头细柱，高度按 RMS 归一值在 [WAVE_BAR_MIN_DP]..[WAVE_BAR_MAX_DP] 间插值。 */
@Composable
private fun WaveformBars(levelsState: MutableState<MutableList<Float>>, color: Color) {
    Canvas(modifier = Modifier.height(WAVE_BAR_MAX_DP.dp).fillMaxWidth()) {
        val levels = levelsState.value // draw 阶段单次读状态：仅重绘，不重组
        val slot = size.width / levels.size
        val barWidth = (slot * 0.45f).coerceAtMost(3.dp.toPx())
        val minH = WAVE_BAR_MIN_DP.dp.toPx()
        val maxH = size.height
        levels.forEachIndexed { i, level ->
            val h = lerp(minH, maxH, level).coerceIn(minH, maxH)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * slot + (slot - barWidth) / 2f, (size.height - h) / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

private const val WAVE_BAR_COUNT = 24
// 盲测返工：最低高 3→6dp，低能量也是短柱（原 3dp×3dp 圆头=圆点观感，与短柱混杂）
private const val WAVE_BAR_MIN_DP = 6
private const val WAVE_BAR_MAX_DP = 26

/** RMS 满量程归一分母：对账表真麦说话 ~0.036，/0.05 → 说话柱 ~0.72 高、静音 ~0.1。 */
private const val RMS_FULL_SCALE = 0.05f

/** 点按开始短震 30ms（原 #2 触觉反馈语义保留）；minSdk 26 起 VibrationEffect 可用，无版本分支。 */
private fun vibrateShort(context: Context) {
    val vibrator = context.getSystemService(Vibrator::class.java) ?: return
    if (!vibrator.hasVibrator()) return
    vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
}

// ---------------- 容器下层 FunctionBar ----------------

/**
 * 功能条（容器内下层，左右配平）：左=上传文件键（DeepSeek「+」位，上传中禁用防连点）；
 * 右=多功能键。+ 与 TO_VOICE/TO_KEYBOARD 统一为 36dp 圆圈容器+灰图标
 * （容器底色已是 surfaceVariant，圆圈改用 surface 保持可辨，盲测返工）；
 * STOP/SEND 保持主题反色实心圆不动。
 * 盲测 R3：vertical padding 收到 4dp（就地小值，XiaoduoDimens 不在白名单故不入档），
 * 与 InputArea 之间零额外间距，消灭容器纵向空洞。
 */
@Composable
private fun FunctionBar(
    action: FunctionKeyLogic.Action,
    uploadInFlight: Boolean,
    onPickFile: () -> Unit,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = XiaoduoDimens.SpaceM,
                end = XiaoduoDimens.SpaceM,
                top = 4.dp,
                bottom = 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPickFile, enabled = !uploadInFlight) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "上传文件",
                    modifier = Modifier.size(20.dp),
                    tint = if (uploadInFlight) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        FunctionKey(action = action, onAction = onAction)
    }
}

/**
 * 多功能键（DeepSeek 范式：生成中=方块停止，空闲=发送/切换）。点击统一走 onAction（含防抖）。
 * 盲测返工：TO_VOICE/TO_KEYBOARD/DISABLED 与 + 键统一为 surface 圆圈容器+灰图标；
 * STOP/SEND 保持主题反色实心圆。
 */
@Composable
private fun FunctionKey(action: FunctionKeyLogic.Action, onAction: () -> Unit) {
    val filled = action == FunctionKeyLogic.Action.STOP || action == FunctionKeyLogic.Action.SEND
    Surface(
        onClick = onAction,
        enabled = action != FunctionKeyLogic.Action.DISABLED,
        modifier = Modifier.size(36.dp),
        shape = CircleShape,
        color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (action) {
                FunctionKeyLogic.Action.STOP -> Icon(
                    Icons.Filled.Stop,
                    contentDescription = "停止输出",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )

                FunctionKeyLogic.Action.SEND -> Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )

                FunctionKeyLogic.Action.TO_VOICE -> Icon(
                    Icons.Filled.Mic,
                    contentDescription = "切换到按住说话",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                FunctionKeyLogic.Action.TO_KEYBOARD -> Icon(
                    Icons.Filled.Keyboard,
                    contentDescription = "切换到键盘输入",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 上传中禁发：灰箭头占位（DeepSeek「文件上传中请稍候」式反馈）
                FunctionKeyLogic.Action.DISABLED -> Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "文件上传中，请稍候",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

// ---------------- 待发文件 chips ----------------

/** 待发文件行（有文件时压 InputArea 上方）：文件名 + 状态/大小 + 移除钮，横向滚动。 */
@Composable
private fun FilesRow(files: List<FileMeta>, onRemoveFile: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(
                start = XiaoduoDimens.SpaceM,
                end = XiaoduoDimens.SpaceM,
                top = XiaoduoDimens.SpaceS,
            ),
        horizontalArrangement = Arrangement.spacedBy(XiaoduoDimens.SpaceS),
    ) {
        for (meta in files) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(
                        start = 10.dp,
                        top = 6.dp,
                        bottom = 6.dp,
                        end = 4.dp,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            meta.fileName,
                            modifier = Modifier.widthIn(max = 160.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            when (meta.status) {
                                UploadStatus.UPLOADING -> "上传中…"
                                UploadStatus.ERROR -> "上传失败"
                                UploadStatus.DONE -> formatFileSize(meta.fileSize)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (meta.status == UploadStatus.ERROR) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(
                        onClick = { onRemoveFile(meta.localId) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "移除文件",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
