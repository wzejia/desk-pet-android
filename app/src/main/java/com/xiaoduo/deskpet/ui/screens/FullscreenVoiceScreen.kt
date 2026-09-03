package com.xiaoduo.deskpet.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.xiaoduo.deskpet.PetOverlayService
import com.xiaoduo.deskpet.chat.ChatMessage
import com.xiaoduo.deskpet.chat.ChatViewModel
import com.xiaoduo.deskpet.pet.PetFrameTable
import com.xiaoduo.deskpet.pet.PetState
import com.xiaoduo.deskpet.ui.PetView
import com.xiaoduo.deskpet.ui.components.MarkdownContent
import com.xiaoduo.deskpet.ui.FullscreenVoiceLayout
import com.xiaoduo.deskpet.ui.theme.XiaoduoDimens
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.util.Prefs
import com.xiaoduo.deskpet.voice.EnergyGate
import com.xiaoduo.deskpet.voice.ReplyCarrier
import com.xiaoduo.deskpet.voice.VoiceClientFactory
import com.xiaoduo.deskpet.voice.VoiceStateRouter
import kotlin.math.exp
import kotlin.math.sin

/**
 * 全屏语音模式（S7-d，ADR 0003；S14 三段式重排 2026-08-24 原型验收）：复用
 * VoiceSessionManager/KWS 共享链路，不另开 KWS。
 * - 布局（S14-2 三段式，调研=豆包/通义）：顶部栏（左 ✕ 退出 / 右「字幕」开关当场写回
 *   Prefs）→ PetView（宽 0.62×屏宽、中心屏高 42%，440dp 上限钳制保留；原居中 ≈2.3×
 *   方案在 360dp 真机占屏高 49% 压字幕区，已废）→ 状态区下 1/4（相位 caption 17sp；
 *   聆听=7 柱声波 RMS 帧时钟插值 / 思考=三点跳动 / 唤醒应答=「我在」19sp 青色）。
 *   语义边界——spec「无文字默认」指无**转写**文字，caption 是状态反馈不是转写。
 * - 控制（S14-3）：底部抽屉默认只露把手（点把手/上滑展开）——打断键（非 IDLE 可点 →
 *   cancelCurrentTurn）+ 红叉退出；与左上 ✕ 双退出入口（2026-08-24 拍板保留，反转
 *   08-07「关闭钮唯一退出入口」精简拍板：抽屉收纳后应急退出仍要一键可达）。
 *   （2026-08-07 精简拍板：相位光环 PhaseHalo / 红色挂断钮 / 长按菜单全删；
 *   悬浮窗手势集的长按菜单不在本屏，不受影响。）
 * - 进出配对（DisposableEffect）：进入＝会话预热 → 隐藏悬浮窗双窗 → 载体切 FULLSCREEN；
 *   退出严格逆序（先恢复 OVERLAY 再恢复窗口，两语句间 append 落 GONE 窗不闪泡）。
 * - 回复默认无文字（TTS+小朵 talking，全屏分支 BubbleSink 恒 no-op 天然达成）；
 *   「字幕」开 → 状态区上方渲染对话流最后一条新增 assistant（S14：共享 MarkdownContent
 *   富文本 + 112dp 限高可滑动 + 流式贴底跟随，VM 单一事实源）。
 * - 降级不死态：KWS 未开 / mic 未授权 → caption 位显示出路提示（指向主 App 设置页），
 *   不崩不抢权限申请。
 */
