package com.xiaoduo.deskpet.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SessionIndexCodec：索引编解码 round-trip 与坏数据兜底。 */
class SessionIndexCodecTest {

    private fun session(id: String, title: String = "t$id", created: Long = 100L, updated: Long = 200L) =
        ChatSession(id, title, created, updated)

    @Test
    fun roundTripPreservesAllFields() {
        val index = SessionIndex("s2", listOf(session("s1"), session("s2", "天气咨询", 1L, 2L)))
        val back = SessionIndexCodec.decode(SessionIndexCodec.encode(index))
        assertEquals(index, back)
    }

    @Test
    fun nullCurrentRoundTrips() {
        val index = SessionIndex(null, listOf(session("s1")))
        val back = SessionIndexCodec.decode(SessionIndexCodec.encode(index))
        assertNull(back.currentId)
        assertEquals(1, back.sessions.size)
    }

    @Test
    fun emptyIndexRoundTrips() {
        val back = SessionIndexCodec.decode(SessionIndexCodec.encode(SessionIndex(null, emptyList())))
        assertNull(back.currentId)
        assertEquals(0, back.sessions.size)
    }

    @Test
    fun badJsonYieldsEmptyIndex() {
        assertEquals(SessionIndex(null, emptyList()), SessionIndexCodec.decode("{{{not json"))
        assertEquals(SessionIndex(null, emptyList()), SessionIndexCodec.decode(""))
        // 数组而非对象 → 空索引
        assertEquals(SessionIndex(null, emptyList()), SessionIndexCodec.decode("[]"))
        // 缺 sessions 字段 → 空索引
        assertEquals(SessionIndex(null, emptyList()), SessionIndexCodec.decode("""{"current":"s1"}"""))
    }

    @Test
    fun entriesWithBlankIdSkipped() {
        val json = """{"current":"s1","sessions":[{"id":"","title":"坏行"},{"id":"s1","title":"好行","createdAt":1,"updatedAt":2}]}"""
        val back = SessionIndexCodec.decode(json)
        assertEquals(1, back.sessions.size)
        assertEquals("s1", back.sessions[0].id)
        assertEquals("s1", back.currentId)
    }

    /** titleManual 编解码 round-trip：true/false 均逐位保留（ADR 0002 手动命名标志） */
    @Test
    fun titleManualRoundTrips() {
        val index = SessionIndex(
            "s1",
            listOf(
                ChatSession("s1", "手动名", 1L, 2L, titleManual = true),
                ChatSession("s2", "派生名", 3L, 4L, titleManual = false),
            ),
        )
        val back = SessionIndexCodec.decode(SessionIndexCodec.encode(index))
        assertEquals(index, back)
        assertTrue(back.sessions[0].titleManual)
        assertFalse(back.sessions[1].titleManual)
    }

    /** 向后兼容：旧索引 JSON 无 titleManual 字段 → 缺省 false（继续走 deriveTitle） */
    @Test
    fun legacyJsonWithoutTitleManualDefaultsFalse() {
        val json = """{"current":"s1","sessions":[{"id":"s1","title":"旧会话","createdAt":1,"updatedAt":2}]}"""
        val back = SessionIndexCodec.decode(json)
        assertEquals(1, back.sessions.size)
        assertFalse(back.sessions[0].titleManual)
    }

    /** DATA-1(249/251)：JSON null 的 id / current 不得经 optString 读成 "null" 幽灵会话 id。 */
    @Test
    fun `DATA-1 json null id and current never become literal null strings`() {
        val json = """{"current":null,"sessions":[
            {"id":null,"title":"幽灵","createdAt":1,"updatedAt":2},
            {"id":"s1","title":"真身","createdAt":3,"updatedAt":4}
        ]}"""
        val back = SessionIndexCodec.decode(json)
        assertEquals(1, back.sessions.size)
        assertEquals("s1", back.sessions[0].id)
        assertNull(back.currentId)
    }
}
