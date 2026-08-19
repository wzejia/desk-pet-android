package com.xiaoduo.deskpet.chat

import android.content.Context
import com.xiaoduo.deskpet.util.PetLogger
import java.io.File
import java.util.UUID

/**
 * 聊天持久化（多会话扩 scope）：files/chat_sessions/ 下 index.json（会话索引 + 当前会话 id）
 * + 每会话一个 {id}.json 消息文件（编解码复用 [ChatHistoryCodec]，杀进程状态迁移随之保留）。
 *
 * 选型理由：沿单文件 JSON 路线演进——按会话拆文件，每次发送只写当前会话一个文件，
 * 索引只记元数据；数据量小（几十~几百条/会话），Room 的 kapt/ksp 与运行时成本仍远超收益。
 *
 * 旧版单会话 files/chat_history.json 首次 loadIndex 时无损迁移为会话 #1
 * （消息逐条保留，标题从首条 user 消息生成），迁移成功后删除旧文件；
 * 旧文件损坏/为空则不建会话直接移除（避免每次启动重试）。
 *
 * IO 薄壳——标题派生 / 索引编解码 / 搜索 / 删除回落全在纯逻辑（JVM 单测覆盖）；
 * 构造注入 File 根目录，JVM 单测可用临时目录全量覆盖迁移与文件行为。
 */