@Composable
fun FullscreenVoiceScreen(
    onExit: () -> Unit,
    chatVm: ChatViewModel,
) {
    val context = LocalContext.current
    val session = remember { VoiceClientFactory.session(context) }
    val voiceState by session.stateFlow.collectAsState()
    val messages by chatVm.messages.collectAsState()
    var petView by remember { mutableStateOf<PetView?>(null) }

    // S14-2：字幕开关搬进场内（右上），当场切换写回 Prefs——原进入读一次、改动要退出到设置页
    var transcriptOn by remember { mutableStateOf(Prefs.fullscreenTranscript(context)) }
    val kwsOn = remember { Prefs.kwsEnabled(context) }
    val micGranted = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    // 进/出配对（守卫四问#1：覆盖 popBackStack/手势退出全部出组合路径）
    DisposableEffect(Unit) {
        VoiceClientFactory.session(context) // 预热：与 KWS 前台服务共享同一会话单例
        PetOverlayService.instance?.setWindowsHidden(true)
        VoiceClientFactory.replyCarrier = ReplyCarrier.FULLSCREEN
        PetLogger.i(TAG, "enter fullscreen voice: windows hidden, carrier=FULLSCREEN")
        onDispose {
            // 严格逆序（守卫四问#3）：先切回载体再开窗，途中 append 落 GONE 窗不闪泡
            VoiceClientFactory.replyCarrier = ReplyCarrier.OVERLAY
            PetOverlayService.instance?.setWindowsHidden(false)
            PetLogger.i(TAG, "exit fullscreen voice: carrier=OVERLAY, windows restored")
        }
    }

    // 相位驱动四态（petView 同为 key：视图 attach 晚于相位变化时补齐漏应用，同 PetStatusBar 策略）
    // S8-7+S13：唤醒路径（wakeAckActive）SPEAKING 期间直接 WAITING 静止（唤醒跳已取消，
    // 用户 2026-08-24 拍板），跳过通用 TALKING；与悬浮窗 transition gate 同源对齐（两模式一致）
    val wakeAckActive by session.wakeAckActive.collectAsState()
    LaunchedEffect(voiceState, wakeAckActive, petView) {
        val pv = petView ?: return@LaunchedEffect
        if (voiceState == VoiceStateRouter.State.SPEAKING && wakeAckActive) {
            pv.setState(PetFrameTable.WAITING)
        } else {
            pv.setState(petStateForVoice(voiceState))
        }
    }

    // 聆听态迷你声波数据源：仅 LISTENING 期间订阅 captureBlocks（LaunchedEffect key=voiceState，
    // 离开 LISTENING 即 cancel dispose），逐块 rmsOf 驱动声波柱高；EMA 平滑防逐块跳变。
    // voice 包只读使用现成公开接口，零改动。（原同时喂 PhaseHalo 呼吸环，2026-08-07 光环已删）
    var listenRms by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(voiceState) {
        if (voiceState == VoiceStateRouter.State.LISTENING) {
            session.captureBlocks.collect { block ->
                listenRms = listenRms * RMS_EMA_KEEP + EnergyGate.rmsOf(block) * (1f - RMS_EMA_KEEP)
            }
        }
    }

    // 转写基线（critic 返工 P2 顺手做）：进入时刻消息数为界，只渲染新增 assistant——
    // 否则进全屏瞬间若会话末条恰为 assistant 非空，会直接亮出上一轮旧回复
    val transcriptBaseline = remember { chatVm.messages.value.size }

    // S17 布局锚点：统一走 FullscreenVoiceLayout 计算器（纯 Kotlin 可测，99 组设备
    // 矩阵不变量验证）。三合一适配（用户 2026-08-29 拍板）：宠物 min(0.62×宽, 440dp,
    // 0.45×高等比)（横屏不再顶格压字幕）；字幕跨区（宠物底+16dp -> 状态区顶-12dp，
    // 底对齐可滚动，大屏自动放大）；列宽钳 520dp + 字号随 min(宽,高) 缩放。
    // 关键性质：360×792 手机上输出与 S14-2 验收值一致（零回归）。
    // 尺寸钳制背景（critic 返工 P1）：PetView.onDraw 帧图拉伸填满 View 无内缩，超宽必平切。
    val cfg = LocalConfiguration.current
    val anchors = remember(cfg.screenWidthDp, cfg.screenHeightDp) {
        FullscreenVoiceLayout.anchors(cfg.screenWidthDp.toFloat(), cfg.screenHeightDp.toFloat())
    }
    val fontScale = anchors.fontScale
    var drawerOpen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FullscreenBg),
    ) {
        // 顶部栏：左 ✕ 退出（双入口之一，2026-08-24 拍板保留）/ 右「字幕」开关
        // （S14-2 搬进场内，当场切换写回 Prefs——原要退出到设置页才能改）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(XiaoduoDimens.SpaceS),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    PetLogger.i(TAG, "close button -> exit fullscreen")
                    onExit()
                },
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(CloseBg, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "退出全屏",
                        modifier = Modifier.size(20.dp),
                        tint = CloseTint,
                    )
                }
            }
            Text(
                text = "字幕",
                fontSize = 13.sp,
                color = if (transcriptOn) Color.White else CloseTint,
                modifier = Modifier
                    .background(if (transcriptOn) HaloCyan else CloseBg, RoundedCornerShape(16.dp))
                    .clickable {
                        transcriptOn = !transcriptOn
                        Prefs.setFullscreenTranscript(context, transcriptOn)
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }

        // 中央宠物（S14-2：中心自 Column 居中上移至屏高 42%，给状态区让出下 1/4）
        AndroidView(
            factory = { ctx -> PetView(ctx).also { petView = it } },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = anchors.petTopDp.dp)
                .width(anchors.petWidthDp.dp)
                .height(anchors.petHeightDp.dp),
        )

        // 转写区（字幕开时）：S14-2 随迁至状态区上方（原贴底）；transcriptBaseline 语义不变——
        // 只渲染进入后新增的 assistant，防进全屏瞬间亮出上一轮旧回复
        if (transcriptOn) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
