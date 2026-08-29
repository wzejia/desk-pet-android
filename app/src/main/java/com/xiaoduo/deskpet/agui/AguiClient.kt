package com.xiaoduo.deskpet.agui

import com.xiaoduo.deskpet.net.TokenPolicy
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 平台 AG-UI 客户端（登录 → token 缓存 → 443 反代 SSE 对话 + thread 管理）。
 * 纯 Kotlin 零 Android import（spec 领域层铁律），持久化与日志全部接口注入，可 JVM 单测。
 *
 * 契约依据：docs/m1-dev-kit/android-dev-brief.md §2.2/§6.1/§6.3 + 踩坑 #23-26/#38-40 +
 * docs/spec/assistant-app.spec.md「AG-UI 链路」与「边界条件」表。
 *
 * 纪律：
 * - 日志禁打 token/password 明文（含任何形式的片段），错误透传平台真实 msg。
 * - 增量单条消息：messages 只发最新一条；重试复用同一消息 id（踩坑 #25）。
 * - 空 run（RUN_FINISHED 但全程 0 字）同 id 自动重试共 [MAX_ATTEMPTS] 次，仍空报「平台繁忙」。
 * - HTTP 401 透明重登 1 次（spec 边界表），重登不消耗空 run 重试次数。
 * - 断流（无 RUN_FINISHED）显式报错，绝不伪装成功（踩坑 #26，由 AguiSseConsumer 判定）。
 * - 删除会话同步删平台 thread（deleteThread，用户拍板 2026-07-31）：幂等目标态——
 *   服务端非成功响应（重复删/不存在）视为已删；失败不回滚本地删除，仅记日志。
 */
