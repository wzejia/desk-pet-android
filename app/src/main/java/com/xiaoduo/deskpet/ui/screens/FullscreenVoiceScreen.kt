package com.xiaoduo.deskpet.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.min
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.xiaoduo.deskpet.PetOverlayService
import com.xiaoduo.deskpet.chat.ChatMessage
import com.xiaoduo.deskpet.chat.ChatViewModel
import com.xiaoduo.deskpet.pet.PetFrameTable
import com.xiaoduo.deskpet.pet.PetState
import com.xiaoduo.deskpet.ui.PetView
import com.xiaoduo.deskpet.ui.theme.XiaoduoDimens
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.util.Prefs
import com.xiaoduo.deskpet.voice.EnergyGate
import com.xiaoduo.deskpet.voice.ReplyCarrier
import com.xiaoduo.deskpet.voice.VoiceClientFactory
import com.xiaoduo.deskpet.voice.VoiceStateRouter

/**
 * 全屏语音模式（S7-d，ADR 0003）：复用 VoiceSessionManager/KWS 共享链路，不另开 KWS。
 * - 界面：近白 #FAFAFA 满屏（用户 2026-08-04 拍板改判，原黑场 #0D0D0D 废弃，色值选择
 *   理由见 FullscreenBg 注释）+ 居中 PetView ≈2.3×（宽 min(440dp, 屏宽) 钳制、高按
 *   192:208 等比——真机屏宽 360dp 硬约束，超宽必被屏缘平切，见 PET_FULLSCREEN_MAX_W_DP）
 *   + stateFlow 相位驱动四态（listening→waiting / processing→review / speaking→talking）。
 * - 状态反馈：小朵正下方相位 caption（主语化，与宠物四态同源）+ 聆听/说话态状态小图标
 *   （mic+声波柱随 RMS / 小喇叭）。语义边界——spec「无文字默认」指无**转写**文字
 *   （转写开关仍默认关），caption 是状态反馈不是转写。
 * - 操作可见性：右上角低存在感关闭钮（=退出全屏，唯一退出入口）。
 *   （2026-08-07 精简拍板：相位光环 PhaseHalo / 红色挂断钮 / 长按菜单全删——挂断钮与
 *   关闭钮同为退出全屏零功能损失；菜单三项「退出全屏/打开设置/隐藏悬浮窗」均与既有入口
 *   重复（设置走主 App，悬浮窗开关设置页已有）；状态反馈由 caption+迷你声波+宠物四态承担。
 *   悬浮窗手势集的长按菜单不在本屏，不受影响。）
 * - 进出配对（DisposableEffect）：进入＝会话预热 → 隐藏悬浮窗双窗 → 载体切 FULLSCREEN；
 *   退出严格逆序（先恢复 OVERLAY 再恢复窗口，两语句间 append 落 GONE 窗不闪泡）。
 * - 回复默认无文字（TTS+小朵 talking，全屏分支 BubbleSink 恒 no-op 天然达成）；
 *   设置项「全屏转写」开 → 底部渲染对话流最后一条新增 assistant（流式跟随，VM 单一事实源）。
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

    // 进入读一次即可：设置页改动发生在离开全屏期间，回来自动重进组合重读
    val transcriptOn = remember { Prefs.fullscreenTranscript(context) }
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

    // 尺寸钳制（critic 返工 P1）：PetView.onDraw 帧图拉伸填满 View 无内缩，视图宽超屏宽
    // 必被屏缘平切。真机 1440×3168 @640dpi → 屏宽 360dp；spritesheet 四态内容 bbox
    // ~80% 帧宽（x 极值 18-173/192）→ 等比上限 ≈2.3×（440dp 视图内容恰贴屏缘）。
    // 取 min(2.3× 设计宽, 屏宽)，高按帧纵横比 192:208 等比跟随。
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val petWidth = min(PET_FULLSCREEN_MAX_W_DP.dp, screenWidthDp)
    val petHeight = petWidth * PetView.PET_HEIGHT_DP / PetView.PET_WIDTH_DP

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FullscreenBg),
    ) {
        // 右上角关闭（盲测返工#2：静态图无任何退出路径——低存在感圆形容器适配深色底，
        // 样式语言对齐顶栏 mic 入口；2026-08-07 起为唯一退出入口）
        IconButton(
            onClick = {
                PetLogger.i(TAG, "close button -> exit fullscreen")
                onExit()
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(XiaoduoDimens.SpaceS),
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

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AndroidView(
                factory = { ctx -> PetView(ctx).also { petView = it } },
                modifier = Modifier
                    .width(petWidth)
                    .height(petHeight),
            )
            // 相位 caption（盲测返工#1：静态图无任何状态线索——订阅 stateFlow 与宠物四态
            // 同源）。语义边界防误删：spec「无文字默认」指无**转写**文字（转写开关仍默认关），
            // 本 caption 是状态反馈不是转写。降级时 caption 位让给出路提示（KWS 未开/mic 未授权，
            // 原中央提示保留于此，此时相位文案无意义且会误导）
            val hint = when {
                !micGranted -> "麦克风未授权，语音不可用；请退出后到设置页授权"
                !kwsOn -> "语音唤醒未开启，请退出后到设置页开启"
                else -> null
            }
            Spacer(modifier = Modifier.height(XiaoduoDimens.SpaceM))
            // caption 行（盲测返工①②）：文案主语化消歧 + 聆听/说话态左侧状态小图标——
            // LISTENING=mic 图标+迷你声波柱（随真实收音 RMS 起伏，静态截图也可见 mic）；
            // SPEAKING=小喇叭；PROCESSING/IDLE 不需要图标
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hint == null) {
                    when (voiceState) {
                        VoiceStateRouter.State.LISTENING -> {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = HaloCyan,
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            MiniWaveform(rms = listenRms, color = HaloCyan)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        VoiceStateRouter.State.SPEAKING -> {
                            Icon(
                                Icons.Filled.VolumeUp,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = HaloCyan,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        else -> Unit
                    }
                }
                Text(
                    text = hint ?: captionFor(voiceState),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                    color = if (hint != null) HintColor else CaptionColor,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // 底部区：仅全屏转写（设置项，默认关）——渲染进入后新增的最后一条 assistant，流式跟随
        // （2026-08-07 精简：原底部红色挂断钮与「长按小朵打开菜单」提示已删，见文件头注释）
        if (transcriptOn) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(
                        horizontal = XiaoduoDimens.SpaceL,
                        vertical = XiaoduoDimens.SpaceM,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val lastAssistant = messages
                    .drop(transcriptBaseline) // 进入前旧消息不渲染（基线见上）
                    .lastOrNull { it.role == ChatMessage.Role.ASSISTANT }
                lastAssistant?.takeIf { it.text.isNotEmpty() }?.let { msg ->
                    Text(
                        text = msg.text,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        color = TranscriptColor,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
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
 *  状态反馈非转写，与宠物四态同源）。 */