.padding(bottom = anchors.subtitleBottomDp.dp)
.width(anchors.subtitleColumnWidthDp.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val lastAssistant = messages
                    .drop(transcriptBaseline) // 进入前旧消息不渲染（基线见上）
                    .lastOrNull { it.role == ChatMessage.Role.ASSISTANT }
                lastAssistant?.takeIf { it.text.isNotEmpty() }?.let { msg ->
                    // S14 字幕升级（用户 2026-08-24）：富文本（共享聊天页 MarkdownContent，
                    // 段落/列表/代码/表格同渲染）+ 可滑动查看。流式贴底跟随：贴底时新 delta
                    // 自动滚底；用户上滑查看期间不打断，滑回底部（48dp 阈值）恢复跟随。
                    val scrollState = rememberScrollState()
                    val followThresholdPx = with(LocalDensity.current) { 48.dp.toPx() }.toInt()
                    LaunchedEffect(msg.text) {
                        val wasNearBottom = scrollState.maxValue - scrollState.value <= followThresholdPx
                        if (wasNearBottom) {
                            withFrameNanos { } // 等内容布局完成再滚（maxValue 才含新内容）
                            withFrameNanos { }
                            scrollState.scrollTo(scrollState.maxValue)
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = anchors.subtitleMaxHeightDp.dp)
                            .verticalScroll(scrollState),
                    ) {
                        MarkdownContent(
                            text = msg.text,
                            bodyStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = (20 * fontScale).sp),
smallStyle = MaterialTheme.typography.bodySmall.copy(lineHeight = (17 * fontScale).sp),
                            contentColor = TranscriptColor,
                        )
                    }
                }
            }
        }

        // 状态区（S14-2 核心）：屏幕下 1/4（脱离宠物正下方），指示器在上 caption 在下。
        // 聆听=7 柱声波（RMS/EMA 数据源不变）/ 思考=三点跳动 / 唤醒应答=「我在」大字。
        // 语义边界防误删：spec「无文字默认」指无**转写**文字，caption 是状态反馈不是转写；
        // 降级时 caption 位让给出路提示（KWS 未开/mic 未授权，此时相位文案无意义且会误导）
        val hint = when {
            !micGranted -> "麦克风未授权，语音不可用；请退出后到设置页授权"
            !kwsOn -> "语音唤醒未开启，请退出后到设置页开启"
            else -> null
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
.padding(bottom = anchors.statusBottomDp.dp)
.width(anchors.subtitleColumnWidthDp.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (hint == null) {
                when (voiceState) {
                    VoiceStateRouter.State.LISTENING -> ListeningWaveform(rms = { listenRms }, color = HaloCyan)
                    VoiceStateRouter.State.PROCESSING -> ThinkingDotsDiag() // S25c 临时诊断：四机制探针+看门狗，定案后换回 ThinkingDots()
                    else -> Spacer(modifier = Modifier.height(INDICATOR_H_DP.dp)) // 占位防跳动
                }
                Spacer(modifier = Modifier.height(XiaoduoDimens.SpaceM))
            }
            val ackActive = voiceState == VoiceStateRouter.State.SPEAKING && wakeAckActive
            Text(
                text = hint ?: captionFor(voiceState, wakeAckActive),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = if (ackActive) (19 * fontScale).sp else (17 * fontScale).sp,
                ),
                color = when {
                    hint != null -> HintColor
                    ackActive -> AckColor // S14-1：「我在」应声大字（青色强调，纯现场反馈不进消息流）
                    else -> CaptionColor
                },
                textAlign = TextAlign.Center,
            )
        }

        // S14-3 底部控制抽屉（原型验收 2026-08-24）：把手在面板顶缘一体移动，默认只露把手；
        // 打断=非 IDLE 可点（cancelCurrentTurn，与 OverlayMenu 打断同入口）；退出=红叉（双入口之二）
        ControlDrawer(
            open = drawerOpen,
            onToggle = { drawerOpen = !drawerOpen },
            onOpen = { drawerOpen = true },
            onClose = { drawerOpen = false },
            interruptEnabled = voiceState != VoiceStateRouter.State.IDLE,
            onInterrupt = {
                PetLogger.i(TAG, "drawer interrupt -> cancelCurrentTurn")
                session.cancelCurrentTurn()
            },
            onExit = {
                PetLogger.i(TAG, "drawer exit -> exit fullscreen")
                onExit()
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** 会话四态 → 精灵状态（与 VoiceSessionManager.transition 既有映射一致）。 */
private fun petStateForVoice(state: VoiceStateRouter.State): PetState = when (state) {
    VoiceStateRouter.State.LISTENING -> PetFrameTable.WAITING
    VoiceStateRouter.State.PROCESSING -> PetFrameTable.REVIEW
    VoiceStateRouter.State.SPEAKING -> PetFrameTable.TALKING
    VoiceStateRouter.State.IDLE -> PetFrameTable.IDLE
}

/** 会话四态 → caption 文案（主语化消歧，盲测返工①：「正在说话…」谁在说？；
 *  状态反馈非转写，与宠物四态同源）。
 *  S14-1：SPEAKING && wakeAckActive = 唤醒应答期 →「我在」（原显示「小朵正在回答…」误导）。 */
private fun captionFor(state: VoiceStateRouter.State, wakeAckActive: Boolean = false): String = when {
    state == VoiceStateRouter.State.SPEAKING && wakeAckActive -> "我在"
    else -> when (state) {
        VoiceStateRouter.State.IDLE -> "说『小朵小朵』开始对话"
        VoiceStateRouter.State.LISTENING -> "在听，请说…" // S14-2 文案（原型验收）
        VoiceStateRouter.State.PROCESSING -> "小朵思考中…"
        VoiceStateRouter.State.SPEAKING -> "小朵回答中…"
    }
}

/**
 * S14-2 聆听指示器：7 柱 52×28dp 声波（用户反馈「三个点不明显」升级版）。
 * S14 卡顿改进（2026-08-24 用户真机反馈）：原按音频块（~10-20Hz）直接驱动重组，视觉跳变——
 * ①帧时钟插值：RMS 只作目标值在帧循环里读（lambda 延迟读，组合期不触碰状态 → 零重组）；
 * 显示值做帧率无关指数趋近（~120ms 时间常数），60fps 平滑；
 * ②显示/时间状态仅被 Canvas draw 阶段读取 → 只触发重绘不重组（Compose draw-phase 读状态手法）；
 * ③柱间正弦相位摆动：静音时也微微起伏（有生命感），不再 7 柱机械同涨同落。
 */
@Composable
private fun ListeningWaveform(rms: () -> Float, color: Color) {
    val display = remember { mutableFloatStateOf(0f) }
    val timeMs = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dtMs = if (last == 0L) 16f else ((now - last) / 1_000_000f).coerceIn(1f, 50f)
                last = now
                timeMs.longValue = now / 1_000_000
                val target = ((rms() - RMS_FLOOR) / RMS_SPAN).coerceIn(0f, 1f)
                val k = 1f - exp(-dtMs / 120f) // 帧率无关趋近系数（120ms 时间常数）
                display.floatValue += (target - display.floatValue) * k
            }
        }
    }
    Canvas(modifier = Modifier.size(width = 52.dp, height = 28.dp)) {
        val barW = 4.dp.toPx()
        val gap = 4.dp.toPx()
        val minH = size.height * 0.2f
        val factors = floatArrayOf(0.45f, 0.7f, 0.9f, 1f, 0.9f, 0.7f, 0.45f)
        val t = timeMs.longValue / 1000f // draw 阶段读状态：仅重绘，不重组
        val level = display.floatValue
        for (i in factors.indices) {
            val wobble = 0.78f + 0.22f * sin(t * 5.2f + i * 1.7f)
            val h = minH + (size.height - minH) * level * factors[i] * wobble
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barW + gap), (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

/** S14-2 思考指示器：三点错相跳动（••• 行业通用符号，豆包/通义同款）。
 *  2026-09-03 冻结修复：改 24755b9 聆听声波同款「帧时钟 + draw 阶段读状态」——
 *  旧实现 rememberInfiniteTransition 重组式动画在板子（弱机+PROCESSING 期音频重载）
 *  上冻结不动（声波同病史，24755b9 已证同方治愈：Canvas 每帧仅重绘零重组）。 */
@Composable
private fun ThinkingDots() {
    val timeMs = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { now -> timeMs.longValue = now / 1_000_000 }
        }
    }
    Canvas(modifier = Modifier.size(width = 43.dp, height = INDICATOR_H_DP.dp)) {
        val t = timeMs.longValue / 1000f // draw 阶段读状态：仅重绘，不重组
        val dotD = 9.dp.toPx()
        val gap = 8.dp.toPx()
        val lift = 7.dp.toPx()
        val cx0 = (size.width - (3 * dotD + 2 * gap)) / 2f + dotD / 2f
        for (i in 0 until 3) {
            // 升余弦跳动脉冲：周期 1.5s，三点错相 1/3（=500ms 遗留节奏同观感）
            val phase = ((t / 1500f) + i / 3f) % 1f
            val hop = 0.5f - 0.5f * kotlin.math.cos(2f * Math.PI.toFloat() * phase)
            val cy = size.height / 2f - lift * hop
            drawCircle(color = DotColor, radius = dotD / 2f, center = Offset(cx0 + i * (dotD + gap), cy))
        }
    }
}

