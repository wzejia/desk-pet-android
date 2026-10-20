package com.xiaoduo.deskpet.ui.screens

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
 * 上区 InputArea（语音=「按住说话」满宽触摸区 / 文字=surfaceVariant 底 TextField）+
 * 下区 FunctionBar（左上传键 / 右多功能键），区间一条 1dp 横线分隔（与顶部横线
 * 同色同粗，outline——Theme 未定义 outlineVariant，M3 默认紫灰与中性色板冲突）；
 * 有待发文件时 chips 行压在容器外上方。
 *
 * - 多功能键真值表见 [FunctionKeyLogic]（纯逻辑 JVM 单测）：输出中=STOP(方块) ＞
 *   上传中=DISABLED ＞ 有内容=SEND ＞ 文字模式=TO_VOICE ＞ 语音模式=TO_KEYBOARD。
 *   SEND 连点第二击会因 text 已清+phase 已 SENDING 落 STOP 分支误取消刚发的轮——
 *   [TAP_DEBOUNCE_MS] 防抖根治（守卫四问④）。
 * - 按住反馈（#2 修复）：awaitEachGesture 全程跟踪（弃 detectTapGestures，支持上滑取消）；
 *   按住开始 Vibrator 30ms（manifest VIBRATE）；仅按住期间订阅 captureBlocks 喂声波纹
 *   （订阅配对=LaunchedEffect(holding) 自动 cancel，守卫四问①）；上滑累计超
 *   [CANCEL_DRAG_THRESHOLD_DP]dp 标 cancelled——滑回阈值下即恢复，取消在松手一刻才物化
 *   （cancelled→onVoiceCancel 不发送，否则 onVoiceEnd 发送，守卫四问②）。
 * - text 由 ChatScreen 级 rememberSaveable 持有（barge-in 首字符打断在其 onTextChange），
 *   voiceMode 本组件内 rememberSaveable。
 */
@Composable
fun InputBar(
    busy: Boolean,
    voiceActive: Boolean,
    files: List<FileMeta>,
    text: String,
    onTextChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onStopTurn: () -> Unit,
    onPickFile: () -> Unit,
    onRemoveFile: (String) -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceEnd: () -> Unit,
    onVoiceCancel: () -> Unit,
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
                        text = text,
                        onTextChange = onTextChange,
                        onVoiceStart = onVoiceStart,
                        onVoiceEnd = onVoiceEnd,
                        onVoiceCancel = onVoiceCancel,
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
 * 语音=「按住说话」触摸区（idle=surfaceVariant）；文字=surfaceVariant 底 TextField。
 */
@Composable
private fun InputArea(
    voiceMode: Boolean,
    text: String,
    onTextChange: (String) -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceEnd: () -> Unit,
    onVoiceCancel: () -> Unit,
) {
    if (voiceMode) {
        VoiceHoldArea(
            onVoiceStart = onVoiceStart,
            onVoiceEnd = onVoiceEnd,
            onVoiceCancel = onVoiceCancel,
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
 * 「按住说话」满宽触摸区（#2 修复核心）：48dp 与文字态 TextField 同高（盲测 R3
 * 由 56dp 同步收 48——切换不跳变=两态同高），切换不跳变。
 * 手势语义：按下=开始录音+震动+声波纹；上滑累计超阈值=cancelled（滑回恢复）；
 * 松手按 cancelled 分流发送/取消；系统打断手势（PointerEventType.Cancel）按取消处理防误发。
 * 单指跟踪（取首 change）：按住说话为单指交互，多指边角不做（可接受边界）。
 */
@Composable
private fun VoiceHoldArea(
    onVoiceStart: () -> Unit,
    onVoiceEnd: () -> Unit,
    onVoiceCancel: () -> Unit,
) {
    val context = LocalContext.current
    var holding by remember { mutableStateOf(false) }
    var cancelled by remember { mutableStateOf(false) }
    // 声波纹是整块覆盖语义（FIFO 两块一替）：mutableStateOf + neverEqualPolicy 单次赋值
    // 驱动失效，免 SnapshotStateList 逐元素写的快照/通知开销（~10-20Hz）
    val levels = remember {
        mutableStateOf(MutableList(WAVE_BAR_COUNT) { 0f }, neverEqualPolicy())
    }
    val cancelThresholdPx = with(LocalDensity.current) { CANCEL_DRAG_THRESHOLD_DP.dp.toPx() }

    // 声波纹订阅配对（守卫四问①）：仅按住期间 collect captureBlocks（SharedFlow
    // DROP_OLDEST，多订阅者不阻塞采麦管线）；holding 翻 false / 离开组合时
    // LaunchedEffect 自动 cancel——UI 绝不开采麦（采麦开关全在 VoiceSessionManager）
    LaunchedEffect(holding) {
        if (!holding) return@LaunchedEffect
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
                    holding = true
                    cancelled = false
                    onVoiceStart()
                    vibrateShort(context)
                    var totalDy = 0f
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.first()
                            // consume 后 changedToUp 恒 false（compose-ui 1.6 语义），必须先判后吞
                            val isUp = change.changedToUp()
                            totalDy += change.positionChange().y
                            change.consume()
                            // 实时重算：滑回阈值下即恢复发送态（可回退，守卫四问②）
                            cancelled = totalDy < -cancelThresholdPx
                            if (isUp) break
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // 手势协程被系统/父级打断（compose-ui 1.6 无 PointerEventType.Cancel，
                        // 取消以 CancellationException 形式到达）：按取消处理防误发，
                        // 收尾后不重抛（本块已结束，录音终态必须在此物化）
                        cancelled = true
                    }
                    holding = false
                    if (cancelled) onVoiceCancel() else onVoiceEnd()
                }
            },
        shape = RoundedCornerShape(XiaoduoDimens.CornerInputBar),
        // 取消态粉底 gate 在 holding 上：cancelled 是 remember，松手后仍 true，
        // 不看 holding 会让 idle 态永久挂 errorContainer（真机 P3）
        // S8-1：idle 回 surfaceVariant 独立底色（与 S7 两层版一致；不再透明融入单容器）
        color = when {
            holding && cancelled -> MaterialTheme.colorScheme.errorContainer
            holding -> MaterialTheme.colorScheme.outline
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!holding) {
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
                        "按住说话",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 「松手发送，上滑取消」压声波纹上方（spec H 节，盲测返工加逗号分隔）
                    Text(
                        if (cancelled) "松开取消" else "松手发送，上滑取消",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (cancelled) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    WaveformBars(
                        levelsState = levels,
                        color = if (cancelled) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
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

/** 上滑取消阈值（spec H 节 ~80dp：拇指上滑一个明显行程，防误触）。 */
private const val CANCEL_DRAG_THRESHOLD_DP = 80

/** 按住开始短震 30ms（#2 修复）；minSdk 26 起 VibrationEffect 可用，无版本分支。 */
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
