package com.xiaoduo.deskpet.ui.screens

import android.provider.Settings
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import com.xiaoduo.deskpet.util.NavInsetPolicy
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.xiaoduo.deskpet.chat.ChatMessage
import com.xiaoduo.deskpet.chat.ChatViewModel
import com.xiaoduo.deskpet.ui.components.MarkdownContent
import com.xiaoduo.deskpet.chat.SuggestedQuestions
import com.xiaoduo.deskpet.ui.PetView
import com.xiaoduo.deskpet.ui.icons.DenseShelfIcon
import com.xiaoduo.deskpet.ui.icons.FullscreenVoiceIcon
import com.xiaoduo.deskpet.ui.theme.XiaoduoDimens
import com.xiaoduo.deskpet.util.AppLauncher
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.voice.VoiceClientFactory

/**
 * Chat 主页（S27 底部导航改版）：ModalNavigationDrawer → 消息流 LazyColumn（顶到系统
 * 状态栏下，无顶栏——S27 拍板：宠物头像/相位文案退役）→ 底部输入条 + 导航行贴底
 * （三段式：汉堡｜密集架键居中｜全屏语音键，S27-1c v4 拍板全屏四角图标）。
 * 抽屉内容见 [SessionDrawerContent]（收窄露 scrim：搜索/新建/会话列表/长按操作菜单 +
 * 设置入口）。vm 必须由 AppNavHost 显式传入 Activity 级共享实例。
 *
 * 手势与返回键（P1 返工，material3 1.2.1 字节码核验）：
 * - gesturesEnabled = drawerState.isOpen：关闭态禁边缘拖拽开抽屉（防与 Android 14+
 *   边缘返回手势互抢，只走 hamburger 点击）；开启态必须 true——1.2.1 的 scrim onClose
 *   首条指令即 gesturesEnabled 短路（真机「点 scrim 不关」根因），开着时给 true 才恢复
 *   点 scrim/拖拽关闭。
 * - BackHandler：1.2.1 的 ModalNavigationDrawer 不内建任何返回键处理（NavigationDrawerKt
 *   全文无 BackHandler 引用），抽屉开着时 back 直落 NavController、startDestination 上
 *   直接 finish Activity（真机「按返回退到桌面」根因）。自加 BackHandler 只在抽屉开着时
 *   拦截并关抽屉；关着时 enabled=false，聊天根页退出/子页回退行为不变。
 */
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    onOpenFullscreenVoice: () -> Unit,
    vm: ChatViewModel,
) {
    val messages by vm.messages.collectAsState()
    val phase by vm.phase.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val currentSession by vm.currentSession.collectAsState()
    val pendingScrollMessageId by vm.pendingScrollMessageId.collectAsState()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // S8-6：listState 上提到 ChatScreen 顶层，让「查看最近一轮」LaunchedEffect 可触达。
    // MessageList 内原有的冷启动/新消息/流式跟随 effect 仍使用同一实例，行为不变。
    val chatListState = rememberLazyListState()

    // S8-6「查看最近一轮」：悬浮窗菜单拉起 App 时，MainActivity 把意图下沉到 vm.pendingScrollMessageId
    // 哨兵 "__last__"，这里消费：定位到最近一条 assistant 消息，然后清空 pending 防重复滚动。
    // 无最近一轮（messages 空 / 无 assistant）时安全 no-op，仍清空 pending 不卡死。
    LaunchedEffect(pendingScrollMessageId) {
        if (pendingScrollMessageId == null) return@LaunchedEffect
        if (messages.isNotEmpty()) {
            // 等首帧布局出条目再滚，否则 LazyListState.scrollToItem 在 0 条目时无效
            snapshotFlow { chatListState.layoutInfo.totalItemsCount }.first { it > 0 }
            val targetIndex = messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
            if (targetIndex >= 0) {
                chatListState.scrollToItem(targetIndex)
                PetLogger.d("ChatScreen", "view last round: scrolled to index $targetIndex")
            } else {
                PetLogger.d("ChatScreen", "view last round: no assistant message, skip")
            }
        }
        vm.consumePendingScroll()
    }

    // material3 1.2.1 的 ModalNavigationDrawer 不内建返回键处理，抽屉开着时 back
    // 会穿透到 NavController 退出 App；此处拦截只关抽屉。enabled 随 isOpen 走，
    // 抽屉关着时不占位，聊天根页退出/子页返回键行为不受影响。
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // 关闭态 false：禁边缘拖拽开抽屉，不与系统返回手势互抢；开启态 true：
        // material3 1.2.1 的 scrim 点击被 gesturesEnabled 短路，开着时必须给 true
        // 才能点 scrim/拖拽关抽屉（见类 KDoc）
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            SessionDrawerContent(
                sessions = sessions,
                currentSessionId = currentSession?.id,
                onSearch = vm::searchSessions,
                onNewSession = {
                    vm.newSession()
                    scope.launch { drawerState.close() }
                },
                onSwitchSession = { id ->
                    vm.switchSession(id)
                    scope.launch { drawerState.close() }
                },
                onDeleteSession = vm::deleteSession,
                onRenameSession = vm::renameSession,
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                },
            )
        },
    ) {
        Scaffold(
            // S27：顶栏整体移除（无 topBar）——Scaffold contentWindowInsets 默认含状态栏，
            // 消息流自动垫到状态栏下；宠物头像与相位文案不迁移，直接退役（S27 拍板）。
            bottomBar = {
                // S27 修复：bottomBar slot 内多组件会被 Scaffold 全部对齐 bottomCenter
                // （重叠而非竖排），必须显式 Column 竖排——输入栏在上、导航行贴最底
                // （navigationBarsPadding 由 AppNavHost 外层 Column 统一垫）。
                Column {
                // S7-f：text 上提 ChatScreen 级 rememberSaveable（barge-in 首字符打断在此，
                // 不上 VM）；files/keyStopActive 驱动 InputBar chips 与多功能键真值表
                // （keyStopActive=voiceActive∪VSM非IDLE，晚建 VM 不错过在飞语音轮）
                val files by vm.files.collectAsState()
                val voiceActive by vm.keyStopActive.collectAsState()
                var text by rememberSaveable { mutableStateOf("") }
                val pickFile = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent(),
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    // 读字节放 IO；ROB-9：读文件/上传任一异常收敛为 FAILED toast 不逸出协程
                    // （vm.uploadFile 同步段的建会话/刷列表是未守卫磁盘 IO；VM 的 20MB 守卫拒收时 toast）
                    scope.launch(Dispatchers.IO) {
                        val outcome = guardedUi {
                            val picked = readPickedFile(context, uri)
                            when {
                                picked == null -> UploadOutcome.READ_FAILED
                                !vm.uploadFile(picked.first, picked.second) -> UploadOutcome.TOO_LARGE
                                else -> UploadOutcome.OK
                            }
                        }
                        val shown = when (outcome) {
                            is UiOutcome.Ok -> uploadToastFor(outcome.value)
                            is UiOutcome.Failed -> {
                                PetLogger.e("ChatScreen", "upload crashed: ${outcome.error.message}", outcome.error)
                                uploadToastFor(UploadOutcome.FAILED)
                            }
                        }
                        if (shown != null) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, shown, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                    InputBar(
                        busy = phase == ChatViewModel.Phase.SENDING ||
                            phase == ChatViewModel.Phase.STREAMING,
                        voiceActive = voiceActive,
                        files = files,
                        text = text,
                        onTextChange = {
                            // #7 阶段1 barge-in：空→非空首字符即打断当前轮输出（音频+文字全停）
                            if (text.isEmpty() && it.isNotEmpty()) vm.cancelCurrentTurn()
                            text = it
                        },
                        onSend = { content ->
                            vm.send(content)
                            text = ""
                        },
                        onStopTurn = vm::cancelCurrentTurn, // 多功能键停止档（S7-e 同源）
                        onPickFile = { pickFile.launch("*/*") },
                        onRemoveFile = vm::removeFile,
                        onVoiceStart = { startVoiceHold(context) },
                        onVoiceEnd = { stopVoiceHold(context) },
                        onVoiceCancel = { cancelVoiceHold(context) },
                    )
                // S27 底部导航行贴底：汉堡（左）｜密集架键（正中）｜全屏语音键（右）。
                // 两侧 weight(1f) 容器实现中键真居中；三键均 48dp 热区 + 36dp 圆形容器
                // （样式对齐原顶栏钮）。
                BottomNavRow(
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenFullscreenVoice = onOpenFullscreenVoice,
                )
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (messages.isEmpty()) {
                    EmptyState(onSend = vm::send)
                } else {
                    MessageList(
                        messages = messages,
                        onResend = vm::resend,
                        listState = chatListState,
                    )
                }
            }
        }
    }
}


// ---------------- 底部导航行（S27） ----------------

/**
 * 底部导航行（贴系统手势条，输入栏在上）：汉堡菜单（左）｜密集架键（正中）｜全屏语音键（右）。
 * 全屏键用全屏四角图标（v4 拍板），与输入栏「按住说话」麦克风图标区分——
 * 按住说话=语音转文字不进页，此键=进入 S14 全屏语音对话页。
 */
@Composable
private fun BottomNavRow(
    onOpenDrawer: () -> Unit,
    onOpenFullscreenVoice: () -> Unit,
) {
    val context = LocalContext.current
    // S31：键尺寸按屏宽 10% 比例（min 56 / max 96dp 钳制）——板子 160dpi（dp=px）大屏上
    // 固定 dp 视觉过小（36dp=36px 仅占屏宽 3.3%，用户反馈太小）；手机 420dpi 屏宽 ~411dp
    // 时 10%≈41dp 同样被 min 56 托底，双端观感一致。
    val keySize = (LocalConfiguration.current.screenWidthDp * 0.10f).coerceIn(56f, 96f).dp
    val iconSize = keySize * 0.44f
    // S31 终版：上下间距一致 24px（= 当前输入框间距 64px 的一半，用户拍板）。
    // 底距 24dp 由本行自垫；顶距 8dp + 输入框自带 16dp 底 padding = 24px，两段完全
    // 对称，双端一致。
    // S31 板子遮挡修复：10.0.18.47（rk/softwinner ROM）insets 派发断裂——窗口
    // edge-to-edge 但 WindowInsets.navigationBars 报 0，navigationBarsPadding 失效，
    // 三键栏（56px）盖住导航行下 1/3。改用 NavInsetPolicy：max(派发值， 三键
    // navigation_mode=0 时的 framework 资源高度兑底)；手势模式不兑底防误垫。
    val density = LocalDensity.current
    val dispatchedNavInset = WindowInsets.navigationBars.getBottom(density)
    val navMode = remember {
        runCatching {
            Settings.Secure.getInt(context.contentResolver, "navigation_mode", 0)
        }.getOrDefault(0)
    }
    val resourceBarHeight = remember {
        runCatching {
            val res = android.content.res.Resources.getSystem()
            res.getDimensionPixelSize(res.getIdentifier("navigation_bar_height", "dimen", "android"))
        }.getOrDefault(0)
    }
    val navInsetDp = with(density) {
        NavInsetPolicy.effectiveBottomInset(dispatchedNavInset, navMode, resourceBarHeight).toDp()
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = navInsetDp)
                .padding(horizontal = XiaoduoDimens.SpaceM)
                .padding(top = 8.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                IconButton(onClick = onOpenDrawer) {
                    Box(
                        modifier = Modifier
                            .size(keySize)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = "打开菜单",
                            modifier = Modifier.size(iconSize),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            IconButton(
                onClick = {
                    val ok = AppLauncher.startWithToast(context)
                    PetLogger.i("ChatScreen", "nav row: open dense shelf app, ok=$ok")
                },
            ) {
                Box(
                    modifier = Modifier
                        .size(keySize)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        DenseShelfIcon,
                        contentDescription = "打开密集架",
                        modifier = Modifier.size(iconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                IconButton(onClick = onOpenFullscreenVoice) {
                    Box(
                        modifier = Modifier
                            .size(keySize)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            FullscreenVoiceIcon,
                            contentDescription = "全屏语音模式",
                            modifier = Modifier.size(iconSize),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ---------------- 空态 ----------------

/**
 * 空态（对齐小布助手形态，用户拍板本期做）：大号宠物（96dp，帧 192×208 等比 96×104，
 * IDLE 相位由 PetView 默认给出）+ 问候语 + 职能说明（档案管理专业法律咨询助手）
 * + 3 条建议问句卡片（竖排、圆角浅底，点击 = 以该文本走 vm.send 发一条用户消息）
 * + 「换一换」（从 [SuggestedQuestions] 池随机换批，优先避开当前批）。
 * 批次只 remember 不落盘：杀进程/重组重抽即可，无恢复价值。
 */
@Composable
private fun EmptyState(onSend: (String) -> Unit) {
    var batch by remember { mutableStateOf(SuggestedQuestions.pick(SUGGESTION_COUNT)) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = XiaoduoDimens.SpaceL),
        verticalArrangement = Arrangement.Center,
    ) {
        AndroidView(
            factory = { context -> PetView(context) },
            modifier = Modifier
                .width(96.dp)
                .height(104.dp),
        )
        Spacer(modifier = Modifier.height(XiaoduoDimens.SpaceM))
        Text(
            text = "你好，我是小朵",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(XiaoduoDimens.SpaceS))
        Text(
            text = "你的档案管理专业法律咨询助手",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(XiaoduoDimens.SpaceL))
        Column(verticalArrangement = Arrangement.spacedBy(XiaoduoDimens.SpaceS)) {
            for (question in batch) {
                Surface(
                    onClick = { onSend(question) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(XiaoduoDimens.CornerCard),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = question,
                        modifier = Modifier.padding(
                            horizontal = XiaoduoDimens.SpaceM,
                            vertical = 14.dp,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(XiaoduoDimens.SpaceS))
        TextButton(onClick = {
            batch = SuggestedQuestions.pick(SUGGESTION_COUNT, exclude = batch)
        }) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "换一换",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 空态建议问句每批条数（卡片 2-3 条拍板取 3，对齐小布参照物）。 */
private const val SUGGESTION_COUNT = 3

// ---------------- 消息流 ----------------

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    onResend: (String) -> Unit,
    listState: LazyListState,
) {
    // 冷启动恢复历史：等 LazyColumn 布局出条目再滚到绝对底部
    LaunchedEffect(Unit) {
        if (messages.isNotEmpty()) {
            snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
            listState.scrollToBottom(messages.size - 1)
        }
    }
    // 新消息到达（条数变化，含自己刚发的）无条件滚到底；
    // 流式增量仅在用户本就在底部时跟随，翻了历史/在同一条长消息内上翻都不拽回
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && listState.layoutInfo.totalItemsCount > 0) {
            listState.scrollToBottom(messages.size - 1)
        }
    }
    val lastKey = messages.lastOrNull()?.let { it.id to it.text.length }
    val followThresholdPx = with(LocalDensity.current) { 96.dp.toPx() }
    LaunchedEffect(lastKey) {
        if (messages.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()
        // 跟随判定用「末条底缘超出视口的距离」而非底缘是否在视口内：
        // 本 effect 在文本增长后的布局上跑，增长本身已把底缘推出视口，
        // 用「在视口内」判会恒 false 而永远不跟随。超出量 ≤ 阈值（约一次增量的高度）
        // 视为用户本在底部→跟；用户上翻后超出量远大于阈值→不拽回（盲测「底部裁行」返工）
        val overshoot = if (lastVisible != null && lastVisible.index == messages.size - 1) {
            lastVisible.offset + lastVisible.size - info.viewportEndOffset
        } else {
            Int.MAX_VALUE
        }
        if (overshoot <= followThresholdPx) {
            listState.scrollToBottom(messages.size - 1)
        }
    }
    // 顶部不做渐隐遮罩：与 ChatGPT 参照物一致硬裁切。遮罩曾两次制造盲测事故——
    // 长消息首项深度被裁时遮罩恒满高，停靠在视口顶带的行首行被洗成灰字
    // （盲测 v4「表格第 2 行灰字」根因，实测文本带中位亮度 157 vs 邻行 13）；
    // 底部裁行早已由 scrollToBottom 根治，顶部硬切是移动端滚动列表通行形态
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = XiaoduoDimens.SpaceM,
            end = XiaoduoDimens.SpaceM,
            top = XiaoduoDimens.SpaceL,
            // 底部留白加大：末条滚到底后与输入条之间留呼吸距，不顶边（盲测「底部裁行」返工）
            bottom = XiaoduoDimens.SpaceL + XiaoduoDimens.SpaceS,
        ),
        verticalArrangement = Arrangement.spacedBy(XiaoduoDimens.SpaceL),
    ) {
        items(messages, key = { it.id }) { msg ->
            MessageRow(msg = msg, onResend = onResend)
        }
    }
}

/**
 * 滚到绝对底部（盲测「底部裁行」返工的根修）：scrollToItem 只把末条**顶缘**对齐视口顶，
 * 末条比视口高时尾部被输入条裁切；补 scrollBy(MAX) 钳到最大滚动，contentPadding 底部留白收尾。
 */
private suspend fun LazyListState.scrollToBottom(lastIndex: Int) {
    scrollToItem(lastIndex)
    scroll { scrollBy(Float.MAX_VALUE) }
}

@Composable
private fun MessageRow(msg: ChatMessage, onResend: (String) -> Unit) {    if (msg.role == ChatMessage.Role.USER) {
        // 用户：右侧灰底气泡（对标 ChatGPT 用户气泡），上限 82% 宽
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                modifier = Modifier.widthIn(max = 320.dp),
                shape = RoundedCornerShape(XiaoduoDimens.CornerBubble),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = msg.text,
                    modifier = Modifier.padding(
                        horizontal = XiaoduoDimens.SpaceM,
                        vertical = 12.dp,
                    ),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    } else {
        // 小朵：左侧无气泡全宽排版（ChatGPT 克制风）+ 状态反馈行
        Column(modifier = Modifier.fillMaxWidth()) {
            if (msg.text.isNotEmpty()) {
                MarkdownContent(text = msg.text)
            }
            when (msg.status) {
                ChatMessage.Status.SENDING -> TypingDots()

                ChatMessage.Status.STREAMING -> StreamingCursor()

                ChatMessage.Status.ERROR -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(XiaoduoDimens.SpaceS),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.error, CircleShape),
                        )
                        Text(
                            "发送失败",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { onResend(msg.id) }) {
                            Text("重发", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                ChatMessage.Status.DONE -> Unit
            }
        }
    }
}

/** 生成中等候态三点指示（sending 状态反馈）：三圆点错相呼吸，首 token 后随状态切 STREAMING 自然消失。 */
@Composable
private fun TypingDots() {
    // S31 板子不可见修复：点径原写死 6dp——160dpi 板子（10.0.18.47）dp=px，6dp=6px 仅占
    // 屏宽 0.55%，肉眼难辨（同一 APK 模拟器 420dpi=15.75px 可见，机型密度差异）；与导航行
    // 键尺寸同款自适应：点径按屏宽 1.5%，锥制 [6,18]dp——手机/模拟器 ~411dp 宽算出 6.2dp
    // ≈原样零回归，板子 1080dp 宽得 16dp 清晰可见。
    val dotSize = (LocalConfiguration.current.screenWidthDp * 0.015f).coerceIn(6f, 18f).dp
    val gap = (dotSize.value * 0.6f).dp
    // 单一周期进度 0→1（线性，Restart），各点用相位偏移派生 alpha → 经典打字指示器波纹
    val transition = rememberInfiniteTransition(label = "typing-dots")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "progress",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
        for (i in 0 until 3) {
            // 相位错开 1/3 周期；三角波映射到 0.3..1 区间，三点轮流亮灭
            val phase = (progress + i / 3f) % 1f
            val wave = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .alpha(0.3f + 0.7f * wave)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
            )
        }
    }
}

/** 流式光标：▍ 呼吸闪烁（streaming 状态反馈）。 */
@Composable
private fun StreamingCursor() {
    val transition = rememberInfiniteTransition(label = "streaming-cursor")
    val a by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "alpha",
    )
    Text(
        text = "▍",
        modifier = Modifier.alpha(a),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---------------- 语音占位接线（S4 全链路前的过渡） ----------------

/**
 * 按住说话占位：已有可调用的 VoiceClientFactory.session（startListening/stopListening）。
 * 麦克风未授权或调用失败 → toast「语音链路接入中」。
 */
private fun startVoiceHold(context: Context) {
    val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) {
        PetLogger.d("ChatScreen", "voice hold: RECORD_AUDIO not granted, placeholder toast")
        Toast.makeText(context, "语音链路接入中", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching { VoiceClientFactory.session(context).startListening() }
        .onFailure {
            PetLogger.w("ChatScreen", "voice startListening failed: ${it.message}")
            Toast.makeText(context, "语音链路接入中", Toast.LENGTH_SHORT).show()
        }
}

private fun stopVoiceHold(context: Context) {
    runCatching { VoiceClientFactory.session(context).stopListening() }
}

/** S7-f 上滑取消按住说话：终止录音会话不发送（VSM LISTENING 分支 = vad.stop + 回 IDLE）。 */
private fun cancelVoiceHold(context: Context) {
    runCatching { VoiceClientFactory.session(context).cancelCurrentTurn() }
}

/** ROB-9：上传链路收敛结果（UI 协程内不抛异常，映射为用户提示）。 */
internal enum class UploadOutcome { OK, READ_FAILED, TOO_LARGE, FAILED }

/** ROB-9：结果 → toast 文案（OK 为 null 不提示）。纯逻辑，JVM 单测覆盖。 */
internal fun uploadToastFor(outcome: UploadOutcome): String? = when (outcome) {
    UploadOutcome.OK -> null
    UploadOutcome.READ_FAILED -> "读取文件失败"
    UploadOutcome.TOO_LARGE -> "文件超过 20MB，未上传"
    UploadOutcome.FAILED -> "上传失败，请重试"
}

/** 系统文件选择器结果：(显示名, 字节)；ContentResolver 查询/读取任一失败返回 null。 */
private fun readPickedFile(context: Context, uri: Uri): Pair<String, ByteArray>? = runCatching {
    val name = context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        ?.takeIf { it.isNotBlank() } ?: "未命名文件"
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: return null
    name to bytes
}.getOrNull()