/** S25c 临时诊断（板子三点冻结定位，定案后整段删除换回 ThinkingDots）：
 *  四行探针区分冻结层——1=Compose 帧钟（withFrameNanos）/2=原生 Choreographer
 *  postFrameCallback/3=协程 delay 计时器/4=旧版 rememberInfiniteTransition；
 *  另有看门狗（Default 线程）：三层计数全停 3s 自动 dump 主线程栈。 */
@Composable
private fun ThinkingDotsDiag() {
    val aTime = remember { mutableLongStateOf(0L) } // 1 帧钟
    val bTime = remember { mutableLongStateOf(0L) } // 2 Choreographer
    val cTick = remember { mutableStateOf(0) } // 3 delay 计时
    val dPhase = remember { mutableStateOf(0) } // 4 旧版（由无限动画的重组驱动计数）
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { now -> aTime.longValue = now / 1_000_000 }
    }
    DisposableEffect(Unit) {
        val ch = android.view.Choreographer.getInstance()
        val cb = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                bTime.longValue = frameTimeNanos / 1_000_000
                ch.postFrameCallback(this)
            }
        }
        ch.postFrameCallback(cb)
        onDispose { ch.removeFrameCallback(cb) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(400)
            cTick.value++
        }
    }
    // 看门狗（Default 线程）：三层计数 3 秒无变化 -> dump 主线程栈（一次冻结一案）
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            var stallA = 0L; var stallB = 0L; var stallC = 0
            var dumped = false
            while (true) {
                kotlinx.coroutines.delay(1000)
                val a = aTime.longValue; val b = bTime.longValue; val c = cTick.value
                val aStuck = a == stallA; val bStuck = b == stallB; val cStuck = c == stallC
                if (aStuck && bStuck && cStuck) {
                    if (!dumped) {
                        dumped = true
                        val st = android.os.Looper.getMainLooper().thread.stackTrace
                        PetLogger.e(
                            "S25diag",
                            "ALL STUCK (a=$a b=$b c=$c) main stack:\n" +
                                st.take(28).joinToString("\n") { "  at $it" },
                        )
                    }
                } else {
                    dumped = false
                }
                stallA = a; stallB = b; stallC = c
                PetLogger.i("S25diag", "a=$a b=$b c=$c d=${dPhase.value}")
            }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        DiagDotRow(label = "1帧钟", color = Color(0xFFE5484D)) { ((aTime.longValue / 1500f) % 1f + 1f) % 1f }
        DiagDotRow(label = "2Choreo", color = Color(0xFF46A758)) { ((bTime.longValue / 1500f) % 1f + 1f) % 1f }
        DiagDotRow(label = "3计时", color = Color(0xFF00A2C7)) { (cTick.value % 3) / 3f }
        // 4 旧版：无限动画重组驱动（动=旧时钟也活着；每帧重组计数+1）
        val transition = rememberInfiniteTransition(label = "diagOld")
        val dy by transition.animateFloat(
            initialValue = 0f, targetValue = -7f,
            animationSpec = infiniteRepeatable(tween(500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "diagOldDy",
        )
        LaunchedEffect(dy) { dPhase.value++ }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("4旧版", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9AA0A6))
            repeat(3) { i ->
                Box(
                    Modifier
                        .size(9.dp)
                        .offset(y = (dy - if (i == 1) 3f else 0f).dp)
                        .background(DotColor, CircleShape),
                )
            }
        }
    }
}