class AguiClient(
    private val http: OkHttpClient,
    private val loginUrl: String,
    private val streamUrl: String,
    private val uploadUrl: String = streamUrl.removeSuffix("/agent/stream") + "/common/file/upload",
    private val credentials: () -> Pair<String, String>,
    private val tokenCache: TokenCache,
    private val threadStore: ThreadIdStore,
    /** S16：agentId 动态 provider（设置页切换即时生效，无需重建 client；默认 chatAgent）。 */
    private val agentIdProvider: () -> String = { DEFAULT_AGENT_ID },
    /** S16：agent 列表 POST {listUrl}（443 反代 https://{host}/ai-server/agent/list，实测同族端点）。 */
    private val listUrl: String = streamUrl.removeSuffix("/agent/stream") + "/agent/list",
    private val logger: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** token 缓存抽象（Android 侧由 net/TokenStore 适配，TTL 25min 逻辑在 TokenPolicy）。 */
    interface TokenCache {
        fun cached(): String?
        fun saveFresh(token: String)
        fun clear()
    }

    /** threadId 本地持久化抽象（按会话 key 存取：每会话持有自己的 threadId，首次 createThreadId 后缓存映射）。 */
    interface ThreadIdStore {
        fun load(key: String): String?
        fun save(key: String, threadId: String)

        /** 删除会话后清除本地映射（无论平台删除成败，见 deleteThreadForSession 放弃语义）。 */
        fun remove(key: String)
    }

    sealed interface ChatResult {
        /**
         * 成功：全文 + 首 token 延迟 + 实际尝试次数。
         * firstTokenLatencyMs 口径 = 末次（成功的）attempt 从发出请求到 TEXT_MESSAGE_START 的毫秒数，
         * 不含此前空 run/重登耗时；未见 TEXT_MESSAGE_START 则 null。
         */
        data class Success(val text: String, val firstTokenLatencyMs: Long?, val attempts: Int) : ChatResult

        /** 失败：message 为透传的真实原因（平台 msg / 网络异常 message） */
        data class Error(val message: String) : ChatResult
    }

    /** 单次 SSE 尝试的内部结果。 */
    private sealed interface AttemptResult {
        data class Success(val text: String, val firstTokenLatencyMs: Long?) : AttemptResult
        data object Empty : AttemptResult
        data class Unauthorized(val message: String) : AttemptResult
        data class Failure(val message: String) : AttemptResult
    }

    /** createThreadId 的内部结果（401 单独分型以支持透明重登，对齐 spec 边界表）。 */
    private sealed interface ThreadResult {
        data class Ok(val threadId: String) : ThreadResult
        data object Unauthorized : ThreadResult
        data class Failed(val message: String) : ThreadResult
    }

    /**
     * deleteThread 的结果（删除本地会话同步删服务端 thread，用户拍板 2026-07-31）。
     * 删除是幂等目标态操作：终态 = thread 不存在，服务端拒绝（重复删/不存在）视为已达终态。
     */
    sealed interface DeleteResult {
        /** 服务端确认删除（HTTP 2xx 且信封 code==200）。 */
        data object Deleted : DeleteResult

        /** 幂等视为已删：服务端返回非成功（HTTP 非 2xx 或信封 code!=200，如多端并发重复删同一 thread）。 */
        data object AlreadyGone : DeleteResult

        /** 无 threadId（从未发过消息的新会话 / 空白 id）：跳过平台调用。 */
        data object NoThread : DeleteResult

        /** 网络失败或鉴权失败（透明重登后仍 401）：调用方不回滚本地删除，仅记日志。 */
        data class Error(val message: String) : DeleteResult
    }

    /** deleteThread 单次调用的内部结果（401 单独分型以支持透明重登，与 createThreadId 同模式）。 */
    private sealed interface DeleteCall {
        data object Done : DeleteCall
        data object Gone : DeleteCall
        data object Unauthorized : DeleteCall
        data class Failed(val message: String) : DeleteCall
    }

    /**
     * renameThread 的结果（会话重命名 best-effort 同步平台，ADR 0002 路线 C）。
     * 与删除不同：重命名不是幂等目标态，服务端非成功即失败（调用方只 log 不回滚本地）。
     */
    sealed interface RenameResult {
        /** 服务端确认改名（HTTP 2xx 且信封 code==200）。 */
        data object Renamed : RenameResult

        /** 无 threadId（从未发过消息的新会话）：跳过平台调用。 */
        data object NoThread : RenameResult

        /** 网络失败 / 服务端拒绝 / 鉴权失败（透明重登后仍 401）：调用方不回滚本地改名，仅记日志。 */
        data class Error(val message: String) : RenameResult
    }

    /** renameThread 单次调用的内部结果（401 单独分型以支持透明重登，与 deleteThread 同模式）。 */
    private sealed interface RenameCall {
        data object Done : RenameCall
        data object Unauthorized : RenameCall
        data class Failed(val message: String) : RenameCall
    }

    /**
     * uploadFile 的结果（S7-f 文件上传，POST {uploadUrl} multipart）。
     * 非幂等：服务端非成功即失败，调用方（VM）把列表项标 ERROR 由用户删除/重传。
     */
    sealed interface UploadResult {
        /** 服务端确认（HTTP 2xx 且信封 code==200），meta 解析自 data 段。 */
        data class Success(val meta: FileMeta) : UploadResult

        /** 网络失败 / 服务端拒绝 / 鉴权失败（透明重登后仍 401）。 */
        data class Error(val message: String) : UploadResult
    }

    /** uploadFile 单次调用的内部结果（401 单独分型以支持透明重登，与 delete/rename 同模式）。 */
    private sealed interface UploadCall {
        data class Done(val meta: FileMeta) : UploadCall
        data object Unauthorized : UploadCall
        data class Failed(val message: String) : UploadCall
    }

    /** S16 agent 摘要（/agent/list 条目；description 可空，name 缺失回退 id）。 */
    data class AgentBrief(
        val agentId: String,
        val agentName: String,
        val agentDescription: String?,
    )

    /** listAgents 的结果（S16 agent 选择）。 */
    sealed interface AgentListResult {
        data class Ok(val agents: List<AgentBrief>) : AgentListResult

        /** 网络失败 / 信封非 200 / 鉴权失败（透明重登后仍 401）。 */
        data class Error(val message: String) : AgentListResult
    }

    /** listAgents 单次调用的内部结果（401 单独分型，与 delete/rename/upload 同模式）。 */
    private sealed interface AgentListCall {
        data class Done(val agents: List<AgentBrief>) : AgentListCall
        data object Unauthorized : AgentListCall
        data class Failed(val message: String) : AgentListCall
    }

    /** thread 管理接口前缀 = streamUrl 去末段 /stream（知识包 §2.2）。 */
    private val threadBaseUrl: String = streamUrl.removeSuffix("/stream")

    private var reloginUsed = false
    private var lastAuthError: String? = null

    /** 消息 id 单调序号：与毫秒拼成 `u_{毫秒}_{seq}`，杜绝同毫秒撞 id（§2.2 要求全局唯一）。 */
    private val messageSeq = AtomicInteger(0)

    /**
     * 同步阻塞执行一轮对话（调用方负责切 IO 线程/协程）。
     * 流程：确保 token → 确保 threadId → 同消息 id 最多 [MAX_ATTEMPTS] 次 SSE 尝试。
     * 登录与 createThreadId 的 401 共享「每次 chat 透明重登 1 次」额度（spec 边界表）。
     *
     * @param messageId 外部指定 wire 消息 id（手动重发必须复用原 id，踩坑 #25：换 id 重发
     * 会在 thread 里追加重复消息，空回复率 ~20%→~50%）；null/空白时生成 `u_{毫秒}_{seq}`。
     * @param threadKey 会话路由 key（多会话：每会话一个 threadId，新会话首次发送时
     * createThreadId 并缓存 key→threadId 映射；切会话后发送走对应 thread）。默认 [DEFAULT_THREAD_KEY]。
     * @param threadName 新建 thread 时上报的 threadName（createThreadId 必填，踩坑 #29）；
     * null/空白时用默认名。已有 thread 的会话不触发创建，此参数被忽略。
     * @param fileRefs 已上传文件 (fileId, fileName) 对：content 文本前缀注入
     * "@file:{fileId}?{fileName}" 多条 "|" 连接 + "\n" + 用户文本（平台 web bundle
     * 静态取证契约，S7-f）；空列表（语音路径/纯文字）content 原样不动。
     */
    fun chat(
        userText: String,
        messageId: String? = null,
        threadKey: String = DEFAULT_THREAD_KEY,
        threadName: String? = null,
        fileRefs: List<Pair<String, String>> = emptyList(),
        onDelta: (String) -> Unit = {},
    ): ChatResult {
        reloginUsed = false
        var token = ensureToken() ?: return ChatResult.Error(lastAuthError ?: "登录失败")
        val threadId = when (val t = ensureThreadId(token, threadKey, threadName)) {
            is ThreadResult.Ok -> t.threadId
            is ThreadResult.Failed -> return ChatResult.Error(t.message)
            ThreadResult.Unauthorized -> {
                logger("createThreadId HTTP 401, token 视为过期，透明重登 1 次")
                token = reloginOnce() ?: return ChatResult.Error(lastAuthError ?: "token 过期透明重登失败")
                when (val t2 = ensureThreadId(token, threadKey, threadName)) {
                    is ThreadResult.Ok -> t2.threadId
                    is ThreadResult.Failed -> return ChatResult.Error(t2.message)
                    ThreadResult.Unauthorized ->
                        return ChatResult.Error("透明重登后 createThreadId 仍鉴权失败")
                }
            }
        }
        val msgId = messageId?.takeIf { it.isNotBlank() }
            ?: "u_${clock()}_${messageSeq.incrementAndGet()}"
        val content = FileMeta.buildContent(userText, fileRefs)

        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            when (val r = runOnce(token, threadId, msgId, attempt, content, onDelta)) {
                is AttemptResult.Success ->
                    return ChatResult.Success(r.text, r.firstTokenLatencyMs, attempt)
                AttemptResult.Empty ->
                    logger("empty run (attempt $attempt/$MAX_ATTEMPTS), retry with same message id")
                is AttemptResult.Unauthorized -> {
                    if (reloginUsed) {
                        return ChatResult.Error("透明重登后仍鉴权失败：${r.message}")
                    }
                    logger("SSE HTTP ${r.message}, token 视为过期，透明重登 1 次")
                    token = reloginOnce() ?: return ChatResult.Error(lastAuthError ?: "token 过期透明重登失败")
                    attempt-- // 401 重登不消耗空 run 重试次数
                }
                is AttemptResult.Failure -> return ChatResult.Error(r.message)
            }
        }
        return ChatResult.Error("平台繁忙：连续 $MAX_ATTEMPTS 次空 run，请稍后重试")
    }

    /** 缓存未过期直接用，否则登录取新 token。 */
    private fun ensureToken(): String? = tokenCache.cached() ?: login()

    /** 清缓存并重新登录；每次 chat 仅限 1 次额度（spec：透明重登 1 次），额度已用尽返回 null。 */
    private fun reloginOnce(): String? {
        if (reloginUsed) return null
        reloginUsed = true
        tokenCache.clear()
        return login()
    }

    /**
     * 登录：POST {loginUrl} body {"username","password"}，信封 code==200 取 36 字符 UUID token。
     * 凭据与 token 一律不进日志。
     */
    private fun login(): String? {
        val (username, password) = credentials()
        if (username.isBlank() || password.isBlank()) {
            lastAuthError = "账号或密码未配置（请到设置页填写并保存）"
            logger(lastAuthError!!)
            return null
        }
        logger("login POST $loginUrl")
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .toString()
            .toRequestBody(JSON)
        val req = Request.Builder().url(loginUrl).post(body).build()
        val respText = try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    lastAuthError = "登录 HTTP ${resp.code}: ${envelopeMsg(text)}"
                    logger(lastAuthError!!)
                    return null
                }
                text
            }
        } catch (e: IOException) {
            lastAuthError = "登录网络错误: ${e.message}"
            logger(lastAuthError!!)
            return null
        }
        val json = runCatching { JSONObject(respText) }.getOrNull()
        if (json == null) {
            lastAuthError = "登录响应非 JSON 信封"
            logger(lastAuthError!!)
            return null
        }
        if (json.optInt("code", -1) != 200) {
            lastAuthError = "登录失败: ${json.optString("msg", "未知错误")}"
            logger(lastAuthError!!)
            return null
        }
        val token = json.optString("token", "")
        if (!TokenPolicy.isValidToken(token)) {
            lastAuthError = "登录返回 token 格式非法（非 36 字符 UUID）"
            logger(lastAuthError!!)
            return null
        }
        tokenCache.saveFresh(token)
        logger("login ok, token cached (ttl 25min)")
        return token
    }

    /** 该会话无缓存 thread 时调 createThreadId（body 必须同时 threadName+agentId，踩坑 #29）并按键持久化；401 分型供调用方透明重登。 */
    private fun ensureThreadId(token: String, threadKey: String, threadName: String?): ThreadResult {
        threadStore.load(threadKey)?.let { return ThreadResult.Ok(it) }
        val url = "$threadBaseUrl/createThreadId"
        logger("createThreadId POST $url")
        val body = JSONObject()
            .put("threadName", threadName?.takeIf { it.isNotBlank() } ?: THREAD_NAME)
            .put("agentId", agentIdProvider())
            .toString()
            .toRequestBody(JSON)
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("agentId", agentIdProvider())
            .post(body)
            .build()
        val respText = try {
            http.newCall(req).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    return ThreadResult.Unauthorized
                }
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    lastAuthError = "createThreadId HTTP ${resp.code}: ${envelopeMsg(text)}"
                    logger(lastAuthError!!)
                    return ThreadResult.Failed(lastAuthError!!)
                }
                text
            }
        } catch (e: IOException) {
            lastAuthError = "createThreadId 网络错误: ${e.message}"
            logger(lastAuthError!!)
            return ThreadResult.Failed(lastAuthError!!)
        }
        val json = runCatching { JSONObject(respText) }.getOrNull()
        if (json == null || json.optInt("code", -1) != 200) {
            lastAuthError = "createThreadId 失败: ${json?.optString("msg") ?: "响应非 JSON 信封"}"
            logger(lastAuthError!!)
            return ThreadResult.Failed(lastAuthError!!)
        }
        val threadId = json.optString("data", "")
        if (threadId.isBlank()) {
            lastAuthError = "createThreadId 返回空 threadId"
            logger(lastAuthError!!)
            return ThreadResult.Failed(lastAuthError!!)
        }
        threadStore.save(threadKey, threadId)
        logger("thread created and persisted, threadId=$threadId")
        return ThreadResult.Ok(threadId)
    }

    /**
     * 删除平台 thread（知识包 §2.2：POST {threadBaseUrl}/deleteThread，body {threadId}，
     * Bearer + agentId 头，信封 code==200，data null）。401 透明重登 1 次重试（与
     * createThreadId 同模式，重登额度独立、不占用 chat 的额度）。
     */
    fun deleteThread(threadId: String): DeleteResult {
        if (threadId.isBlank()) return DeleteResult.NoThread
        var token = ensureToken() ?: return DeleteResult.Error(lastAuthError ?: "登录失败")
        var reloginRetried = false
        while (true) {
            when (val r = deleteThreadOnce(token, threadId)) {
                DeleteCall.Done -> return DeleteResult.Deleted
                DeleteCall.Gone -> return DeleteResult.AlreadyGone
                is DeleteCall.Failed -> return DeleteResult.Error(r.message)
                DeleteCall.Unauthorized -> {
                    if (reloginRetried) return DeleteResult.Error("透明重登后 deleteThread 仍鉴权失败")
                    reloginRetried = true
                    logger("deleteThread HTTP 401, token 视为过期，透明重登 1 次")
                    tokenCache.clear()
                    token = login() ?: return DeleteResult.Error(lastAuthError ?: "token 过期透明重登失败")
                }
            }
        }
    }

    /**
     * 按会话 key 删除平台 thread：无 threadId 直接跳过（零网络请求）；
     * 放弃语义（用户拍板裁决）——无论平台删除成败都清本地映射：会话已删，映射留着是
     * 死状态永不复用；失败产生的平台孤儿 thread 为接受的权衡（失败已记日志，可平台侧清理），
     * 本地删除不回滚、不重试。
     */
    fun deleteThreadForSession(threadKey: String): DeleteResult {
        val threadId = threadStore.load(threadKey)
        if (threadId.isNullOrBlank()) return DeleteResult.NoThread
        val result = deleteThread(threadId)
        threadStore.remove(threadKey)
        return result
    }

    /**
     * S16：拉取平台 agent 列表（POST {listUrl} 空 body + Bearer，信封 code==200 data.list[]）。
     * 供设置页选择器；401 透明重登 1 次（与 deleteThread 同模式，重登额度独立）。
     * 交互语义（用户 08-25 拍板）：agentId 仅请求头路由（提示词/MCP 不同），
     * thread 连续性与本地历史均不动。
     */
    fun listAgents(): AgentListResult {
        var token = ensureToken() ?: return AgentListResult.Error(lastAuthError ?: "登录失败")
        var reloginRetried = false
        while (true) {
            when (val r = listAgentsOnce(token)) {
                is AgentListCall.Done -> return AgentListResult.Ok(r.agents)
                is AgentListCall.Failed -> return AgentListResult.Error(r.message)
                AgentListCall.Unauthorized -> {
                    if (reloginRetried) return AgentListResult.Error("透明重登后 agent/list 仍鉴权失败")
                    reloginRetried = true
                    logger("agent/list HTTP 401, token 视为过期，透明重登 1 次")
                    tokenCache.clear()
                    token = login() ?: return AgentListResult.Error(lastAuthError ?: "token 过期透明重登失败")
                }
            }
        }
    }

    /** 单次 agent/list 调用；name 缺失回退 id，条目缺 agentId 则跳过（脏数据容错）。 */
    private fun listAgentsOnce(token: String): AgentListCall {
        logger("agent/list POST $listUrl")
        val req = Request.Builder()
            .url(listUrl)
            .header("Authorization", "Bearer $token")
            .post("{}".toRequestBody(JSON))
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.code == 401 || resp.code == 403 -> AgentListCall.Unauthorized
                    !resp.isSuccessful -> AgentListCall.Failed("agent/list HTTP ${resp.code}: ${envelopeMsg(text)}")
                    else -> {
                        val env = runCatching { JSONObject(text) }.getOrElse {
                            return AgentListCall.Failed("agent/list 响应非 JSON: ${text.take(80)}")
                        }
                        if (env.optInt("code") != 200) {
                            return AgentListCall.Failed("agent/list 信封非 200: ${envelopeMsg(text)}")
                        }
                        val list = env.optJSONObject("data")?.optJSONArray("list")
                            ?: return AgentListCall.Failed("agent/list 信封缺 data.list")
                        val agents = (0 until list.length()).mapNotNull { i ->
                            val o = list.optJSONObject(i) ?: return@mapNotNull null
                            val id = o.optString("agentId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            AgentBrief(
                                agentId = id,
                                agentName = o.optString("agentName").ifBlank { id },
                                agentDescription = o.optString("agentDescription").takeIf { it.isNotBlank() },
                            )
                        }
                        AgentListCall.Done(agents)
                    }
                }
            }
        } catch (e: Exception) {
            AgentListCall.Failed("agent/list 网络失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 单次 deleteThread 调用；幂等语义：服务端非成功响应（重复删/不存在/信封非 200）→ Gone。 */
    private fun deleteThreadOnce(token: String, threadId: String): DeleteCall {
        val url = "$threadBaseUrl/deleteThread"
        logger("deleteThread POST $url threadId=$threadId")
        val body = JSONObject().put("threadId", threadId).toString().toRequestBody(JSON)
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("agentId", agentIdProvider())
            .post(body)
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 -> DeleteCall.Unauthorized
                    !resp.isSuccessful -> {
                        // 幂等：HTTP 非 2xx（多端并发重复删/thread 已不存在）视为已达终态
                        logger("deleteThread HTTP ${resp.code}: ${envelopeMsg(resp.body?.string().orEmpty())}，幂等视为已删")
                        DeleteCall.Gone
                    }
                    else -> {
                        val text = resp.body?.string().orEmpty()
                        val code = runCatching { JSONObject(text).optInt("code", -1) }.getOrDefault(-1)
                        if (code == 200) {
                            logger("deleteThread ok, threadId=$threadId")
                            DeleteCall.Done
                        } else {
                            logger("deleteThread 信封非 200: ${envelopeMsg(text)}，幂等视为已删")
                            DeleteCall.Gone
                        }
                    }
                }
            }
        } catch (e: IOException) {
            DeleteCall.Failed("deleteThread 网络错误: ${e.message}")
        }
    }

    /**
     * 重命名平台 thread（POST {threadBaseUrl}/renameThread，body {threadId, threadName}，
     * Bearer + agentId 头，信封 code==200 data null——与 deleteThread 同款请求形态，
     * 端点路径见票据实测记录）。401 透明重登 1 次重试（与 deleteThread 同模式，
     * 重登额度独立、不占用 chat 的额度）。
     */
    fun renameThread(threadId: String, newName: String): RenameResult {
        if (threadId.isBlank() || newName.isBlank()) return RenameResult.NoThread
        var token = ensureToken() ?: return RenameResult.Error(lastAuthError ?: "登录失败")
        var reloginRetried = false
        while (true) {
            when (val r = renameThreadOnce(token, threadId, newName)) {
                RenameCall.Done -> return RenameResult.Renamed
                is RenameCall.Failed -> return RenameResult.Error(r.message)
                RenameCall.Unauthorized -> {
                    if (reloginRetried) return RenameResult.Error("透明重登后 renameThread 仍鉴权失败")
                    reloginRetried = true
                    logger("renameThread HTTP 401, token 视为过期，透明重登 1 次")
                    tokenCache.clear()
                    token = login() ?: return RenameResult.Error(lastAuthError ?: "token 过期透明重登失败")
                }
            }
        }
    }

    /**
     * 按会话 key 重命名平台 thread（对称 deleteThreadForSession）：无 threadId
     * （从未发过消息的新会话）直接跳过，零网络请求；fire-and-forget 语义——
     * 本地改名已完成，平台失败只进日志，不回滚、不重试、不清本地映射。
     */
    fun renameThreadForSession(threadKey: String, newName: String): RenameResult {
        val threadId = threadStore.load(threadKey)
        if (threadId.isNullOrBlank()) return RenameResult.NoThread
        return renameThread(threadId, newName)
    }

    /** 单次 renameThread 调用；非幂等：服务端非成功响应（HTTP 非 2xx / 信封非 200）→ Failed。 */
    private fun renameThreadOnce(token: String, threadId: String, newName: String): RenameCall {
        val url = "$threadBaseUrl/renameThread"
        logger("renameThread POST $url threadId=$threadId")
        val body = JSONObject()
            .put("threadId", threadId)
            .put("threadName", newName)
            .toString()
            .toRequestBody(JSON)
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("agentId", agentIdProvider())
            .post(body)
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 -> RenameCall.Unauthorized
                    !resp.isSuccessful -> {
                        val msg = "renameThread HTTP ${resp.code}: ${envelopeMsg(resp.body?.string().orEmpty())}"
                        logger(msg)
                        RenameCall.Failed(msg)
                    }
                    else -> {
                        val text = resp.body?.string().orEmpty()
                        val code = runCatching { JSONObject(text).optInt("code", -1) }.getOrDefault(-1)
                        if (code == 200) {
                            logger("renameThread ok, threadId=$threadId")
                            RenameCall.Done
                        } else {
                            val msg = "renameThread 信封非 200: ${envelopeMsg(text)}"
                            logger(msg)
                            RenameCall.Failed(msg)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            RenameCall.Failed("renameThread 网络错误: ${e.message}")
        }
    }

    /**
     * 上传文件（S7-f，契约 = CONSTRUCTION 已关闭取证）：
     * POST {uploadUrl} multipart 字段 file + fileClass（值=agent----{agentId}----{threadId}），
     * Bearer + agentId 头复用 AG-UI；信封 code==200 取 data 段解析 [FileMeta]。
     * 上传前按会话确保 threadId（新会话无 thread 时先 createThreadId——fileClass 绑定该
     * thread，与随后首条消息同 thread 天然一致）。401 透明重登 1 次重试（与 delete/rename
     * 同模式，重登额度独立、不占用 chat 的额度）。
     */
    fun uploadFile(
        fileName: String,
        bytes: ByteArray,
        threadKey: String = DEFAULT_THREAD_KEY,
    ): UploadResult {
        if (fileName.isBlank() || bytes.isEmpty()) return UploadResult.Error("空文件不上传")
        var token = ensureToken() ?: return UploadResult.Error(lastAuthError ?: "登录失败")
        var reloginRetried = false
        while (true) {
            val threadId = when (val t = ensureThreadId(token, threadKey, null)) {
                is ThreadResult.Ok -> t.threadId
                is ThreadResult.Failed -> return UploadResult.Error(t.message)
                ThreadResult.Unauthorized -> {
                    if (reloginRetried) return UploadResult.Error("透明重登后 createThreadId 仍鉴权失败")
                    reloginRetried = true
                    logger("upload createThreadId HTTP 401, token 视为过期，透明重登 1 次")
                    tokenCache.clear()
                    token = login() ?: return UploadResult.Error(lastAuthError ?: "token 过期透明重登失败")
                    continue
                }
            }
            when (val r = uploadOnce(token, threadId, fileName, bytes)) {
                is UploadCall.Done -> return UploadResult.Success(r.meta)
                is UploadCall.Failed -> return UploadResult.Error(r.message)
                UploadCall.Unauthorized -> {
                    if (reloginRetried) return UploadResult.Error("透明重登后 uploadFile 仍鉴权失败")
                    reloginRetried = true
                    logger("uploadFile HTTP 401, token 视为过期，透明重登 1 次")
                    tokenCache.clear()
                    token = login() ?: return UploadResult.Error(lastAuthError ?: "token 过期透明重登失败")
                }
            }
        }
    }

    /** 单次 upload 调用；非幂等：服务端非成功响应（HTTP 非 2xx / 信封非 200 / 缺 fileId）→ Failed。 */
    private fun uploadOnce(token: String, threadId: String, fileName: String, bytes: ByteArray): UploadCall {
        logger("uploadFile POST $uploadUrl ($fileName, ${bytes.size} bytes)")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("fileClass", "agent----${agentIdProvider()}----$threadId")
            .addFormDataPart("file", fileName, bytes.toRequestBody(OCTET_STREAM))
            .build()
        val req = Request.Builder()
            .url(uploadUrl)
            .header("Authorization", "Bearer $token")
            .header("agentId", agentIdProvider())
            .post(body)
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 -> UploadCall.Unauthorized
                    !resp.isSuccessful -> {
                        val msg = "uploadFile HTTP ${resp.code}: ${envelopeMsg(resp.body?.string().orEmpty())}"
                        logger(msg)
                        UploadCall.Failed(msg)
                    }
                    else -> {
                        val text = resp.body?.string().orEmpty()
                        val json = runCatching { JSONObject(text) }.getOrNull()
                        if (json == null || json.optInt("code", -1) != 200) {
                            val msg = "uploadFile 失败: ${json?.optString("msg") ?: "响应非 JSON 信封"}"
                            logger(msg)
                            UploadCall.Failed(msg)
                        } else {
                            val meta = json.optJSONObject("data")?.let { FileMeta.fromJson(it) }
                            if (meta == null || meta.fileId.isBlank()) {
                                val msg = "uploadFile 返回缺 fileId"
                                logger(msg)
                                UploadCall.Failed(msg)
                            } else {
                                logger("uploadFile ok, fileId=${meta.fileId}")
                                UploadCall.Done(meta)
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            UploadCall.Failed("uploadFile 网络错误: ${e.message}")
        }
    }

    /** 单次 SSE 尝试：逐行喂 AguiSseConsumer，首 token 延迟按 TEXT_MESSAGE_START 计时（踩坑 #24）。 */
    private fun runOnce(
        token: String,
        threadId: String,
        messageId: String,
        attempt: Int,
        content: String,
        onDelta: (String) -> Unit,
    ): AttemptResult {
        val runId = "deskpet_run_${clock()}_$attempt"
        val payload = JSONObject()
            .put("threadId", threadId)
            .put("runId", runId)
            .put("state", JSONObject())
            .put("tools", JSONArray())
            .put("context", JSONArray())
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("id", messageId)
                        .put("role", "user")
                        .put("content", content),
                ),
            )
            .put(
                "forwardedProps",
                JSONObject()
                    .put("protocolVersion", "0.1.0")
                    .put("source", "desk_pet"),
            )
        val req = Request.Builder()
            .url(streamUrl)
            .header("Accept", "text/event-stream")
            .header("agentId", agentIdProvider())
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        logger("run POST $streamUrl runId=$runId msgId=$messageId threadId=$threadId attempt=$attempt")

        val startMs = clock()
        var firstTokenMs: Long? = null
        val consumer = AguiSseConsumer(onDelta = onDelta)
        try {
            http.newCall(req).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    return AttemptResult.Unauthorized("${resp.code}")
                }
                if (!resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty()
                    return AttemptResult.Failure("AG-UI HTTP ${resp.code}: ${envelopeMsg(text)}")
                }
                val source = resp.body!!.source()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (firstTokenMs == null && line.contains("TEXT_MESSAGE_START")) {
                        firstTokenMs = clock() - startMs
                        logger("first token (TEXT_MESSAGE_START) at ${firstTokenMs}ms runId=$runId")
                    }
                    consumer.feedLine(line)
                }
            }
        } catch (e: IOException) {
            return AttemptResult.Failure("AG-UI 网络错误: ${e.message}")
        }
        return when (val r = consumer.finish()) {
            is AguiSseConsumer.RunResult.Success -> {
                logger("RUN_FINISHED ok, reply ${r.text.length} chars, runId=$runId")
                AttemptResult.Success(r.text, firstTokenMs)
            }
            AguiSseConsumer.RunResult.EmptyRun -> AttemptResult.Empty
            is AguiSseConsumer.RunResult.Error ->
                AttemptResult.Failure("SSE 断流: ${r.reason}")
        }
    }

    /** 从错误响应体提取平台信封 msg（code=500 的 msg 常带真实原因，知识包 §6.3）。 */
    private fun envelopeMsg(body: String): String =
        runCatching { JSONObject(body).optString("msg") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: body.take(120)

    companion object {
        /** 默认 agentId（知识包 §6.2 PLATFORM_AGENT_ID=chatAgent，换 agent 只改 header，踩坑 #29） */
        const val DEFAULT_AGENT_ID = "chatAgent"

        /** 默认会话路由 key（未显式指定 threadKey 的调用方，如语音路径/设置自检，共享此 thread） */
        const val DEFAULT_THREAD_KEY = "default"

        /** 空 run 同 id 重试总次数（spec：空 run ×3 报「平台繁忙」） */
        const val MAX_ATTEMPTS = 3

        private const val THREAD_NAME = "小朵手机助手"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}
