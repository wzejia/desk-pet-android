package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.voice.pipeline.TtsSanitizer
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsSanitizerTest {

    @Test
    fun `whitelist punctuation preserved char by char`() {
        val punct = "，。！？；：、,.!?;:" +
            "「」『』“”‘’'\"()（）【】[]《》〈〉" +
            "—…·-–/~%℃°+"
        val input = "甲" + punct + "乙9"
        assertEquals(input, TtsSanitizer.sanitize(input))
    }

    @Test
    fun `strips markdown table syntax but keeps book-title marks`() {
        assertEquals("法规查询 《档案法》", TtsSanitizer.sanitize("| **法规查询** | 《档案法》"))
    }

    @Test
    fun `compresses consecutive whitespace to single space`() {
        assertEquals("你好 世界", TtsSanitizer.sanitize("你好   \t\n\r 世界  "))
    }

    @Test
    fun `nbsp treated as whitespace and compressed (Python isspace parity)`() {
        assertEquals("你好 世界", TtsSanitizer.sanitize("你好  世界"))
        assertEquals("你好 世界", TtsSanitizer.sanitize("你好  世界")) // NBSP 与普通空格混排
    }

    @Test
    fun `pure symbols return empty to skip synthesis`() {
        assertEquals("", TtsSanitizer.sanitize("***|---|"))
        assertEquals("", TtsSanitizer.sanitize("。！？")) // 有标点但无 alnum
        assertEquals("", TtsSanitizer.sanitize("😀😀"))
        assertEquals("", TtsSanitizer.sanitize(""))
    }

    @Test
    fun `strips emoji and keeps text`() {
        assertEquals("你好啊", TtsSanitizer.sanitize("你好😀啊🎉"))
    }

    @Test
    fun `keeps mixed alnum with whitelisted units and hyphen`() {
        assertEquals("GPT-4o 3.14℃ 25°", TtsSanitizer.sanitize("GPT-4o 3.14℃ 25°"))
    }
}