/** 诊断单行：三圆点按 phase(0..1) 轮转抬升。 */
@Composable
private fun DiagDotRow(label: String, color: Color, phase: () -> Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        Canvas(modifier = Modifier.size(width = 43.dp, height = 14.dp)) {
            val dotD = 8.dp.toPx()
            val gap = 8.dp.toPx()
            val cx0 = (size.width - (3 * dotD + 2 * gap)) / 2f + dotD / 2f
            val ph = phase()
            for (i in 0 until 3) {
                val local = ((ph + i / 3f) % 1f + 1f) % 1f
                val hop = 0.5f - 0.5f * kotlin.math.cos(2f * Math.PI.toFloat() * local)
                drawCircle(
                    color = color,
                    radius = dotD / 2f,
                    center = Offset(cx0 + i * (dotD + gap), size.height / 2f - 4.dp.toPx() * hop),
                )
            }
        }
    }
}

/**
 * S14-3 底部控制抽屉（原型 v6 验收 2026-08-24）：把手在面板顶缘一体移动，默认 translateY
 * 只露 30dp 把手区；点把手/上滑展开，下滑/再点收起（250ms）。双键无文字说明（豆包风：
 * 60dp 灰底圆 #EDF0F2 统一底色，语义交给图标色）——打断=深色停止块 24dp（非 IDLE 可点），
 * 退出=红叉 28dp（双入口之二，左上 ✕ 保留）。
 */
