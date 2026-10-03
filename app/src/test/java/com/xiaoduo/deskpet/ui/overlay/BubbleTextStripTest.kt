package com.xiaoduo.deskpet.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S7-c：BubbleTextStrip 由「剥离输出纯文本」反转为「渲染输出 StyledText」，
 * 原剥离断言等价改写为 spanned 断言（text 内容等价 + span type/区间钉住）。
 */
class BubbleTextStripTest {

    // ---------- 一次性 render：行内标记 → span ----------

    @Test
    fun `bold markers become BOLD span keeping text`() {
        // 真机取证形态（s5-voice-round 顶部气泡）：** 原样裸露
        val r = BubbleTextStrip.render("**小朵提醒您**：今天有雨")
        assertEquals("小朵提醒您：今天有雨", r.text)
        assertEquals(listOf(Span(Type.BOLD, 0, 5)), r.spans)
    }

    @Test
    fun `bold allows digit-leading content`() {
        val r = BubbleTextStrip.render("评分 **5星** 好评")
        assertEquals("评分 5星 好评", r.text)
        assertEquals(listOf(Span(Type.BOLD, 3, 5)), r.spans)
    }

    @Test
    fun `italic becomes ITALIC span but math star kept literal`() {
        val r = BubbleTextStrip.render("*注意* 安全")
        assertEquals("注意 安全", r.text)
        assertEquals(listOf(Span(Type.ITALIC, 0, 2)), r.spans)
        // 数学/词内单星不成对，字面值保留、不产 span
        val m = BubbleTextStrip.render("2*3=6 与 a*b")
        assertEquals("2*3=6 与 a*b", m.text)
        assertTrue(m.spans.isEmpty())
    }

    @Test
    fun `inline code becomes CODE span keeping content`() {
        val r = BubbleTextStrip.render("执行 `adb devices` 查看")
        assertEquals("执行 adb devices 查看", r.text)
        assertEquals(listOf(Span(Type.CODE, 3, 14)), r.spans)
    }

    @Test
    fun `nested code inside bold produces both spans`() {
        val r = BubbleTextStrip.render("**看 `adb` 命令**")
        assertEquals("看 adb 命令", r.text)
        assertEquals(
            listOf(Span(Type.CODE, 2, 5), Span(Type.BOLD, 0, 8)),
            r.spans,
        )
    }

    @Test
    fun `list item prefixes become bullet`() {
        val r = BubbleTextStrip.render("- 第一项\n* 第二项\n  - 缩进项")
        assertEquals("• 第一项\n• 第二项\n• 缩进项", r.text)
        assertTrue(r.spans.isEmpty())
    }

    // ---------- 一次性 render：块级降级语义不变（等价改写：text 断言 + 无 span）----------

    @Test
    fun `table rows readable and separator line skipped`() {
        val raw = "| 名称 | 数量 |\n| --- | --- |\n| 档案 | 3 |"
        val r = BubbleTextStrip.render(raw)
        assertEquals("名称 数量\n档案 3", r.text)
        assertTrue(r.spans.isEmpty())
    }

    @Test
    fun `heading prefix removed`() {
        assertEquals("二、利用\n档案", BubbleTextStrip.render("## 二、利用\n档案").text)
    }

    @Test
    fun `quote prefix removed`() {
        assertEquals("档案法规定\n第二十三条", BubbleTextStrip.render("> 档案法规定\n> 第二十三条").text)
    }

    @Test
    fun `rule line skipped`() {
        assertEquals("上文\n下文", BubbleTextStrip.render("上文\n---\n下文").text)
    }

    @Test
    fun `plain text and empty unchanged`() {
        val raw = "你好，世界。"
        assertEquals(StyledText(raw), BubbleTextStrip.render(raw))
        assertEquals(StyledText(""), BubbleTextStrip.render(""))
    }

