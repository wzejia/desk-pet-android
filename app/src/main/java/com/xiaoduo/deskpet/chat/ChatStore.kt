package com.xiaoduo.deskpet.chat

import android.content.Context
import com.xiaoduo.deskpet.util.PetLogger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 * DATA-1 数据丢失链防线（2026-10-03 审计）：
 * - 读失败/损坏不再与「没有」混淆：先备份 .bak 保底，备份成功才移除原件重建；
 *   备份失败置 degraded——拒写保护原件（坏数据可人工捞回），createSession 失败向上抛；
 * - 写入一律临时文件 + 原子 rename（torn write 不再直接毁原文件）；
 * - deleteSession 先持久化索引、成功后才删消息文件（防两步间崩溃留幽灵会话/丢数据）；
 * - 索引持久化失败的迁移中止并保留旧文件（下次启动重试），改名失败返回 null 不谎报成功。
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

    /** DATA-1：索引降级标志（读失败且备份失败置位；置位期间 saveIndex 拒写保护原件）。 */
    @Volatile
    private var indexDegraded = false

    /** DATA-1：读失败且备份失败的会话（拒写保护原件；进程级，重启后重试）。 */
    private val unreadableSessions = HashSet<String>()

    /** 读索引（必要时先迁移旧版单会话文件）。 */
    fun loadIndex(): SessionIndex = synchronized(LOCK) {
        migrateLegacyIfNeeded()
        if (!indexFile.exists()) return SessionIndex(null, emptyList())
        val text = runCatching { indexFile.readText() }
            .onFailure { logger("read session index failed: ${it.message}") }
            .getOrNull()
        if (text == null || !parsesAsJsonObject(text)) {
            // DATA-1：读失败/损坏不可与「没有」混淆——处置原件（备份/降级）再返回空
            recoverCorrupt(indexFile, "session index") { indexDegraded = true }
            return SessionIndex(null, emptyList())
        }
        SessionIndexCodec.decode(text)
    }

    fun loadMessages(sessionId: String): List<ChatMessage> = synchronized(LOCK) {
        val file = messagesFile(sessionId)
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }
            .onFailure { logger("read session messages failed: ${it.message}") }
            .getOrNull()
        if (text == null || !parsesAsJsonArray(text)) {
            // DATA-1：备份保底；备份失败记账拒写，空列表不得覆盖坏原件里的好数据
            if (backupCorrupt(file)) {
                if (!file.delete()) logger("remove corrupt messages file failed: $sessionId")
                unreadableSessions.remove(sessionId)
            } else {
                unreadableSessions.add(sessionId)
                logger("DEGRADED: messages file kept unreadable, saves refused: $sessionId")
            }
            return emptyList()
        }
        ChatHistoryCodec.decode(text)
    }

    /** 写会话消息并同步索引（标题随首条 user 消息派生，updatedAt 刷新；手动命名会话跳过派生）。 */
    fun saveMessages(sessionId: String, messages: List<ChatMessage>) = synchronized(LOCK) {
        if (sessionId in unreadableSessions) {
            logger("save session messages refused (file unreadable, degraded): $sessionId")
            return
        }
        val index = loadIndex()
        val session = index.sessions.firstOrNull { it.id == sessionId } ?: return
        if (!atomicWrite(messagesFile(sessionId), ChatHistoryCodec.encode(messages))) return
        // titleManual=true（用户重命名过，ADR 0002 路线 C）→ 保留手动名，不被新消息覆盖
        val updated = if (session.titleManual) {
            session.copy(updatedAt = now())
        } else {
            session.copy(title = ChatSession.deriveTitle(messages), updatedAt = now())
        }
        if (!saveIndex(index.copy(sessions = index.sessions.map { if (it.id == sessionId) updated else it }))) {
            logger("save session index failed (messages file already durable): $sessionId")
        }
    }

    /**
     * 重命名会话（ADR 0002 路线 C，本地权威）：改 title + 置 titleManual（后续 saveMessages
     * 不再 deriveTitle 覆盖）+ 存索引。updatedAt 不动——改名不改会话活跃度，排序不应跳动。
     * 会话不存在 / 标题空白 / 索引持久化失败（DATA-1：未落盘不谎报成功）返回 null。
     */
    fun renameSession(sessionId: String, title: String): ChatSession? = synchronized(LOCK) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return null
        val index = loadIndex()
        val session = index.sessions.firstOrNull { it.id == sessionId } ?: return null
        val updated = session.copy(title = trimmed, titleManual = true)
        if (!saveIndex(index.copy(sessions = index.sessions.map { if (it.id == sessionId) updated else it }))) {
            return null
        }
        return updated
    }

    /** 新建空会话并置为当前。DATA-1：索引持久化失败向上抛（112：静默返回会让 saveMessages 全程 no-op）。 */
    fun createSession(): ChatSession = synchronized(LOCK) {
        val ts = now()
        val session = ChatSession(newId(), ChatSession.FALLBACK_TITLE, ts, ts)
        val index = loadIndex()
        if (!saveIndex(index.copy(currentId = session.id, sessions = index.sessions + session))) {
            error("createSession: session index persist failed (session ${session.id} not durable)")
        }
        return session
    }

    fun setCurrent(sessionId: String) = synchronized(LOCK) {
        val index = loadIndex()
        if (index.sessions.none { it.id == sessionId }) return
        if (!saveIndex(index.copy(currentId = sessionId))) logger("set current session persist failed: $sessionId")
    }

    /**
     * 删除会话（消息文件 + 索引项），不影响其他会话；
     * 当前会话被删按 [SessionRules.currentAfterDelete] 回落（最近更新的其余会话，无剩余 → null）。
     * DATA-1(113)：先持久化索引、成功后才删消息文件——索引持久化失败即中止（文件保留）。
     * 返回删后索引。
     */
    fun deleteSession(sessionId: String): SessionIndex = synchronized(LOCK) {
        val index = loadIndex()
        if (index.sessions.none { it.id == sessionId }) return index
        val remaining = index.sessions.filterNot { it.id == sessionId }
        val next = SessionRules.currentAfterDelete(index.sessions, sessionId, index.currentId)
        val out = SessionIndex(next, remaining)
        if (!saveIndex(out)) {
            logger("delete session aborted: index persist failed, messages file kept: $sessionId")
            return index
        }
        val file = messagesFile(sessionId)
        if (file.exists() && !file.delete()) logger("delete session messages failed")
        return out
    }

    /** 全文搜索（拍板②）：标题 + 各会话全部消息文本，按 updatedAt 倒序返回。 */
    fun searchSessions(query: String): List<SessionSummary> = synchronized(LOCK) {
        val index = loadIndex()
        val pairs = index.sessions
            .sortedByDescending { it.updatedAt }
            .map { it to loadMessages(it.id) }
        SessionSearch.search(pairs, query)
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
        if (!atomicWrite(messagesFile(session.id), ChatHistoryCodec.encode(messages))) {
            logger("migrate legacy history failed: messages write failed, legacy kept")
            return
        }
        val index = if (indexFile.exists()) {
            val text = runCatching { indexFile.readText() }.getOrNull()
            if (text != null && parsesAsJsonObject(text)) SessionIndexCodec.decode(text)
            else {
                // DATA-1：存量索引读不了 → 中止迁移保旧文件（绝不以迁移覆盖不可读状态）
                recoverCorrupt(indexFile, "session index") { indexDegraded = true }
                logger("migrate legacy aborted: existing index unreadable, legacy kept for retry")
                return
            }
        } else {
            SessionIndex(null, emptyList())
        }
        if (!saveIndex(index.copy(currentId = index.currentId ?: session.id, sessions = index.sessions + session))) {
            logger("migrate legacy aborted: index persist failed, legacy kept for retry")
            return
        }
        if (!legacyFile.delete()) logger("delete legacy history after migration failed")
        logger("legacy history migrated to session, ${messages.size} messages")
    }

    private fun messagesFile(sessionId: String): File {
        if (!sessionsDir.exists()) sessionsDir.mkdirs()
        return File(sessionsDir, "$sessionId.json")
    }

    /** 存索引（degraded 拒写；返回成败）。调用方须持 [LOCK]。 */
    private fun saveIndex(index: SessionIndex): Boolean {
        if (indexDegraded) {
            logger("save session index refused (degraded: corrupt index kept for forensics)")
            return false
        }
        if (!sessionsDir.exists()) sessionsDir.mkdirs()
        return atomicWrite(indexFile, SessionIndexCodec.encode(index))
    }

    /** DATA-1：临时文件 + 原子 rename——torn write 只毁 .tmp，不毁原件（111）。 */
    private fun atomicWrite(target: File, text: String): Boolean {
        val tmp = File(target.parentFile, target.name + ".tmp")
        return try {
            tmp.writeText(text)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (t: Throwable) {
            logger("atomic write failed: ${target.name}: ${t.message}")
            runCatching { tmp.delete() }
            false
        }
    }

    /** 语义校验（区别于编解码兜底）：文本必须是可解析 JSON 才交给 codec，否则按损坏处置。 */
    private fun parsesAsJsonObject(text: String): Boolean =
        runCatching { org.json.JSONObject(text) }.isSuccess

    private fun parsesAsJsonArray(text: String): Boolean =
        runCatching { org.json.JSONArray(text) }.isSuccess

    /**
     * DATA-1：损坏文件处置——备份成功 → 移除原件（数据在 .bak 保底，写入链恢复可用）；
     * 备份失败 → 置降级（onDegraded），原件原样保留，写入一律拒绝。
     */
    private fun recoverCorrupt(file: File, kind: String, onDegraded: () -> Unit) {
        if (backupCorrupt(file)) {
            if (!file.delete()) logger("remove corrupt $kind failed (kept alongside .bak)")
        } else {
            onDegraded()
            logger("DEGRADED: corrupt $kind kept, writes refused until cleared: ${file.name}")
        }
    }

    /** 损坏文件备份（同名 + .bak，覆盖旧备份）；返回是否成功。 */
    private fun backupCorrupt(file: File): Boolean = try {
        Files.copy(
            file.toPath(),
            File(file.parentFile, file.name + ".bak").toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
        true
    } catch (t: Throwable) {
        logger("backup corrupt file failed: ${file.name}: ${t.message}")
        false
    }

    companion object {
        private const val TAG = "ChatStore"
        const val DIR_NAME = "chat_sessions"
        const val INDEX_NAME = "index.json"

        /** 旧版单会话文件（多会话升级后仅作迁移来源）。 */
        const val LEGACY_FILE_NAME = "chat_history.json"

        /**
         * DATA-1(115)：进程级锁——@Synchronized 实例锁挡不住第二实例交错 RMW
         * （last-writer-wins 整体覆盖），类级监视器使多实例串行。
         */
        private val LOCK = Any()
    }
}
