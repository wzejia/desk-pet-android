package com.xiaoduo.deskpet.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ChatStore 多会话持久化 JVM 测试（临时目录注入，覆盖文件级行为）：
 * 旧版单会话文件无损迁移 / 会话增删改 / 当前会话持久化 / 删除回落 / 搜索 IO 包装。
 */
class ChatStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1000L
    private var idSeq = 0
    private lateinit var store: ChatStore

    @Before
    fun setUp() {
        clock = 1000L
        idSeq = 0
        store = ChatStore(tmp.root, now = { clock }, newId = { "s${++idSeq}" })
    }

    private fun legacyFile() = File(tmp.root, ChatStore.LEGACY_FILE_NAME)

    private fun msg(id: String, role: ChatMessage.Role, text: String, ts: Long, wireId: String? = null) =
        ChatMessage(id, role, text, ChatMessage.Status.DONE, ts, wireId = wireId)

    /** 1. 旧版 chat_history.json 无损迁移为会话 #1：消息逐条一致，标题/ts 派生，旧文件删除 */
    @Test
    fun legacyFileMigratesToSessionOneLosslessly() {
        val msgs = listOf(
            msg("u1", ChatMessage.Role.USER, "你好小朵，今天天气怎么样", 111L, wireId = "u_1_1"),
            msg("a1", ChatMessage.Role.ASSISTANT, "晴天，适合出门", 112L),
            msg("u2", ChatMessage.Role.USER, "谢谢", 113L),
            msg("a2", ChatMessage.Role.ASSISTANT, "不客气", 114L),
        )
        legacyFile().writeText(ChatHistoryCodec.encode(msgs))

        val index = store.loadIndex()

        assertEquals(1, index.sessions.size)
        val s = index.sessions[0]
        assertEquals("你好小朵，今天天气怎么样", s.title)
        assertEquals(111L, s.createdAt)
        assertEquals(114L, s.updatedAt)
        assertEquals(s.id, index.currentId)
        assertFalse(legacyFile().exists())
        // 无损：读回消息逐条一致（含 wireId）
        assertEquals(msgs, store.loadMessages(s.id))
    }

    /** 2. 旧文件损坏/为空：不建会话，直接移除（避免每次启动重试） */
    @Test
    fun corruptLegacyFileRemovedWithoutSession() {
        legacyFile().writeText("{{{not json")
        val index = store.loadIndex()
        assertTrue(index.sessions.isEmpty())
        assertFalse(legacyFile().exists())
    }

    /** 3. 杀进程遗留状态迁移随旧文件导入生效（streaming→done，逻辑在 ChatHistoryCodec） */
    @Test
    fun legacyStatusMigrationAppliesOnImport() {
        val streaming = ChatMessage(
            "a1", ChatMessage.Role.ASSISTANT, "半截回复", ChatMessage.Status.STREAMING, 2L,
        )
        legacyFile().writeText(
            ChatHistoryCodec.encode(listOf(msg("u1", ChatMessage.Role.USER, "问", 1L), streaming)),
        )
        val index = store.loadIndex()
        val back = store.loadMessages(index.sessions[0].id)
        assertEquals(ChatMessage.Status.DONE, back[1].status)
    }

    /** 4. 新建会话 → 保存消息 → 读回一致；标题随首条 user 消息派生、updatedAt 刷新 */
    @Test
    fun createSaveLoadRoundTripWithDerivedTitle() {
        val s = store.createSession()
        assertEquals("s1", s.id)
        assertEquals(ChatSession.FALLBACK_TITLE, s.title)
        assertEquals(s.id, store.loadIndex().currentId)

        clock = 2000L
        val msgs = listOf(
            msg("u1", ChatMessage.Role.USER, "红烧肉怎么做", 1L),
            msg("a1", ChatMessage.Role.ASSISTANT, "先焯水", 2L),
        )
        store.saveMessages(s.id, msgs)

        assertEquals(msgs, store.loadMessages(s.id))
        val index = store.loadIndex()
        assertEquals("红烧肉怎么做", index.sessions[0].title)
        assertEquals(2000L, index.sessions[0].updatedAt)
    }

    /** 5. 删除非当前会话：数据文件移除，其余会话与当前选择不受影响 */
    @Test
    fun deleteNonCurrentSessionKeepsOthersUntouched() {
        val a = store.createSession()
        store.saveMessages(a.id, listOf(msg("u1", ChatMessage.Role.USER, "甲会话", 1L)))
        val b = store.createSession()
        store.saveMessages(b.id, listOf(msg("u2", ChatMessage.Role.USER, "乙会话", 2L)))

        val index = store.deleteSession(a.id)

        assertEquals(listOf(b.id), index.sessions.map { it.id })
        assertEquals(b.id, index.currentId)
        assertEquals(emptyList<ChatMessage>(), store.loadMessages(a.id))
        assertEquals(1, store.loadMessages(b.id).size)
    }

    /** 6. 删除当前会话：回落到最近更新的其余会话 */
    @Test
    fun deleteCurrentFallsBackToMostRecentlyUpdated() {
        val a = store.createSession()
        clock = 2000L
        store.saveMessages(a.id, listOf(msg("u1", ChatMessage.Role.USER, "甲", 1L)))
        clock = 3000L
        val b = store.createSession()

        val index = store.deleteSession(b.id)

        assertEquals(a.id, index.currentId)
        assertEquals(listOf(a.id), index.sessions.map { it.id })
    }

    /** 7. 删除唯一会话：currentId 置空（VM 层据此造新空会话） */
    @Test
    fun deleteOnlySessionClearsCurrent() {
        val a = store.createSession()
        val index = store.deleteSession(a.id)
        assertNull(index.currentId)
        assertTrue(index.sessions.isEmpty())
    }

    /** 8. 当前会话选择随索引持久化：新实例（模拟杀进程重启）读回同一当前会话与消息 */
    @Test
    fun currentSelectionSurvivesStoreRecreation() {
        val a = store.createSession()
        store.saveMessages(a.id, listOf(msg("u1", ChatMessage.Role.USER, "甲", 1L)))
        val b = store.createSession()
        store.saveMessages(b.id, listOf(msg("u2", ChatMessage.Role.USER, "乙", 2L)))
        store.setCurrent(a.id)

        val store2 = ChatStore(tmp.root, now = { clock }, newId = { "x${++idSeq}" })
        val index = store2.loadIndex()
        assertEquals(a.id, index.currentId)
        assertEquals(2, index.sessions.size)
        assertEquals(1, store2.loadMessages(a.id).size)
        assertEquals("甲", store2.loadMessages(a.id)[0].text)
    }

    /** 9. 搜索 IO 包装：跨会话标题 + 消息全文命中，按 updatedAt 倒序 */
    @Test
    fun searchSessionsWrapsStoreData() {
        val a = store.createSession()
        clock = 2000L
        store.saveMessages(a.id, listOf(msg("u1", ChatMessage.Role.USER, "红烧肉怎么做", 1L)))
        clock = 3000L
        val b = store.createSession()
        store.saveMessages(b.id, listOf(msg("u2", ChatMessage.Role.USER, "红烧肉要焯水吗", 2L)))

        val hits = store.searchSessions("红烧肉")
        assertEquals(listOf(b.id, a.id), hits.map { it.session.id })
        assertTrue(store.searchSessions("").isEmpty())
        assertTrue(store.searchSessions("不存在的词").isEmpty())
    }

    /** 10. renameSession：本地改 title + 置 titleManual + 存索引（新实例读回一致）；updatedAt 不动 */
    @Test
    fun renameSessionSetsManualTitleAndPersists() {
        val a = store.createSession()
        store.saveMessages(a.id, listOf(msg("u1", ChatMessage.Role.USER, "派生标题", 1L)))
        val before = store.loadIndex().sessions[0].updatedAt

        clock = 9999L
        val renamed = store.renameSession(a.id, "  我的法律咨询  ")

        assertEquals("我的法律咨询", renamed!!.title) // 首尾空白裁剪
        assertTrue(renamed.titleManual)
        // 改名不刷新 updatedAt：会话活跃度没变，列表排序不应跳动
        assertEquals(before, renamed.updatedAt)
        val store2 = ChatStore(tmp.root, now = { clock }, newId = { "x${++idSeq}" })
        val back = store2.loadIndex().sessions[0]
        assertEquals("我的法律咨询", back.title)
        assertTrue(back.titleManual)
    }

    /** 11. 手动命名后 saveMessages 跳过 deriveTitle：发新消息 title 不被覆盖 */
    @Test
    fun saveMessagesKeepsManualTitleAfterRename() {
        val a = store.createSession()
        store.saveMessages(a.id, listOf(msg("u1", ChatMessage.Role.USER, "旧派生", 1L)))
        store.renameSession(a.id, "手动名")

        clock = 5000L
        store.saveMessages(a.id, listOf(msg("u2", ChatMessage.Role.USER, "完全不同的新消息", 2L)))

        val s = store.loadIndex().sessions[0]
        assertEquals("手动名", s.title)
        assertEquals(5000L, s.updatedAt) // updatedAt 仍随保存刷新
    }

    /** 12. 未手动命名的会话 saveMessages 仍正常 deriveTitle（回归保护） */
    @Test
    fun saveMessagesStillDerivesTitleWhenNotManual() {
        val a = store.createSession()
        store.saveMessages(a.id, listOf(msg("u1", ChatMessage.Role.USER, "红烧肉怎么做", 1L)))
        assertEquals("红烧肉怎么做", store.loadIndex().sessions[0].title)
        assertFalse(store.loadIndex().sessions[0].titleManual)
    }

    /** 13. renameSession 边界：空白标题忽略 / 会话不存在返回 null，索引均不变 */
    @Test
    fun renameSessionBlankOrMissingIgnored() {
        val a = store.createSession()
        store.saveMessages(a.id, listOf(msg("u1", ChatMessage.Role.USER, "原标题", 1L)))

        assertNull(store.renameSession(a.id, "   "))
        assertNull(store.renameSession("no-such-id", "新名"))

        val s = store.loadIndex().sessions[0]
        assertEquals("原标题", s.title)
        assertFalse(s.titleManual)
    }
}
