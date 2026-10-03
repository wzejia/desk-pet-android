package com.xiaoduo.deskpet.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoduo.deskpet.PetOverlayService
import com.xiaoduo.deskpet.agui.AguiClient
import com.xiaoduo.deskpet.agui.AguiClientFactory
import com.xiaoduo.deskpet.agui.FileMeta
import com.xiaoduo.deskpet.agui.UploadStatus
import com.xiaoduo.deskpet.chat.ChatSession
import com.xiaoduo.deskpet.device.DeviceClient
import com.xiaoduo.deskpet.device.DeviceIntentMatcher
import com.xiaoduo.deskpet.device.ShelfMcpBridge
import com.xiaoduo.deskpet.device.ShelfNavIntentMatcher
import com.xiaoduo.deskpet.cloud.CloudClientFactory
import com.xiaoduo.deskpet.cloud.OpenAIChatClient
import com.xiaoduo.deskpet.cloud.TextChatRouter
import com.xiaoduo.deskpet.net.NetConfig
import com.xiaoduo.deskpet.util.Prefs
import kotlinx.coroutines.withContext
import com.xiaoduo.deskpet.ui.overlay.BubbleOwner
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.voice.VoiceClientFactory
import com.xiaoduo.deskpet.voice.VoiceSessionManager
import com.xiaoduo.deskpet.voice.VoiceStateRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 聊天状态层（S2 + 多会话扩 scope）：消息流 + 对话相位 + 会话管理，文字轮经 [TextChatRouter]
 * 路由——平台 AguiClient（增量单条）或云端 OpenAI 兼容端点（08-14 分支，llmIsCloud，本地历史）——
 * 并接线 S3 悬浮窗气泡（首个 delta showBubble / 后续 appendBubble / 完成后延迟隐藏）。
 *
 * - 多会话（implementation-notes 2026-07-31 用户拍板）：每会话独立消息流水与 AG-UI threadId
 *   （threadKey = session.id，新会话首次发送时 createThreadId 并缓存；语音路径继续走默认
 *   thread，与本层互不影响）。杀进程恢复当前会话选择与消息（索引持久化 currentId）。
 * - 消息状态迁移 / 流式追加 / 重发 wire messageId 决策全在 [ChatTurnLogic]（纯逻辑，JVM 单测）；
 *   本层只做 IO 与 Android 接线。delta 清洗经 TurnLogic 的原文缓冲，半截 think 标签跨
 *   delta 拼接无损（implementation-notes 裁决：think 标签不是内容）。
 * - 发送中禁重复发（busy 守卫）；失败 → error + 重发；重发复用 user 消息携带的
 *   wire messageId（踩坑 #25：同 id 重试，空回复率才不从 ~20% 涨到 ~50%）。
 * - 会话操作（new/switch/delete/rename）同样在 busy 时忽略：在途回合的消息流与持久化始终绑定
 *   发起时的会话，杜绝跨会话串写与在途回合被清空后 fail-silent 吞回复
 *   （删除的「长按 + 二次确认」交互在后票 UI 层，本层提供能力）。
 * - 持久化时机：用户消息入列 / done / error（流式 delta 不落盘，
 *   杀进程遗留 streaming 由 ChatHistoryCodec 解码迁移）。
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    /** 对话相位：驱动顶部状态条文案与 PetView 精灵状态（SENDING→review / STREAMING→talking）。 */
    enum class Phase { IDLE, SENDING, STREAMING, ERROR }

    private val store = ChatStore(app)
    private val client: AguiClient = AguiClientFactory.create(app)
    private val turns = ChatTurnLogic()

    /** 云端 LLM 客户端（每轮现取配置：设置页改完下一轮即生效——jadx 取证的原始形态）。 */
    private fun cloudClient(): OpenAIChatClient = CloudClientFactory.chat(getApplication())

    /** S30 文字设备控制：共享 OkHttp（与语音轮同源），设备地址每轮现读即时生效。 */
    private val deviceHttp by lazy { DeviceClient.defaultHttp() }

    /** S41 文字轮补接：密集架 MCP 动画联动桥（与语音轮同桥语义；baseUrl 每轮现读，
     *  设置页改动即时生效；失败只记日志不拖文字管线）。 */
    private val shelfMcpClient = ShelfMcpBridge.Client(
        baseUrl = { Prefs.shelfBaseUrl(app) },
        logger = { msg -> PetLogger.i("ShelfMcpBridge", msg) },
    )

    /**
     * 文字轮 LLM 路由（08-14 云端分支，事故后按 jadx 寄存器级伪码复原，语义见 [TextChatRouter]）：
     * llmIsCloud → OpenAI 兼容云端（本地历史）；否则平台 AG-UI（线程记忆）。模式读取延迟到每轮
     * 调用时（lambda 注入），与 NetConfig 运行时读 SP 的原行为一致。
     */
    private val textChatRouter = TextChatRouter(
        llmIsCloud = { NetConfig.llmIsCloud(getApplication()) },
        cloudClient = ::cloudClient,
        platformClient = client,
        logger = { msg -> PetLogger.d(TAG, msg) },
    )

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase

    /**
     * S8-6「查看最近一轮」一次性滚动事件：MainActivity 接到 EXTRA_VIEW_LAST_ROUND 时
     * [requestScrollToLastRound] 写入哨兵值 [SCROLL_TO_LAST_ROUND]，ChatScreen 的
     * LaunchedEffect 消费后调 [consumePendingScroll] 清空（防重复滚动）。
     * null = 无待消费请求。StateFlow 选型：跨 Activity/Compose 边界同一 VM 实例共享，
     * ChatScreen collectAsState 即时响应（implementation-notes 2026-08-05 S8-6）。
     */
    private val _pendingScrollMessageId = MutableStateFlow<String?>(null)
    val pendingScrollMessageId: StateFlow<String?> = _pendingScrollMessageId

    /** 全部会话（updatedAt 倒序）。 */
    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions

    /** 当前会话（杀进程恢复；会话操作后即时刷新标题/时间）。 */
    private val _currentSession = MutableStateFlow<ChatSession?>(null)
    val currentSession: StateFlow<ChatSession?> = _currentSession

    /**
     * 当前会话待发文件列表（S7-f）：UPLOADING/DONE/ERROR 三态，仅内存不落盘。
     * 生命周期守卫（防跨会话串写）：fileClass/fileId 绑定上传时会话的 threadId——
     * 切会话/新建/删当前会话一律取消在途上传并整清（[resetFiles]）；发送成功只清
     * **本轮挂载快照**（[FileListRules.clearAttached]，不连坐 turn 进行中后到的文件）；
     * Error/被打断保留，重发再挂载同一批文件；UPLOADING 未竟时禁发禁重发
     * （[FileListRules.uploadInFlight]，send/resend 同源 + UI 多功能键 DISABLED）。
     */
    private val _files = MutableStateFlow<List<FileMeta>>(emptyList())
    val files: StateFlow<List<FileMeta>> = _files

    /** 在途上传 job（按 FileMeta.localId 登记；切会话/删除文件时取消）。 */
    private val uploadJobs = HashMap<String, Job>()

    init {
        val index = store.loadIndex()
        val current = index.currentId?.let { cid -> index.sessions.firstOrNull { it.id == cid } }
            ?: index.sessions.maxByOrNull { it.updatedAt }?.also { store.setCurrent(it.id) }
            ?: store.createSession()
        _currentSession.value = current
        _sessions.value = store.loadIndex().sessions.sortedByDescending { it.updatedAt }
        _messages.value = store.loadMessages(current.id)
        if (_messages.value.isNotEmpty()) {
            PetLogger.i(TAG, "session restored: ${_sessions.value.size} sessions, ${_messages.value.size} messages")
        }
        attachVoiceTranscript()
        // S31 双击打断修复：把统一门控版 cancelCurrentTurn 挂到悬浮窗静态桥
        // （文字轮播报期 vsm.state=IDLE，悬浮窗直调 vsm.cancelCurrentTurn 是 no-op）
        PetOverlayService.chatInterrupt = { cancelCurrentTurn() }
        // S48 悬浮窗「创建新会话」：忙态快照 + 新会话桥（不进 APP，气泡提示由 Service 负责）
        PetOverlayService.chatBusyProvider = { busy }
        PetOverlayService.chatNewSession = { newSessionFromOverlay() }
    }

    /**
     * P1：语音轮入当前会话历史（用户拍板语义：语音轮与文字轮是同一会话的不同输入方式），
     * 流式上屏与文字轮一致：ASR 出文本 user 立即入列（DONE）→ 首个 delta 建 assistant
     * 占位（SENDING→STREAMING）→ delta 经 [ChatTurnLogic.applyDelta] 原文缓冲清洗追加 →
     * done/error 收尾落盘。持久化时机同文字轮：user 入列 / done / error（delta 不落盘）。
     * 语义选择（记 implementation-notes）：平台侧语音仍走 AguiClient 默认 thread（现状不动），
     * 本地历史并入聊天页「当前会话」——代价是该会话本地流水与平台 thread 不再一一对应
     * （语音轮不在会话 thread 内），换统一视图。
     * 已知边界：sink 挂在语音会话单例上，本 VM 未创建过的进程会话（纯悬浮窗使用且从未
     * 打开过聊天 UI）语音轮不落历史；AppNavHost 启动即建 VM，常规路径无此洞。
     * 回调线程：全部来自语音管线 IO 线程且单管线按序（user→delta*→done|error），
     * StateFlow 跨线程写安全（同文字轮 runChat 的 IO 协程写法）。
     * 守卫：onUserUtterance→done|error 窗口内 [voiceActive]=true 并入 busy 判定，
     * send/resend/会话操作全部忽略——防切会话后 delta 目标缺失
     * fail-silent 吞回复（只留孤儿 user），也防文字轮与语音轮双写者逐 delta 竞写。
     */
    private fun attachVoiceTranscript() {
        // S37（用户拍板 2026-09-11）：语音轮平台 thread 跟随当前会话——修复「本地一个会话、
        // 平台两个 thread 上下文互不可见」。provider 读当前会话 id（含切会话后新语音轮
        // 归新 thread）；null（无会话极端态）回退 default，由 VSM 侧兑底。
        VoiceClientFactory.session(getApplication()).threadKeyProvider = {
            _currentSession.value?.id
        }
        VoiceClientFactory.session(getApplication()).transcriptSink =
            object : VoiceSessionManager.TranscriptSink {
                @Volatile
                private var currentAssistantId: String? = null

                /** 本轮 assistant 原始累加（done 时作 markDone 终稿，与 applyDelta 缓冲同文）。 */
                private val rawBuffer = StringBuilder()

                override fun onUserUtterance(text: String) {
                    // DATA-3：文字轮在途即拒——防语音/文字双写者对 _messages 交错 RMW
                    // 后写整体覆盖先写（拒入方向安全：用户可再说一遍，历史数据不被覆盖）
                    if (TurnGateRules.textTurnInFlight(
                            _phase.value == Phase.SENDING,
                            _phase.value == Phase.STREAMING,
                        )
                    ) {
                        PetLogger.w(TAG, "voice utterance dropped: text turn in flight (phase=${_phase.value})")
                        return
                    }
                    _voiceActive.value = true // 守卫窗口尽早开启（缩小与主线程 send 的 TOCTOU）
                    // S48 惰性超时轮转：与文字轮同规则（置守卫窗前调用，rotate 内部不查 busy）。
                    // 轮转后下方 _currentSession.value 拿到新会话，本轮消息入新会话/新 thread。
                    rotateSessionIfIdle()
                    val session = _currentSession.value ?: return
                    val userMsg = ChatMessage(
                        newId(),
                        ChatMessage.Role.USER,
                        text,
                        ChatMessage.Status.DONE,
                        System.currentTimeMillis(),
                    )
                    _messages.value = _messages.value + userMsg
                    persist(session)
                }

                override fun onAssistantDelta(delta: String) {
                    val id = ensureAssistant()
                    rawBuffer.append(delta)
                    if (_messages.value.none { it.id == id }) {
                        // 守卫下不应发生；仍留日志防 fail-silent（如清空前后的竞态窗口）
                        PetLogger.w(TAG, "voice delta dropped: assistant message $id missing from history")
                    }
                    _messages.value = turns.applyDelta(_messages.value, id, delta)
                }

                override fun onAssistantDone() {
                    val id = currentAssistantId
                    if (id == null) {
                        // 零 delta 成功轮（理论不可达：空 run 会被 AguiClient 重试至 Error）
                        PetLogger.w(TAG, "voice turn done with no assistant placeholder, nothing to mark")
                        endTurn()
                        return
                    }
                    _messages.value = turns.markDone(_messages.value, id, rawBuffer.toString())
                    endTurn()
                }

                override fun onAssistantError(message: String) {
                    // 零 delta 失败（如 HTTP 500 未出字）也补占位再标错误，历史里可见失败轮
                    val id = ensureAssistant()
                    _messages.value = turns.markError(_messages.value, id)
                    endTurn()
                    PetLogger.w(TAG, "voice turn failed: $message")
                }

                /** 首个 delta / 零 delta 失败时创建 assistant 占位（SENDING），返回其 id。 */
                private fun ensureAssistant(): String {
                    currentAssistantId?.let { return it }
                    val msg = ChatMessage(
                        newId(),
                        ChatMessage.Role.ASSISTANT,
                        "",
                        ChatMessage.Status.SENDING,
                        System.currentTimeMillis(),
                    )
                    _messages.value = _messages.value + msg
                    currentAssistantId = msg.id
                    return msg.id
                }

                private fun endTurn() {
                    currentAssistantId = null
                    rawBuffer.setLength(0)
                    _voiceActive.value = false // 守卫窗口关闭
                    _currentSession.value?.let { persist(it) }
                }
            }
    }

    /**
     * 语音轮流式上屏守卫窗口（onUserUtterance→done|error）：期间视同 busy，
     * send/resend/会话操作全部忽略。独立于 [Phase] 的选型理由：
     * phase 连带顶部状态条文案与 PetView 精灵状态，语音轮精灵态已由
     * VoiceSessionManager 经 PetOverlayService 驱动，复用 phase 会视觉串扰。
     * S7-f 起改 StateFlow：多功能键 STOP 档须随语音轮起止即时重组（原 @Volatile
     * var 不驱动 Compose）。写点不变：transcript sink 的 onUserUtterance/endTurn。
     */
    private val _voiceActive = MutableStateFlow(false)
    val voiceActive: StateFlow<Boolean> = _voiceActive

    /**
     * 多功能键 STOP 档信号（2026-08-05 D 修复）：_voiceActive 事件窗 ∪ VSM 非 IDLE。
     * _voiceActive 是边沿触发（onUserUtterance→endTurn），VM 在语音轮中途创建会错过
     * 边沿、整轮键不显 STOP——真机实证 19:46：唤醒轮流式中用户从桌面打开 App
     * （onCreate 19:46:49 晚于 onUserUtterance 19:46:44），该轮键全程非 STOP。
     * VSM stateFlow 是状态源，晚建 VM 订阅即得当前态；与 cancelCurrentTurn 门控同源。
     */
    val keyStopActive: StateFlow<Boolean> =
        combine(_voiceActive, VoiceClientFactory.session(getApplication()).stateFlow) { va, st ->
            va || st != VoiceStateRouter.State.IDLE
        }.stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = false)

    /** 在途文字轮 job（barge-in cancel 用；runChat 协程自登记，收尾自清）。 */
    @Volatile
    private var chatJob: Job? = null

    /** 在途文字轮 assistant 占位 id（cancel 时 [ChatTurnLogic.markCancelledDone] 目标）。 */
    @Volatile
    private var activeAssistantId: String? = null

    /**
     * 文字轮代际（barge-in 迟到写主防线）：cancel 即 ++。OkHttp blocking 不响应
     * 协程取消，被打断轮的 chat 调用会残跑到自然终结——其 delta 回调与结果写回
     * 都以捕获的 epoch 对不上现值为由丢弃（防跨轮串写/迟到标 DONE 覆盖新轮）。
     */
    @Volatile
    private var turnEpoch = 0

    /**
     * barge-in（#7 阶段1 键盘打断，输入框首字符触发）：音频+文字全停。
     * - 语音轮：转 [VoiceSessionManager.cancelCurrentTurn]（停「我在」/TTS/管线 + 部分回复
     *   done + 收泡，终态恰好一次由 VSM 轮守卫保证）。门控（critic P1-② 拍板修）=
     *   voiceActive || VSM 非 IDLE——voiceActive 只覆盖 onUserUtterance→done|error 窗口，
     *   SPEAKING(wozai)/LISTENING 期 voiceActive=false，仅以它门控则「我在」播放期/
     *   聆听期的键盘打断不可达（与 spec「音频+文字全停」冲突）；
     * - 文字轮（VSM IDLE 才落此路径，语义不变）：epoch++ + cancel job + 部分回复标 DONE
     *   （[ChatTurnLogic.markCancelledDone]，非 Error——已流出文本是有效内容，error 会出
     *   「重发」误导）+ phase 复位 + claim 新 round 立刻收泡（上轮 12s 延迟 hide 随之失效）。
     * 幂等：无在途轮时 epoch++ 无害，其余步骤由 null 守卫跳过；连续打断（STREAMING 中
     * 打断→立刻再发→再打断）靠 epoch 单调 + Job.cancel 幂等成立。
     * 留痕（critic P3-⑩）：组合态边角——文字轮 STREAMING 中恰有 VSM 非 IDLE（如文字轮
     * 在飞时又按住说话）时 barge-in 走语音分支，文字轮本轮不被打断（继续流完）。
     * 语音优先是拍板语义；该组合需用户连点两次不同输入方式，属可接受边界。
     */
    fun cancelCurrentTurn() {
        val vsm = VoiceClientFactory.session(getApplication())
        if (_voiceActive.value || vsm.state != VoiceStateRouter.State.IDLE) {
            PetLogger.i(TAG, "barge-in: cancel voice turn (voiceActive=${_voiceActive.value}, vsmState=${vsm.state})")
            vsm.cancelCurrentTurn()
            return
        }
        val assistantId = activeAssistantId
        turnEpoch++
        chatJob?.cancel()
        chatJob = null
        if (assistantId == null) return // 无在途文字轮：no-op
        PetLogger.i(TAG, "barge-in: cancel text turn")
        activeAssistantId = null
        _messages.value = turns.markCancelledDone(_messages.value, assistantId)
        _currentSession.value?.let { persist(it) }
        _phase.value = Phase.IDLE
        val svc = PetOverlayService.instance ?: return
        val round = svc.claimBubbleRound(BubbleOwner.CHAT)
        svc.hideBubble(BubbleOwner.CHAT, round)
    }

    /** S8-6：悬浮窗「查看最近一轮」入口写入哨兵，待 ChatScreen 消费。 */
    fun requestScrollToLastRound() {
        _pendingScrollMessageId.value = SCROLL_TO_LAST_ROUND
    }

    /** S8-6：ChatScreen 消费后清空 pending（防重复滚动 / 配置变更重入）。 */
    fun consumePendingScroll() {
        _pendingScrollMessageId.value = null
    }

    private val busy: Boolean
        get() = _phase.value == Phase.SENDING || _phase.value == Phase.STREAMING || _voiceActive.value

    // ---------------- S7-f 文件上传 ----------------

    /**
     * 上传文件到当前会话 thread（fileClass=agent----{agentId}----{threadId}，AguiClient 内拼）。
     * >20MB 直接拒（返回 false，UI 提示，不进列表）——平台无实测上限，前端限额见 spec 边界表。
     * 占位 UPLOADING 先入列 → 异步上传 → 成功回写平台 meta（localId 不变）/ 失败标 ERROR。
     * 上传绑定调用时的当前会话；之后切会话会取消在途 job 并清空列表（见 [resetFiles]）。
     */
    fun uploadFile(fileName: String, bytes: ByteArray): Boolean {
        if (bytes.size > MAX_UPLOAD_BYTES) {
            PetLogger.w(TAG, "upload rejected: $fileName ${bytes.size} bytes > 20MB")
            return false
        }
        val session = _currentSession.value ?: store.createSession().also {
            _currentSession.value = it
            refreshSessions()
        }
        val placeholder = FileMeta(
            fileId = "",
            fileName = fileName,
            filePath = "",
            fileSize = bytes.size.toLong(),
            fileSuffix = fileName.substringAfterLast('.', ""),
            status = UploadStatus.UPLOADING,
        )
        _files.value = _files.value + placeholder
        PetLogger.i(TAG, "upload start: $fileName ${bytes.size} bytes")
        uploadJobs[placeholder.localId] = viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                client.uploadFile(fileName, bytes, threadKey = session.id)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                PetLogger.e(TAG, "upload crashed: ${t.message}", t)
                AguiClient.UploadResult.Error("上传异常: ${t.message}")
            }
            // 切会话/删文件已取消本 job：blocking 残跑返回的结果丢弃，不回写已清空的列表
            if (!isActive) return@launch
            when (result) {
                is AguiClient.UploadResult.Success -> {
                    _files.value = _files.value.map {
                        if (it.localId == placeholder.localId) {
                            result.meta.copy(localId = it.localId)
                        } else {
                            it
                        }
                    }
                    PetLogger.i(TAG, "upload done: $fileName -> ${result.meta.fileId}")
                }
                is AguiClient.UploadResult.Error -> {
                    _files.value = _files.value.map {
                        if (it.localId == placeholder.localId) it.copy(status = UploadStatus.ERROR) else it
                    }
                    PetLogger.w(TAG, "upload failed: $fileName: ${result.message}")
                }
            }
            uploadJobs.remove(placeholder.localId)
        }
        return true
    }

    /** 从待发列表移除（在途上传一并取消）。 */
    fun removeFile(localId: String) {
        uploadJobs.remove(localId)?.cancel()
        _files.value = _files.value.filterNot { it.localId == localId }
    }

    /** 切会话/发送成功的统一收尾：取消全部在途上传 + 清空列表（防 fileId 跨会话串写）。 */
    private fun resetFiles() {
        uploadJobs.values.forEach { it.cancel() }
        uploadJobs.clear()
        if (_files.value.isNotEmpty()) _files.value = emptyList()
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || busy) {
            if (trimmed.isNotEmpty()) PetLogger.d(TAG, "send ignored: busy")
            return
        }
        // S7-f 边界表：filesList 有 UPLOADING 时禁发（与 resend 同源 single source，
        // UI 多功能键 DISABLED 之外的 VM 双保险）
        if (FileListRules.uploadInFlight(_files.value)) {
            PetLogger.d(TAG, "send ignored: upload in flight")
            return
        }
        // S48 惰性超时轮转：当前会话最后一条消息超 10 分钟无后续 → 先静默新建会话再发
        // （本轮消息入新会话，新 threadId=LLM 无上文；静默无提示，旧会话保留可切回）
        rotateSessionIfIdle()
        val session = _currentSession.value ?: store.createSession().also {
            _currentSession.value = it
            refreshSessions()
        }
        val now = System.currentTimeMillis()
        val wireId = turns.nextWireId()
        val userMsg = ChatMessage(
            newId(),
            ChatMessage.Role.USER,
            trimmed,
            ChatMessage.Status.DONE,
            now,
            wireId = wireId,
        )
        val assistant = ChatMessage(newId(), ChatMessage.Role.ASSISTANT, "", ChatMessage.Status.SENDING, now)
        _messages.value = turns.appendTurn(_messages.value, userMsg, assistant)
        persist(session)
        // fileId 前缀注入在 AguiClient.chat（两路一致挂载点）：本轮捕获 DONE 文件快照，
        // 列表展示文本保持纯用户输入（文件以 chips 形态在输入区呈现）；
        // localId 快照随轮走，成功时只清本轮挂载项（P2-② 不连坐）
        val attached = FileListRules.attachedDone(_files.value)
        // S30 文字设备控制：意图命中 → 直连设备（零 LLM），结果作为 assistant 消息收尾。
        // 与语音轮同一匹配器（S26 保守匹配：须含设备名词，普通提问零刼持）；设备地址空=禁用。
        // 文字轮只上屏不播报（聊天页文字轮无 TTS，与用户预期一致）。
        val deviceCmd = deviceIntentOrNull(trimmed)
        if (deviceCmd != null) {
            runDeviceTurn(session, deviceCmd, assistant.id)
            return
        }
        // S46 密集架页面导航：本地意图命中 → 直连跳页（零 LLM），本地回复上屏。
        // 与 S30 设备轮同模式；拦截后不发云端——避免「打开借阅页面」被误路由到
        // archive_borrow（执行语义） 引发多余工具调用。
        val shelfPage = shelfNavPageOrNull(trimmed)
        if (shelfPage != null) {
            runShelfNavTurn(session, shelfPage, assistant.id)
            return
        }
        runChat(
            session,
            trimmed,
            assistant.id,
            wireId,
            fileRefs = attached.map { it.fileId to it.fileName },
            attachedLocalIds = attached.map { it.localId }.toSet(),
        )
    }

    /** 设备地址已配置时返回文字输入的设备意图（否则 null → 走 LLM 常规轮）。 */
    private fun deviceIntentOrNull(text: String): DeviceIntentMatcher.Command? =
        DeviceTurnRules.gate(Prefs.deviceBaseUrl(getApplication()), text)

    /** S46：密集架地址已配置时返回文字输入的页面导航意图（否则 null）。 */
    private fun shelfNavPageOrNull(text: String): String? =
        if (Prefs.shelfBaseUrl(getApplication()).isEmpty()) null
        else ShelfNavIntentMatcher.match(text)

    /**
     * S46 密集架页面导航轮：同步 POST mcpUiNavigate（IO 线程）→ 结果 speech 作为
     * assistant 终态（DONE）上屏。失败降级为 Fail 文案，不抛异常不标 ERROR 轮
     * （设备轮同款：联动不可达是常态而非崩溃）。
     */
    private fun runShelfNavTurn(
        session: ChatSession,
        page: String,
        assistantId: String,
    ) {
        _phase.value = Phase.SENDING
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { shelfMcpClient.navigate(page) }
                .getOrElse { false }
            val speech = if (ok) ShelfNavIntentMatcher.navSpeech(page) // S62：共用回执（home=已回到首页）
                         else "密集架联动不可用，请检查网络后重试"
            PetLogger.i(TAG, "shelf nav turn done: page=$page ok=$ok")
            withContext(Dispatchers.Main) {
                _messages.value = turns.markDone(_messages.value, assistantId, speech)
                persist(session)
                _phase.value = Phase.IDLE
            }
        }
    }

    /**
     * 文字设备轮：直连执行（IO 线程）→ 结果 speech 作为 assistant 终态（DONE）上屏。
     * 失败降级为 Fail 播报（语音轮同款），不抛异常不标 ERROR 轮——设备不可达是常态而非崩溃。
     */
    private fun runDeviceTurn(
        session: ChatSession,
        cmd: DeviceIntentMatcher.Command,
        assistantId: String,
    ) {
        _phase.value = Phase.SENDING
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                DeviceClient(
                    http = deviceHttp,
                    baseUrl = Prefs.deviceBaseUrl(getApplication()),
                    // 异常原因必须进日志（S30 排查痛：未传 logger 时 CLEARTEXT 拦截被吞）
                    logger = { msg -> PetLogger.w(TAG, "device skill: $msg") },
                ).execute(cmd)
            }.getOrElse { DeviceClient.SkillResult.Fail("设备控制异常，请重试") }
            val speech = result.speech
            PetLogger.i(TAG, "device turn done: cmd=$cmd speech=$speech")
            withContext(Dispatchers.Main) {
                _messages.value = turns.markDone(_messages.value, assistantId, speech)
                persist(session)
                _phase.value = Phase.IDLE
            }
        }
    }

    /** 失败重发：复用同一 wire messageId（踩坑 #25），assistant 消息清空重跑。 */
    fun resend(assistantId: String) {
        if (busy) return
        // P2-①：与 send 同款禁发守卫（同源 single source）——在途上传 + 点「重发」
        // 若放行，turn 成功清场会连带取消在途上传
        if (FileListRules.uploadInFlight(_files.value)) {
            PetLogger.d(TAG, "resend ignored: upload in flight")
            return
        }
        val session = _currentSession.value ?: return
        val plan = turns.prepareResend(_messages.value, assistantId)
        if (plan == null) {
            PetLogger.w(TAG, "resend aborted: no resendable error turn for $assistantId")
            return
        }
        _messages.value = plan.messages
        PetLogger.i(TAG, "resend assistantMsg=$assistantId")
        // Error/被打断轮不清文件列表：重发把当前 DONE 文件再挂载（同 send 的快照语义）
        val attached = FileListRules.attachedDone(_files.value)
        runChat(
            session,
            plan.userText,
            plan.assistantId,
            plan.wireId,
            fileRefs = attached.map { it.fileId to it.fileName },
            attachedLocalIds = attached.map { it.localId }.toSet(),
        )
    }

    /** 新建空会话并切换（busy 时忽略）。 */
    fun newSession() {
        if (busy) {
            PetLogger.d(TAG, "newSession ignored: busy")
            return
        }
        newSessionInternal()
    }

    /**
     * S48 悬浮窗「创建新会话」桥入口（不进 APP）：成功静默新建返回 true；
     * busy 拒绝返回 false（菜单弹出时已按快照置灰，此处为双保险）。气泡提示由 Service 负责。
     */
    fun newSessionFromOverlay(): Boolean {
        if (busy) {
            PetLogger.d(TAG, "newSessionFromOverlay ignored: busy")
            return false
        }
        newSessionInternal()
        return true
    }

    private fun newSessionInternal() {
        val session = store.createSession()
        turns.reset()
        resetFiles() // S7-f：文件绑定旧会话 thread，不可带入新会话
        _phase.value = Phase.IDLE
        _messages.value = emptyList()
        _currentSession.value = session
        refreshSessions()
        PetLogger.i(TAG, "new session, ${_sessions.value.size} total")
    }

    /**
     * S48 惰性超时轮转：当前会话最后一条消息距今超 10 分钟 → 静默新建会话并切换。
     * 与 [newSession] 的差异：不查 busy（调用点在 busy gate 之后/语音守卫窗开启之前）、
     * 无 UI 提示（用户拍板静默）。空会话永不触发（SessionRules.shouldRotateForIdle）。
     * 存储异常降级为不轮转继续原会话（不打断用户当前输入，仅日志）。
     */
    private fun rotateSessionIfIdle() {
        val session = _currentSession.value ?: return
        val lastTs = _messages.value.lastOrNull()?.ts
        val now = System.currentTimeMillis()
        if (!SessionRules.shouldRotateForIdle(lastTs, now)) return
        runCatching {
            val fresh = store.createSession()
            turns.reset()
            resetFiles() // 文件绑定旧会话 thread，不可带入新会话（S7-f 同 newSession）
            _phase.value = Phase.IDLE
            _messages.value = emptyList()
            _currentSession.value = fresh
            refreshSessions()
            PetLogger.i(TAG, "session idle rotate: ${session.id} -> ${fresh.id} (last msg ${now - (lastTs ?: 0)}ms ago)")
        }.onFailure { PetLogger.e(TAG, "session idle rotate failed, keep current", it) }
    }

    /** 切换会话：持久化选择（杀进程恢复），载入该会话消息（busy 时忽略）。 */
    fun switchSession(sessionId: String) {
        if (busy) {
            PetLogger.d(TAG, "switchSession ignored: busy")
            return
        }
        if (sessionId == _currentSession.value?.id) return
        val index = store.loadIndex()
        val target = index.sessions.firstOrNull { it.id == sessionId } ?: return
        store.setCurrent(sessionId)
        turns.reset()
        resetFiles() // S7-f：fileClass/fileId 绑上传时会话 threadId，切走即废
        _phase.value = Phase.IDLE
        _messages.value = store.loadMessages(sessionId)
        _currentSession.value = target
        refreshSessions()
        PetLogger.i(TAG, "switched session, ${_messages.value.size} messages")
    }

    /**
     * 删除会话：持久化数据一并删除，不影响其他会话；删的是当前会话 →
     * 回落到最近更新的会话，无剩余则造新空会话（busy 时忽略）。
     * 本地删除成功后异步同步删平台 thread（用户拍板 2026-07-31）：无 threadId
     * （从未发过消息的新会话）跳过；失败不回滚、不重试，仅记日志（放弃语义，
     * 平台孤儿 thread 为接受的权衡，裁决见 implementation-notes）。
     */
    fun deleteSession(sessionId: String) {
        if (busy) {
            PetLogger.d(TAG, "deleteSession ignored: busy")
            return
        }
        val index = store.deleteSession(sessionId)
        if (sessionId == _currentSession.value?.id) {
            val next = index.currentId?.let { cid -> index.sessions.firstOrNull { it.id == cid } }
                ?: store.createSession()
            turns.reset()
            resetFiles() // S7-f：当前会话被删，其待发文件绑定随之作废
            _phase.value = Phase.IDLE
            _currentSession.value = next
            _messages.value = store.loadMessages(next.id)
        }
        refreshSessions()
        PetLogger.i(TAG, "session deleted, ${index.sessions.size} remaining")
        // 平台 thread 删除 fire-and-forget：本地删除已完成，结果只进日志
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val r = client.deleteThreadForSession(sessionId)) {
                    AguiClient.DeleteResult.NoThread ->
                        PetLogger.d(TAG, "platform thread delete skipped: session had no threadId")
                    AguiClient.DeleteResult.Deleted ->
                        PetLogger.i(TAG, "platform thread deleted for removed session")
                    AguiClient.DeleteResult.AlreadyGone ->
                        PetLogger.i(TAG, "platform thread already gone (idempotent delete)")
                    is AguiClient.DeleteResult.Error ->
                        PetLogger.w(TAG, "platform thread delete failed (local delete kept, no retry): ${r.message}")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // 兜底：fire-and-forget 语义不变，无本地状态需复位（删除已完成），崩溃只进日志
                PetLogger.e(TAG, "platform thread delete crashed (local delete kept): ${t.message}", t)
            }
        }
    }

    /** 全文搜索（拍板②：标题 + 消息全文，按 updatedAt 倒序）。 */
    fun searchSessions(query: String): List<SessionSummary> = store.searchSessions(query)

    /**
     * 重命名会话（ADR 0002 路线 C，本地权威 + best-effort 平台同步，与 deleteSession 同模式）：
     * busy 守卫 → 本地改 title + 置 titleManual（后续消息不再 deriveTitle 覆盖）→
     * 异步 renameThreadForSession（fire-and-forget：失败只 log 不回滚；无 threadId 的新会话跳过）。
     */
    fun renameSession(sessionId: String, title: String) {
        if (busy) {
            PetLogger.d(TAG, "renameSession ignored: busy")
            return
        }
        val updated = store.renameSession(sessionId, title) ?: return
        refreshSessions()
        PetLogger.i(TAG, "session renamed: ${updated.title.length} chars")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val r = client.renameThreadForSession(sessionId, updated.title)) {
                    AguiClient.RenameResult.NoThread ->
                        PetLogger.d(TAG, "platform thread rename skipped: session had no threadId")
                    AguiClient.RenameResult.Renamed ->
                        PetLogger.i(TAG, "platform thread renamed for session")
                    is AguiClient.RenameResult.Error ->
                        PetLogger.w(TAG, "platform thread rename failed (local rename kept, no retry): ${r.message}")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                PetLogger.e(TAG, "platform thread rename crashed (local rename kept): ${t.message}", t)
            }
        }
    }

    private fun runChat(
        session: ChatSession,
        userText: String,
        assistantId: String,
        wireId: String,
        fileRefs: List<Pair<String, String>> = emptyList(),
        attachedLocalIds: Set<String> = emptySet(),
    ) {
        _phase.value = Phase.SENDING
        activeAssistantId = assistantId
        val myEpoch = turnEpoch // 捕获代际：barge-in 后本轮一切迟到写据此丢弃
        // 回合开始即认领气泡 round：上轮 12s 延迟 hide 即刻失效（服务未运行时 round=0，气泡操作全跳过）
        val svc = PetOverlayService.instance
        val myRound = svc?.claimBubbleRound(BubbleOwner.CHAT) ?: 0
        // 新建 thread 时上报的标题取当前消息派生值（首条 user 消息截断），而非会话对象的兜底名
        val threadName = ChatSession.deriveTitle(_messages.value)
        PetLogger.i(TAG, "chat send: ${userText.length} chars")
        chatJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                var bubbleStarted = false
                var bubbleVisible = ""
                // onDelta 两路共用（jadx 双源：lambda 在模式分支判断之前构造）——
                // epoch 防线/相位/气泡节奏与 LLM 模式无关
                val onDelta: (String) -> Unit = onDelta@{ delta ->
                    if (myEpoch != turnEpoch) return@onDelta // barge-in 迟到 delta 防线
                    _messages.value = turns.applyDelta(_messages.value, assistantId, delta)
                    val visible = _messages.value.firstOrNull { it.id == assistantId }?.text ?: return@onDelta
                    _phase.value = Phase.STREAMING
                    svc?.let {
                        when {
                            !bubbleStarted && visible.isNotEmpty() -> {
                                it.showBubble(BubbleOwner.CHAT, myRound, visible)
                                bubbleStarted = true
                                bubbleVisible = visible
                            }
                            bubbleStarted && visible.length > bubbleVisible.length &&
                                visible.startsWith(bubbleVisible) -> {
                                it.appendBubble(BubbleOwner.CHAT, myRound, visible.substring(bubbleVisible.length))
                                bubbleVisible = visible
                            }
                            bubbleStarted && visible != bubbleVisible -> {
                                // 清洗回吐等非前缀形态：重置重打
                                it.showBubble(BubbleOwner.CHAT, myRound, visible)
                                bubbleVisible = visible
                            }
                        }
                    }
                }
                // 08-14 云端分支（事故复原）：llmIsCloud → OpenAI 兼容端点（本地历史拼上下文）；
                // 否则平台 AG-UI（线程记忆）。两路统一产 ChatResult，下游 epoch 守卫/持久化/
                // 文件清场/收泡零分叉（取证依据与 fileRefs 处置见 TextChatRouter KDoc）
                val result = textChatRouter.chat(
                    messages = _messages.value,
                    userText = userText,
                    wireId = wireId,
                    roundId = "u_${System.currentTimeMillis()}", // S67c：每次发言一个回合 ID
                    threadKey = session.id,
                    threadName = threadName,
                    fileRefs = fileRefs,
                    onDelta = onDelta,
                    // S41 文字轮补接：TOOL_CALL_START 工具名 → 密集架动画联动桥
                    onToolCallEvent = { tool, runId -> shelfMcpClient.syncAsync(tool, runId) },
                    // S43 文字轮补接：最后一次工具结果 → 密集架结果卡
                    onToolCallOutcome = { oc ->
                        oc?.let { shelfMcpClient.resultAsync(it.toolCallName, it.content, it.argsJson, it.runId) } // S67：轮次边界
                    },
                )
                if (myEpoch != turnEpoch) return@launch // 已被打断：结果不落盘不改相位
                when (result) {
                    is AguiClient.ChatResult.Success -> {
                        _messages.value = turns.markDone(_messages.value, assistantId, result.text)
                        val finalText = _messages.value.firstOrNull { it.id == assistantId }?.text.orEmpty()
                        persist(session)
                        _phase.value = Phase.IDLE
                        // S7-f 发送成功清场：只清本轮挂载快照（P2-② 不连坐——turn 进行中
                        // 用户新选并传完的 DONE 文件未挂进本轮，必须存活；Error/被打断全保留）
                        if (attachedLocalIds.isNotEmpty()) {
                            _files.value = FileListRules.clearAttached(_files.value, attachedLocalIds)
                            attachedLocalIds.forEach { uploadJobs.remove(it)?.cancel() }
                        }
                        PetLogger.i(
                            TAG,
                            "chat round done: reply ${finalText.length} chars, " +
                                "firstToken=${result.firstTokenLatencyMs}ms, attempts=${result.attempts}",
                        )
                        // 完成 12s 后收泡：服务原语到点复验 round，
                        // 新对话轮/语音占用时已失效不生效（替代旧 VM 局部 bubbleRound 判断）
                        svc?.hideBubbleLater(BubbleOwner.CHAT, myRound, BUBBLE_HIDE_DELAY_MS)
                    }
                    is AguiClient.ChatResult.Error -> {
                        _messages.value = turns.markError(_messages.value, assistantId)
                        persist(session)
                        _phase.value = Phase.ERROR
                        PetLogger.w(TAG, "chat round failed: ${result.message}")
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (myEpoch != turnEpoch) return@launch // 被打断轮的迟到异常不落 Error（cancel 已标 DONE）
                // 兜底：client.chat 契约是 Result 返回，但回调/delta 处理内的意外异常不应直抛 scope
                // （viewModelScope 无 handler → 崩 App，且消息永卡 SENDING、phase 永卡 SENDING/STREAMING
                // 使 busy 守卫吞掉后续一切发送）。复位语义对齐 Error 分支：标错 + 落盘 + phase=ERROR，
                // 用户可经既有重发入口恢复
                PetLogger.e(TAG, "chat round crashed: ${t.message}", t)
                // DONE 守卫(critic nit):markDone 之后的善后步骤(persist/hideBubbleLater)抛异常时,
                // 不把已完成的回复改判 ERROR——否则用户经重发入口会重复投递同一 user 文本拿第二份回复
                val alreadyDone = _messages.value.firstOrNull { it.id == assistantId }
                    ?.status == ChatMessage.Status.DONE
                if (!alreadyDone) {
                    _messages.value = turns.markError(_messages.value, assistantId)
                    persist(session)
                    _phase.value = Phase.ERROR
                }
            } finally {
                // 自清：仅本轮未被新轮/取消换主时清（cancel 路径已清；迟到 finally 不得清掉新轮登记）
                if (myEpoch == turnEpoch) {
                    activeAssistantId = null
                    chatJob = null
                }
            }
        }
    }

    /** 当前会话消息落盘 + 刷新会话列表（标题/updatedAt 随保存派生）。 */
    private fun persist(session: ChatSession) {
        store.saveMessages(session.id, _messages.value)
        refreshSessions()
    }

    private fun refreshSessions() {
        val index = store.loadIndex()
        _sessions.value = index.sessions.sortedByDescending { it.updatedAt }
        val cur = _currentSession.value ?: return
        _currentSession.value = index.sessions.firstOrNull { it.id == cur.id } ?: cur
    }

    private fun newId(): String = UUID.randomUUID().toString()

    companion object {
        private const val TAG = "ChatViewModel"
        private const val BUBBLE_HIDE_DELAY_MS = 12_000L

        /** 单文件上传上限（spec 边界表建议前端限额：平台无实测上限，20MB 提示拒绝）。 */
        private const val MAX_UPLOAD_BYTES = 20 * 1024 * 1024

        /** S8-6 哨兵：[pendingScrollMessageId] 取此值表示「定位到最近一轮 assistant 消息」。 */
        const val SCROLL_TO_LAST_ROUND = "__last__"
    }
}