    @Test
    fun `extra whitespace and blank lines compressed`() {
        assertEquals("第一 点，\n第二", BubbleTextStrip.render("第一  点，\n\n\n第二").text)
    }

    @Test
    fun `unclosed markers in one-shot render degrade gracefully without spans`() {
        // 未闭合粗体丢标记留内容、不产 span；未闭合单星字面值保留（数学安全）
        val b = BubbleTextStrip.render("**没有闭合的粗体")
        assertEquals("没有闭合的粗体", b.text)
        assertTrue(b.spans.isEmpty())
        val i = BubbleTextStrip.render("孤立 * 单星")
        assertEquals("孤立 * 单星", i.text)
        assertTrue(i.spans.isEmpty())
    }

    @Test
    fun `one-shot render drops empty code span markers`() {
        assertEquals(StyledText("见 与"), BubbleTextStrip.render("见 `` 与"))
    }

    // ---------- S8-4：表格/代码块/多级标题/引用 输出无裸控制符 ----------

    @Test
    fun `fenced code block swallows fence lines keeping content`() {
        // S8-4 主修复：```python 围栏行整行吞，语言标签不得成游离文字行
        val r = BubbleTextStrip.render("前文\n```python\nprint(1)\n```\n结束")
        assertEquals("前文\nprint(1)\n结束", r.text)
        assertFalse(r.text.contains("`"))
        assertFalse(r.text.contains("python"))
        assertTrue(r.spans.isEmpty())
    }

    @Test
    fun `fence without language tag and unclosed fence degrade safely`() {
        assertEquals("code", BubbleTextStrip.render("```\ncode\n```").text)
        // 未闭合围栏：后续内容按块内内容降级流出，围栏行不裸显
        val r = BubbleTextStrip.render("```js\ncode")
        assertEquals("code", r.text)
        assertFalse(r.text.contains("`"))
    }

    @Test
    fun `heading hashes stripped even without space`() {
        assertEquals("标题", BubbleTextStrip.render("##标题").text)
        assertEquals("四", BubbleTextStrip.render("#### 四").text)
        // 孤 # 行剥成空行跳过
        assertEquals("正文", BubbleTextStrip.render("#\n正文").text)
        assertFalse(BubbleTextStrip.render("##标题").text.contains("#"))
    }

    @Test
    fun `quote marker stripped without space`() {
        val r = BubbleTextStrip.render(">引用")
        assertEquals("引用", r.text)
        assertFalse(r.text.contains(">"))
    }

    @Test
    fun `table output never contains bare pipe`() {
        val r = BubbleTextStrip.render("| 名称 | 数量 |\n| --- | --- |\n| 档案 | 3 |")
        assertEquals("名称 数量\n档案 3", r.text)
        assertFalse(r.text.contains('|'))
    }

    @Test
    fun `streaming split fence line held then swallowed`() {
        val s = BubbleTextStrip.Session()
        // 围栏行半截到达：第三反引号处 hold，不吐裸标记
        assertEquals(StyledText(""), s.feed("```py").text)
        val e = s.feed("thon\ncode\n```\n尾")
        assertFalse(e.reset)
        assertEquals("code\n尾", e.text.text)
        assertFalse(e.text.text.contains("`"))
    }

    // ---------- StyledText 模型：prefix / dropStart / plus ----------

    @Test
    fun `prefix truncates spans into range`() {
        val r = BubbleTextStrip.render("**abcdef** 尾巴")
        // 截在 BOLD 区间内：span 端点裁到 n
        assertEquals(StyledText("abc", listOf(Span(Type.BOLD, 0, 3))), r.prefix(3))
        // 截在 span 之后：完全在外的 span 丢弃
        val r2 = BubbleTextStrip.render("**ab** `cd`")
        assertEquals(StyledText("ab ", listOf(Span(Type.BOLD, 0, 2))), r2.prefix(3))
        // 越界 n 钳位
        assertEquals(StyledText(""), r.prefix(0))
        assertEquals(r, r.prefix(999))
    }