@Composable
private fun ControlDrawer(
    open: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    interruptEnabled: Boolean,
    onInterrupt: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offsetY by animateDpAsState(
        targetValue = if (open) 0.dp else DRAWER_HIDDEN_OFFSET_DP.dp,
        animationSpec = tween(durationMillis = 250),
        label = "drawerOffset",
    )
    val density = LocalDensity.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .offset { IntOffset(0, offsetY.roundToPx()) }
            .shadow(8.dp, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .background(DrawerBg, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .pointerInput(density) {
                val threshold = with(density) { 48.dp.toPx() }
                var dragTotal = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragTotal = 0f },
                    onVerticalDrag = { _, amount -> dragTotal += amount },
                    onDragEnd = {
                        if (dragTotal < -threshold) onOpen() else if (dragTotal > threshold) onClose()
                    },
                )
            },
    ) {
        // 把手区（30dp 整条可点）：44×5dp 横线，与面板一体移动
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(DRAWER_HANDLE_H_DP.dp)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 9.dp)
                    .size(width = 44.dp, height = 5.dp)
                    .background(HandleColor, RoundedCornerShape(3.dp)),
            )
        }
        // 双键行（无文字说明）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(56.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 打断（停止块）：VSM 非 IDLE 才可点（与 OverlayMenu 打断门控同语义）
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .alpha(if (interruptEnabled) 1f else 0.35f)
                    .background(DrawerBtnBg, CircleShape)
                    .let { if (interruptEnabled) it.clickable(onClick = onInterrupt) else it },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(StopIconColor, RoundedCornerShape(5.dp)),
                )
            }
            // 退出全屏（红叉）
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(DrawerBtnBg, CircleShape)
                    .clickable(onClick = onExit),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "退出全屏",
                    modifier = Modifier.size(28.dp),
                    tint = ExitRed,
                )
            }
        }
    }
}

