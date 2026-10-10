package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.voice.pipeline.SentenceSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSplitterTest {

    private fun feedChars(sp: SentenceSplitter, s: String): List<String> =
        s.flatMap { sp.feed(it) }

    @Test
    fun `cuts at end punctuation when at least MIN chars`() {
        val sp = SentenceSplitter()
        assertTrue(feedChars(sp, "你好呀").isEmpty())
        assertEquals(listOf("你好呀！"), sp.feed('！'))
        assertEquals(listOf("再见啦！"), feedChars(sp, "再见啦！"))
    }

    @Test
    fun `fragment shorter than MIN is not cut and merges with next`() {
        val sp = SentenceSplitter()
        // "嗯。" 仅 3 字符 < MIN 4，不切；攒到下一个句末一起出
        assertTrue(feedChars(sp, "嗯。好的").isEmpty())
        assertEquals(listOf("嗯。好的。"), sp.feed('。'))
    }

    @Test
    fun `each end char cuts directly (semicolon bang question marks)`() {
        // 防从 SENTENCE_END_CHARS 集合删字符不红：逐字符直接验证
        for (end in charArrayOf(';', '!', '?', '？')) {
            val sp = SentenceSplitter()
            assertTrue("前置字不应触发: $end", feedChars(sp, "你好呀").isEmpty())
            assertEquals("切点 $end", listOf("你好呀$end"), sp.feed(end))
        }
    }

    @Test
    fun `newline cuts and emitted sentence is stripped`() {
        val sp = SentenceSplitter()
        assertEquals(listOf("第一行"), feedChars(sp, "第一行\n"))
        // 切口后的前导空白随 strip 去掉
        assertEquals(listOf("第二行。"), feedChars(sp, "  第二行。"))
    }

    @Test
    fun `force cut at MAX without end punctuation`() {
        val sp = SentenceSplitter()
        val sent = feedChars(sp, "一".repeat(30))
        assertEquals(listOf("一".repeat(30)), sent)
        assertNull(sp.flush())
    }

    @Test
    fun `long cut inside ascii alnum run falls back to word boundary`() {
        val sp = SentenceSplitter()
        assertTrue(feedChars(sp, "一".repeat(25)).isEmpty())
        // 多字符 token 使 buf 冲到 31：切口落在 abcdef 中间 → 回退到词边界（第 25 字后）
        assertEquals(listOf("一".repeat(25)), sp.feed("abcdef"))
        assertEquals("abcdef", sp.flush())
    }

    @Test
    fun `long cut without word boundary hard cuts at MAX`() {
        val sp = SentenceSplitter()
        // 31 个连续 ASCII 字母无词边界 → 硬切 30
        assertEquals(listOf("a".repeat(30)), sp.feed("a".repeat(31)))
        assertEquals("a", sp.flush())
    }

    @Test
    fun `flush emits non-blank remainder and drops blank`() {
        val sp = SentenceSplitter()
        assertTrue(feedChars(sp, "残句残留").isEmpty())
        assertEquals("残句残留", sp.flush())

        val blank = SentenceSplitter()
        feedChars(blank, "  \t ")
        assertNull(blank.flush())
    }
}