    @Test
    fun `dropStart shifts remaining spans`() {
        val r = BubbleTextStrip.render("前缀 `code` 尾巴")
        // text = "前缀 code 尾巴"，CODE(3,7)；drop 4 → "ode 尾巴"，CODE(0,3)
        assertEquals("前缀 code 尾巴", r.text)
        assertEquals(StyledText("ode 尾巴", listOf(Span(Type.CODE, 0, 3))), r.dropStart(4))
        assertEquals(StyledText(""), r.dropStart(999))
    }

    @Test
    fun `plus concatenates with span offset`() {
        val a = BubbleTextStrip.render("**x**")
        val b = BubbleTextStrip.render("`y`")
        assertEquals(
            StyledText("xy", listOf(Span(Type.BOLD, 0, 1), Span(Type.CODE, 1, 2))),
            a + b,
        )
        // 空侧恒等
        assertEquals(a, a + StyledText(""))
        assertEquals(a, StyledText("") + a)
    }

    // ---------- 流式 Session：holdback 行为不变，产出带 span ----------

    @Test
    fun `streaming half bold held back then completed with BOLD span`() {
        val s = BubbleTextStrip.Session()
        assertEquals(StyledText(""), s.feed("**").text)
        assertEquals(StyledText(""), s.feed("小朵提醒").text)
        val e = s.feed("**今天晴")
        assertFalse(e.reset)
        assertEquals(StyledText("小朵提醒今天晴", listOf(Span(Type.BOLD, 0, 4))), e.text)
    }

    @Test
    fun `streaming italic resolves on close and math star never freezes`() {
        val s = BubbleTextStrip.Session()
        // 数学单星：digit 防护直接按字面值吐出，不 hold 不冻结
        assertEquals("2*3", s.feed("2*3").text.text)
        assertEquals("=6，", s.feed("=6，").text.text)
        // 合规斜体：闭合前先 hold，闭合后连内容带 ITALIC span 吐出
        assertEquals(StyledText(""), s.feed("*斜").text)
        val e = s.feed("体*。")
        assertEquals(StyledText("斜体。", listOf(Span(Type.ITALIC, 0, 2))), e.text)
    }

    @Test
    fun `streaming unclosed italic beyond cap released as literal`() {
        val s = BubbleTextStrip.Session()
        // 未闭合斜体超过 24 字上限：按字面值放行（防打字机永久冻结）
        val tail = "这是一段没有闭合标记而且足够长的内容用来超过二十四字符的上限限制"
        val e = s.feed("备注：*$tail")
        assertFalse(e.reset)
        assertEquals("备注：*$tail", e.text.text)
        assertTrue(e.text.spans.isEmpty())
    }

    @Test
    fun `streaming empty code span does not freeze output`() {
        // 空 code span（两个紧邻反引号）：剥掉两个标记、不 hold、后续文本正常流出
        val s = BubbleTextStrip.Session()
        val e = s.feed("见 `` 与")
        assertFalse(e.reset)
        assertEquals(StyledText("见 与"), e.text)
    }

    @Test
    fun `streaming unclosed backtick within cap held then closed with CODE span`() {
        // 上限内的未闭合反引号：先 hold，闭合后连内容吐出（钉住既有正常路径不回归）
        val s = BubbleTextStrip.Session()
        assertEquals(StyledText("执行"), s.feed("执行 `adb").text)
        val e = s.feed(" devices` 查看")
        assertFalse(e.reset)
        // 尾巴相对全文偏移：全文「执行 adb devices 查看」CODE(3,14) → 尾巴 CODE(1,12)
        assertEquals(StyledText(" adb devices 查看", listOf(Span(Type.CODE, 1, 12))), e.text)
    }