private fun captionFor(state: VoiceStateRouter.State): String = when (state) {
    VoiceStateRouter.State.IDLE -> "说『小朵小朵』开始对话"
    VoiceStateRouter.State.LISTENING -> "正在听你说…"
    VoiceStateRouter.State.PROCESSING -> "小朵正在思考…"
    VoiceStateRouter.State.SPEAKING -> "小朵正在回答…"
}

/**
 * 聆听态迷你声波柱（盲测返工②，caption 左侧第二状态线索）：
 * 3 柱高随真实收音 RMS 起伏（listenRms，与已删光环原同一路 EMA 数据源）；
 * 静音时柱落最小高，mic 图标本身已是静态可辨线索。
 */
@Composable
private fun MiniWaveform(rms: Float, color: Color) {
    val level = ((rms - RMS_FLOOR) / RMS_SPAN).coerceIn(0f, 1f)
    Canvas(modifier = Modifier.size(width = 12.dp, height = 14.dp)) {
        val barW = 2.5.dp.toPx()
        val gap = 1.75.dp.toPx()
        val minH = size.height * 0.25f
        for (i in 0 until 3) {
            val factor = when (i) {
                0 -> 0.6f
                1 -> 1f
                else -> 0.75f
            }
            val h = minH + (size.height - minH) * level * factor
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barW + gap), (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

private const val TAG = "FullscreenVoice"

/**
 * 全屏 PetView 宽度上限（dp）：帧 192dp 的 ≈2.3×。
 * 约束来源（critic 返工 P1）：原 2.5×=480dp 只算了放大倍数，没算真机屏宽 360dp
 * （1440px @640dpi）硬约束——PetView 帧图拉伸填满 View，视图超屏宽即被屏缘平切；
 * 帧内容 bbox ~80% 帧宽，2.3×（440dp）时内容恰贴屏缘不裁。高度按 192:208 等比跟随，
 * 不再单列常量（防宽高比脱节）。
 */
private const val PET_FULLSCREEN_MAX_W_DP = 440

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

/** RMS→能级归一（对账表实测域：静音 ~0.001-0.006 / 说话 ~0.036 / 门控上限 0.05）。 */
private const val RMS_FLOOR = 0.005f
private const val RMS_SPAN = 0.045f
private const val RMS_EMA_KEEP = 0.6f // EMA 平滑：保留旧值比例，防逐块跳变
