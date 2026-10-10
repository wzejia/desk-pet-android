package com.xiaoduo.deskpet.chat

import com.xiaoduo.deskpet.api.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ChatSession.deriveTitle：标题 = 首条用户消息截断（用户拍板①）；
 * 无用户消息 / 纯空白 → 兜底「新会话」。
 */
class ChatSessionTest {

    private fun user(text: String, ts: Long = 1L) =
        ChatMessage("u$ts", ChatMessage.Role.USER, text, ChatMessage.Status.DONE, ts)

    private fun assistant(text: String, ts: Long = 1L) =
        ChatMessage("a$ts", ChatMessage.Role.ASSISTANT, text, ChatMessage.Status.DONE, ts)

    @Test
    fun firstUserMessageBecomesTitle() {
        val msgs = listOf(assistant("你好吗"), user("今天天气怎么样"), assistant("晴"))
        assertEquals("今天天气怎么样", ChatSession.deriveTitle(msgs))
    }

    @Test
    fun longTitleTruncatedAt20CharsWithEllipsis() {
        val long = "这是一条非常非常长的用户消息用来验证标题截断规则是否生效"
        assertEquals(long.take(20) + "…", ChatSession.deriveTitle(listOf(user(long))))
    }

    @Test
    fun exactly20CharsNotTruncated() {
        val t = "一二三四五六七八九十一二三四五六七八九十"
        assertEquals(20, t.length)
        assertEquals(t, ChatSession.deriveTitle(listOf(user(t))))
    }

    @Test
    fun whitespaceCollapsedAndTrimmed() {
        assertEquals("你好 小朵", ChatSession.deriveTitle(listOf(user("  你好\n\n  小朵  "))))
    }

    @Test
    fun noUserMessageFallsBack() {
        assertEquals(ChatSession.FALLBACK_TITLE, ChatSession.deriveTitle(listOf(assistant("只有回复"))))
        assertEquals(ChatSession.FALLBACK_TITLE, ChatSession.deriveTitle(emptyList()))
    }

    @Test
    fun blankUserMessageFallsBack() {
        assertEquals(ChatSession.FALLBACK_TITLE, ChatSession.deriveTitle(listOf(user("   \n  "))))
    }
}