    @Test
    fun `streaming unclosed backtick beyond cap released as content`() {
        // 残缺反引号超上限：丢标记留内容放行（防打字机永久冻结，体例同单 * 24 字封顶）
        val s = BubbleTextStrip.Session()
        val tail = "这是一段没有闭合反引号而且足够长的内容用来超过四十八字符的反引号冻结上限限制所以需要按字面值放行出尾巴"
        val e = s.feed("`$tail")
        assertFalse(e.reset)
        assertEquals(tail, e.text.text)
        assertTrue(e.text.spans.isEmpty())
    }

    @Test
    fun `streaming table separator partial line held until resolved`() {
        val s = BubbleTextStrip.Session()
        assertEquals("名称 值", s.feed("| 名称 | 值 |\n").text.text)
        // 分隔行逐字符到达：疑似分隔行先 hold，成行后整行跳过
        assertEquals(StyledText(""), s.feed("|--").text)
        assertEquals(StyledText(""), s.feed("--|\n").text)
        assertEquals("\na b", s.feed("| a | b |").text.text)
    }

    @Test
    fun `streaming list marker held until content then bullet`() {
        val s = BubbleTextStrip.Session()
        // 孤「-」是疑似标记行，先 hold；内容到达后行首转「• 」
        assertEquals(StyledText(""), s.feed("-").text)
        val e = s.feed(" 第一项")
        assertFalse(e.reset)
        assertEquals("• 第一项", e.text.text)
    }

    @Test
    fun `streaming spans stay stable across continuation no reset`() {
        val s = BubbleTextStrip.Session()
        val e1 = s.feed("**粗**")
        assertFalse(e1.reset)
        assertEquals(StyledText("粗", listOf(Span(Type.BOLD, 0, 1))), e1.text)
        // 续打纯文本：前缀（含 span）稳定，不走 reset 兜底
        val e2 = s.feed("尾巴")
        assertFalse(e2.reset)
        assertEquals(StyledText("尾巴"), e2.text)
    }

    // ---------- FUNC-5：未闭合 ** hold 上限 + 流尾 flush ----------

    @Test
    fun `streaming unclosed bold within cap held until close`() {
        // 上限内未闭合 **：仍先 hold（钉住既有正常路径不回归）
        val s = BubbleTextStrip.Session()
        assertEquals(StyledText("看"), s.feed("看**重点").text)
    }

    @Test
    fun `streaming unclosed bold beyond cap released as content`() {
        // 未闭合 ** 超过 48 字上限：丢标记留内容放行（体例同反引号 48/斜体 24，防打字机永久冻结）
        val s = BubbleTextStrip.Session()
        val content = "重".repeat(47)
        val e = s.feed("看**$content")
        assertFalse(e.reset)
        assertEquals("看$content", e.text.text)
        assertTrue(e.text.spans.isEmpty())
    }

    @Test
    fun `flush emits tail of unclosed bold at end of stream`() {
        // FUNC-5 反例（简报）：LLM 回复以未闭合 ** 收尾 → feed 只吐 hold 点前内容，flush 补发残余
        val s = BubbleTextStrip.Session()
        assertEquals(StyledText("注意"), s.feed("注意**这里是重点").text)
        val e = s.flush()
        assertFalse(e.reset)
        assertEquals(StyledText("这里是重点"), e.text)
    }

    @Test
    fun `flush emits tail of unclosed backtick at end of stream`() {
        val s = BubbleTextStrip.Session()
        assertEquals(StyledText("执行"), s.feed("执行 `adb devices").text)
        val e = s.flush()
        assertFalse(e.reset)
        assertEquals(StyledText(" adb devices"), e.text)
    }

    @Test
    fun `flush after fully emitted stream is empty no-op and idempotent`() {
        val s = BubbleTextStrip.Session()
        val e1 = s.feed("你好**加粗**世界")
        assertEquals("你好加粗世界", e1.text.text)
        val e2 = s.flush()
        assertFalse(e2.reset)
        assertEquals(StyledText(""), e2.text)
        // 幂等：重复 flush 不重复吐
        assertEquals(StyledText(""), s.flush().text)
    }
}
