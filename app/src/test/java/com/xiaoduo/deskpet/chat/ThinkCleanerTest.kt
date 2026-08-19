package com.xiaoduo.deskpet.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkCleanerTest {

    @Test
    fun `complete think block stripped`() {
        val raw = "<think>用户问的是档案法，我要分三点</think>档案法核心有三点。"
        assertEquals("档案法核心有三点。", ThinkCleaner.clean(raw))
    }

    @Test
    fun `stray close tag stripped`() {
        // 真机实测形态（implementation-notes 2026-07-30）：回复中段混入字面量 </think>
        val raw = "第一点，总则。</think>第二点，利用与公布。"
        assertEquals("第一点，总则。第二点，利用与公布。", ThinkCleaner.clean(raw))
    }

    @Test
    fun `unclosed think block truncated for streaming`() {
        val raw = "<think>思考过程还没走完"
        assertEquals("", ThinkCleaner.clean(raw))
    }

    @Test
    fun `trailing half tag held back at delta boundary`() {
        assertEquals("你好", ThinkCleaner.clean("你好</th"))
        assertEquals("你好", ThinkCleaner.clean("你好<thi"))
        assertEquals("你好", ThinkCleaner.clean("你好</"))
    }

    @Test
    fun `normal text untouched including angle brackets`() {
        val raw = "1 < 2 且 b > a，没有 think 标签。"
        assertEquals(raw, ThinkCleaner.clean(raw))
    }

    @Test
    fun `multiple blocks and stray tags combined`() {
        val raw = "<think>第一段思考</think>正式回答。</think>后续内容<think>又一段</think>收尾。"
        assertEquals("正式回答。后续内容收尾。", ThinkCleaner.clean(raw))
    }

    // ---------------- 裸 < 闪现（S2 返工） ----------------

    @Test
    fun `bare trailing angle bracket held back while streaming`() {
        assertEquals("你好", ThinkCleaner.clean("你好<"))
        assertEquals("", ThinkCleaner.clean("<"))
        assertEquals("你好", ThinkCleaner.clean("你好<t"))
    }

    @Test
    fun `char by char think tag spelling never exposes bare bracket or half tag`() {
        // 逐字符拼出完整 think 块：任何前缀的清洗输出都不得含裸 < 或半截标签
        val full = "<think>秘密思考</think>正文"
        for (end in 1..full.length) {
            val out = ThinkCleaner.clean(full.substring(0, end))
            assertTrue("prefix[$end] leaked: <$out>", !out.contains("<"))
            assertTrue("prefix[$end] leaked: <$out>", !out.contains("think"))
        }
        assertEquals("正文", ThinkCleaner.clean(full))
    }

    @Test
    fun `char by char stray close tag never exposes half tag`() {
        val full = "前文</think>正文"
        for (end in 1..full.length) {
            val out = ThinkCleaner.clean(full.substring(0, end))
            assertTrue("prefix[$end] leaked: <$out>", !out.contains("<"))
        }
        assertEquals("前文正文", ThinkCleaner.clean(full))
    }
}
