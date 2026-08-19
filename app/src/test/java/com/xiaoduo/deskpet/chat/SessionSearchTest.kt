package com.xiaoduo.deskpet.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SessionSearch：全文搜索（拍板②：标题 + 消息全文）。
 * 大小写不敏感；空 query → 空；摘要 = 首条命中消息截断。
 */
class SessionSearchTest {

    private fun msg(id: String, role: ChatMessage.Role, text: String) =
        ChatMessage(id, role, text, ChatMessage.Status.DONE, 1L)

    private val s1 = ChatSession("s1", "天气咨询", 1L, 100L)
    private val m1 = listOf(
        msg("u1", ChatMessage.Role.USER, "今天天气怎么样"),
        msg("a1", ChatMessage.Role.ASSISTANT, "晴转多云"),
    )
    private val s2 = ChatSession("s2", "红烧肉菜谱", 2L, 200L)
    private val m2 = listOf(
        msg("u2", ChatMessage.Role.USER, "红烧肉怎么做"),
        msg("a2", ChatMessage.Role.ASSISTANT, "先焯水再小火慢炖"),
    )
    private val s3 = ChatSession("s3", "greeting", 3L, 300L)
    private val m3 = listOf(msg("u3", ChatMessage.Role.USER, "Hello World"))
    private val s4 = ChatSession("s4", "空会话占位", 4L, 400L)
    private val m4 = emptyList<ChatMessage>()

    private val all = listOf(s1 to m1, s2 to m2, s3 to m3, s4 to m4)

    @Test
    fun messageTextHitReturnsSessionWithMessageSnippet() {
        val r = SessionSearch.search(all, "焯水")
        assertEquals(1, r.size)
        assertEquals("s2", r[0].session.id)
        assertEquals("先焯水再小火慢炖", r[0].snippet)
    }

    @Test
    fun titleOnlyHitReturnsTitleAsSnippet() {
        val r = SessionSearch.search(all, "菜谱")
        assertEquals(1, r.size)
        assertEquals("s2", r[0].session.id)
        assertEquals("红烧肉菜谱", r[0].snippet)
    }

    @Test
    fun messageHitPreferredOverTitleForSnippet() {
        // 「天气」同时命中 s1 标题与消息 → 摘要取首条命中消息
        val r = SessionSearch.search(all, "天气")
        assertEquals(1, r.size)
        assertEquals("s1", r[0].session.id)
        assertEquals("今天天气怎么样", r[0].snippet)
    }

    @Test
    fun caseInsensitiveMatch() {
        val r = SessionSearch.search(all, "hello")
        assertEquals(1, r.size)
        assertEquals("s3", r[0].session.id)
        assertEquals("Hello World", r[0].snippet)
    }

    @Test
    fun blankQueryReturnsEmpty() {
        assertTrue(SessionSearch.search(all, "").isEmpty())
        assertTrue(SessionSearch.search(all, "   ").isEmpty())
    }

    @Test
    fun noMatchReturnsEmpty() {
        assertTrue(SessionSearch.search(all, "不存在的词").isEmpty())
    }

    @Test
    fun resultOrderFollowsInputOrder() {
        // s1/s2 均含「肉」？不——用同时命中两个会话的词验证顺序：s1 的「晴」与 s2 的「红」无交集，
        // 改用公共词「怎么」：s1「今天天气怎么样」与 s2「红烧肉怎么做」均命中
        val r = SessionSearch.search(all, "怎么")
        assertEquals(listOf("s1", "s2"), r.map { it.session.id })
    }

    @Test
    fun longSnippetTruncatedWithEllipsis() {
        val long = "这是一条很长很长的回复内容，专门用来验证搜索摘要超过三十个字时会被截断并加上省略号的规则"
        val s = ChatSession("s9", "长文", 9L, 900L)
        val r = SessionSearch.search(listOf(s to listOf(msg("a9", ChatMessage.Role.ASSISTANT, long))), "回复")
        assertEquals(1, r.size)
        assertEquals(long.take(SessionSearch.SNIPPET_MAX_CHARS) + "…", r[0].snippet)
    }
}