private const val TAG = "FullscreenVoice"

/**
 * 全屏底色（用户 2026-08-04 拍板改判：黑场 #0D0D0D → 白底）。取近白 #FAFAFA 而非纯白
 * #FFFFFF：宠物白身在纯白上轮廓全靠描边，近白底给白身留一档明度差，描边分层更稳；
 * 同时与 App 主界面浅色 surface 同族，进出全屏不闪色温。
 */
private val FullscreenBg = Color(0xFFFAFAFA)

/** 浅色底配色族（盲测返工④）：caption/转写深灰，hint 中灰，关闭钮浅灰容器+深灰图标。 */
private val TranscriptColor = Color(0xFF3A3A3A)
private val HintColor = Color(0xFF6E6E6E)
private val CaptionColor = Color(0xFF3A3A3A)
private val CloseBg = Color(0x14000000) // 黑 8% 纱（浅底上的低存在感容器）
private val CloseTint = Color(0xFF5A5A5A)

/** 状态图标/声波柱用色：角色霓虹描边青色系加深档（白底对比保证，原 PhaseHalo 环色）。 */
private val HaloCyan = Color(0xFF19B8D8)

/** S14-1「我在」应声 caption 强调色（HaloCyan 加深档，白底可读）。 */
private val AckColor = Color(0xFF1298B5)
private const val INDICATOR_H_DP = 28

/** S14-3 抽屉几何：把手区 30dp；总高 120dp（30+12+60+18），关闭时外露把手 30 → 隐藏偏移 90dp。 */
private const val DRAWER_HANDLE_H_DP = 30
private const val DRAWER_HIDDEN_OFFSET_DP = 90
private val DrawerBg = Color(0xF7FAFAFA)
private val HandleColor = Color(0x2E000000)
private val DrawerBtnBg = Color(0xFFEDF0F2)
private val StopIconColor = Color(0xFF3A3A3A)
private val ExitRed = Color(0xFFFF3B30)
private val DotColor = Color(0xFF9AA0A6)

/** RMS→能级归一（对账表实测域：静音 ~0.001-0.006 / 说话 ~0.036 / 门控上限 0.05）。 */
private const val RMS_FLOOR = 0.005f
private const val RMS_SPAN = 0.045f
private const val RMS_EMA_KEEP = 0.6f // EMA 平滑：保留旧值比例，防逐块跳变
