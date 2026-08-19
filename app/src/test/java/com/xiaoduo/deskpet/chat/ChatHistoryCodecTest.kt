package com.xiaoduo.deskpet.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryCodecTest {

    @Test
    fun `roundtrip preserves all fields`() {
        val messages = listOf(
            ChatMessage("m1", ChatMessage.Role.USER, "你好小朵", ChatMessage.Status.DONE, 1000L),
            ChatMessage("m2", ChatMessage.Role.ASSISTANT, "你好，**有什么**可以帮你？", ChatMessage.Status.DONE, 2000L),
            ChatMessage("m3", ChatMessage.Role.SYSTEM, "sys", ChatMessage.Status.ERROR, 3000L),
        )
        val decoded = ChatHistoryCodec.decode(ChatHistoryCodec.encode(messages))
        assertEquals(messages, decoded)
    }

    @Test
    fun `killed mid-send migrates sending to error`() {
        // 杀进程遗留：用户消息卡在 sending → 重进后应为 error（可重发）
        val json = """[{"id":"m1","role":"user","text":"在吗","status":"sending","ts":1}]"""
        val decoded = ChatHistoryCodec.decode(json)
        assertEquals(1, decoded.size)
        assertEquals(ChatMessage.Status.ERROR, decoded[0].status)
    }

    @Test
    fun `killed mid-stream migrates streaming to done when text present`() {
        val json = """[{"id":"m2","role":"assistant","text":"半段回复","status":"streaming","ts":2}]"""
        assertEquals(ChatMessage.Status.DONE, ChatHistoryCodec.decode(json)[0].status)
    }

    @Test
    fun `killed mid-stream with empty text becomes error`() {
        val json = """[{"id":"m3","role":"assistant","text":"","status":"streaming","ts":3}]"""
        assertEquals(ChatMessage.Status.ERROR, ChatHistoryCodec.decode(json)[0].status)
    }

    @Test
    fun `corrupt json yields empty list`() {
        assertTrue(ChatHistoryCodec.decode("not json at all").isEmpty())
        assertTrue(ChatHistoryCodec.decode("{\"oops\":1}").isEmpty())
        assertTrue(ChatHistoryCodec.decode("").isEmpty())
    }

    @Test
    fun `wireId roundtrip writes key only when present`() {
        // 非 null 写键 + 读回一致；null 不写键（升级前旧历史保持原样，重发时才现造回写）
        val withWire = ChatMessage(
            "m1", ChatMessage.Role.USER, "在吗", ChatMessage.Status.DONE, 1000L, wireId = "wire-abc",
        )
        val withoutWire = ChatMessage("m2", ChatMessage.Role.ASSISTANT, "在的", ChatMessage.Status.DONE, 2000L)
        val encoded = ChatHistoryCodec.encode(listOf(withWire, withoutWire))
        assertEquals(1, Regex("wireId").findAll(encoded).count())
        val decoded = ChatHistoryCodec.decode(encoded)
        assertEquals("wire-abc", decoded[0].wireId)
        assertNull(decoded[1].wireId)
        assertEquals(listOf(withWire, withoutWire), decoded)
    }

    @Test
    fun `unknown role and status fall back safely`() {
        val json = """[{"id":"m4","role":"alien","text":"hi","status":"weird","ts":4}]"""
        val m = ChatHistoryCodec.decode(json)[0]
        assertEquals(ChatMessage.Role.ASSISTANT, m.role)
        assertEquals(ChatMessage.Status.DONE, m.status)
    }
}