class ChatStore(
    private val filesDir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val logger: (String) -> Unit = {},
) {

    constructor(context: Context) : this(
        filesDir = context.filesDir,
        logger = { msg -> PetLogger.i(TAG, msg) },
    )

    private val sessionsDir = File(filesDir, DIR_NAME)
    private val indexFile get() = File(sessionsDir, INDEX_NAME)
    private val legacyFile = File(filesDir, LEGACY_FILE_NAME)

    /** 读索引（必要时先迁移旧版单会话文件）。 */
    @Synchronized
    fun loadIndex(): SessionIndex {
        migrateLegacyIfNeeded()
        if (!indexFile.exists()) return SessionIndex(null, emptyList())
        val text = runCatching { indexFile.readText() }
            .onFailure { logger("read session index failed: ${it.message}") }
            .getOrNull() ?: return SessionIndex(null, emptyList())
        return SessionIndexCodec.decode(text)
    }

    @Synchronized
    fun loadMessages(sessionId: String): List<ChatMessage> {
        val file = messagesFile(sessionId)
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }
            .onFailure { logger("read session messages failed: ${it.message}") }
            .getOrNull() ?: return emptyList()
        return ChatHistoryCodec.decode(text)
    }

    /** 写会话消息并同步索引（标题随首条 user 消息派生，updatedAt 刷新；手动命名会话跳过派生）。 */
    @Synchronized
    fun saveMessages(sessionId: String, messages: List<ChatMessage>) {
        val index = loadIndex()
        val session = index.sessions.firstOrNull { it.id == sessionId } ?: return
        runCatching { messagesFile(sessionId).writeText(ChatHistoryCodec.encode(messages)) }
            .onFailure { logger("save session messages failed: ${it.message}") }
            .getOrNull() ?: return
        // titleManual=true（用户重命名过，ADR 0002 路线 C）→ 保留手动名，不被新消息覆盖
        val updated = if (session.titleManual) {
            session.copy(updatedAt = now())
        } else {
            session.copy(title = ChatSession.deriveTitle(messages), updatedAt = now())
        }
        saveIndex(index.copy(sessions = index.sessions.map { if (it.id == sessionId) updated else it }))
    }

    /**
     * 重命名会话（ADR 0002 路线 C，本地权威）：改 title + 置 titleManual（后续 saveMessages
     * 不再 deriveTitle 覆盖）+ 存索引。updatedAt 不动——改名不改会话活跃度，排序不应跳动。
     * 会话不存在 / 标题空白则忽略。返回改名后会话，未命中返回 null。
     */
    @Synchronized
    fun renameSession(sessionId: String, title: String): ChatSession? {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return null
        val index = loadIndex()
        val session = index.sessions.firstOrNull { it.id == sessionId } ?: return null
        val updated = session.copy(title = trimmed, titleManual = true)
        saveIndex(index.copy(sessions = index.sessions.map { if (it.id == sessionId) updated else it }))
        return updated
    }

    /** 新建空会话并置为当前。 */
    @Synchronized
    fun createSession(): ChatSession {
        val ts = now()
        val session = ChatSession(newId(), ChatSession.FALLBACK_TITLE, ts, ts)
        val index = loadIndex()
        saveIndex(index.copy(currentId = session.id, sessions = index.sessions + session))
        return session
    }

    @Synchronized
    fun setCurrent(sessionId: String) {
        val index = loadIndex()
        if (index.sessions.none { it.id == sessionId }) return
        saveIndex(index.copy(currentId = sessionId))
    }

    /**
     * 删除会话（消息文件 + 索引项），不影响其他会话；
     * 当前会话被删按 [SessionRules.currentAfterDelete] 回落（最近更新的其余会话，无剩余 → null）。
     * 返回删后索引。
     */
    @Synchronized
    fun deleteSession(sessionId: String): SessionIndex {
        val index = loadIndex()
        if (index.sessions.none { it.id == sessionId }) return index
        val file = messagesFile(sessionId)
        if (file.exists() && !file.delete()) logger("delete session messages failed")
        val remaining = index.sessions.filterNot { it.id == sessionId }
        val next = SessionRules.currentAfterDelete(index.sessions, sessionId, index.currentId)
        val out = SessionIndex(next, remaining)
        saveIndex(out)
        return out
    }

    /** 全文搜索（拍板②）：标题 + 各会话全部消息文本，按 updatedAt 倒序返回。 */
    @Synchronized
    fun searchSessions(query: String): List<SessionSummary> {
        val index = loadIndex()
        val pairs = index.sessions
            .sortedByDescending { it.updatedAt }
            .map { it to loadMessages(it.id) }
        return SessionSearch.search(pairs, query)
    }

    /** 旧版单会话文件 → 会话 #1（无损：消息逐条保留；标题从首条 user 消息生成；ts 取自消息）。 */
    private fun migrateLegacyIfNeeded() {
        if (!legacyFile.exists()) return
        val messages = runCatching { ChatHistoryCodec.decode(legacyFile.readText()) }
            .onFailure { logger("read legacy history failed: ${it.message}") }
            .getOrNull()
        if (messages.isNullOrEmpty()) {
            if (!legacyFile.delete()) logger("delete empty legacy history failed")
            return
        }
        val session = ChatSession(
            id = newId(),
            title = ChatSession.deriveTitle(messages),
            createdAt = messages.first().ts.takeIf { it > 0 } ?: now(),
            updatedAt = messages.last().ts.takeIf { it > 0 } ?: now(),
        )
        runCatching { messagesFile(session.id).writeText(ChatHistoryCodec.encode(messages)) }
            .onFailure { logger("migrate legacy history failed: ${it.message}") }
            .getOrNull() ?: return
        val index = if (indexFile.exists()) {
            SessionIndexCodec.decode(runCatching { indexFile.readText() }.getOrNull() ?: "")
        } else {
            SessionIndex(null, emptyList())
        }
        saveIndex(index.copy(currentId = index.currentId ?: session.id, sessions = index.sessions + session))
        if (!legacyFile.delete()) logger("delete legacy history after migration failed")
        logger("legacy history migrated to session, ${messages.size} messages")
    }

    private fun messagesFile(sessionId: String): File {
        if (!sessionsDir.exists()) sessionsDir.mkdirs()
        return File(sessionsDir, "$sessionId.json")
    }

    private fun saveIndex(index: SessionIndex) {
        if (!sessionsDir.exists()) sessionsDir.mkdirs()
        runCatching { indexFile.writeText(SessionIndexCodec.encode(index)) }
            .onFailure { logger("save session index failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "ChatStore"
        const val DIR_NAME = "chat_sessions"
        const val INDEX_NAME = "index.json"

        /** 旧版单会话文件（多会话升级后仅作迁移来源）。 */
        const val LEGACY_FILE_NAME = "chat_history.json"
    }
}
