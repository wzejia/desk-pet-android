package com.xiaoduo.deskpet.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {

    @Test
    fun `bold inline parsed`() {
        val blocks = MarkdownRenderer.parse("档案法的**核心**是三点")
        val p = blocks.single() as MarkdownRenderer.Block.Paragraph
        assertEquals(
            listOf(
                MarkdownRenderer.Segment("档案法的", false),
                MarkdownRenderer.Segment("核心", true),
                MarkdownRenderer.Segment("是三点", false),
            ),
            p.segments,
        )
    }

    @Test
    fun `unclosed bold kept literal for streaming`() {
        val blocks = MarkdownRenderer.parse("前半 **还没闭合")
        val p = blocks.single() as MarkdownRenderer.Block.Paragraph
        assertEquals(listOf(MarkdownRenderer.Segment("前半 **还没闭合", false)), p.segments)
    }

    @Test
    fun `bullet list grouped`() {
        val blocks = MarkdownRenderer.parse("要点如下：\n- 第一条\n* 第二条\n• 第三条\n收尾。")
        assertEquals(3, blocks.size)
        val bullets = blocks[1] as MarkdownRenderer.Block.Bullets
        assertEquals(3, bullets.items.size)
        assertEquals("第二条", bullets.items[1].single().text)
        assertTrue(blocks[2] is MarkdownRenderer.Block.Paragraph)
    }

    @Test
    fun `ordered list keeps original labels`() {
        val blocks = MarkdownRenderer.parse("1. 总则\n2、 利用\n3) 公布")
        val ordered = blocks.single() as MarkdownRenderer.Block.Ordered
        assertEquals(listOf("1.", "2、", "3)"), ordered.items.map { it.label })
        assertEquals("公布", ordered.items[2].segments.single().text)
    }

    @Test
    fun `fenced code block raw preserved`() {
        val blocks = MarkdownRenderer.parse("示例：\n```kotlin\nfun main() {\n  println(1)\n}\n```\n完。")
        assertEquals(3, blocks.size)
        val code = blocks[1] as MarkdownRenderer.Block.Code
        assertEquals("fun main() {\n  println(1)\n}", code.code)
    }

    @Test
    fun `unclosed code block closed at stream end`() {
        val blocks = MarkdownRenderer.parse("```\nline1\nline2")
        val code = blocks.single() as MarkdownRenderer.Block.Code
        assertEquals("line1\nline2", code.code)
    }

    @Test
    fun `headings parsed with level and inline bold`() {
        val blocks = MarkdownRenderer.parse("## 📜 法规依据\n正文。\n### **核心**条款")
        val h2 = blocks[0] as MarkdownRenderer.Block.Heading
        assertEquals(2, h2.level)
        assertEquals("📜 法规依据", h2.segments.single().text)
        val h3 = blocks[2] as MarkdownRenderer.Block.Heading
        assertEquals(3, h3.level)
        assertEquals(MarkdownRenderer.Segment("核心", true), h3.segments[0])
    }

    @Test
    fun `thematic break becomes rule block`() {
        val blocks = MarkdownRenderer.parse("上段\n---\n下段")
        assertEquals(3, blocks.size)
        assertTrue(blocks[1] is MarkdownRenderer.Block.Rule)
    }

    // ---------------- 行内代码（S2 返工） ----------------

    @Test
    fun `inline code parsed as code segment`() {
        val blocks = MarkdownRenderer.parse("用 `gradlew test` 跑单测")
        val p = blocks.single() as MarkdownRenderer.Block.Paragraph
        assertEquals(
            listOf(
                MarkdownRenderer.Segment("用 ", false),
                MarkdownRenderer.Segment("gradlew test", false, true),
                MarkdownRenderer.Segment(" 跑单测", false),
            ),
            p.segments,
        )
    }

    @Test
    fun `unclosed inline code kept literal for streaming`() {
        val blocks = MarkdownRenderer.parse("命令是 `gradlew asse")
        val p = blocks.single() as MarkdownRenderer.Block.Paragraph
        assertEquals(listOf(MarkdownRenderer.Segment("命令是 `gradlew asse", false)), p.segments)
    }

    @Test
    fun `code span shields bold markers inside`() {
        val blocks = MarkdownRenderer.parse("写 `**不是粗体**` 即可")
        val p = blocks.single() as MarkdownRenderer.Block.Paragraph
        assertEquals(MarkdownRenderer.Segment("**不是粗体**", false, true), p.segments[1])
    }

    @Test
    fun `two code spans in one line`() {
        val blocks = MarkdownRenderer.parse("先 `adb install` 再 `adb shell` 即可")
        val p = blocks.single() as MarkdownRenderer.Block.Paragraph
        assertEquals(
            listOf(
                MarkdownRenderer.Segment("先 ", false),
                MarkdownRenderer.Segment("adb install", false, true),
                MarkdownRenderer.Segment(" 再 ", false),
                MarkdownRenderer.Segment("adb shell", false, true),
                MarkdownRenderer.Segment(" 即可", false),
            ),
            p.segments,
        )
    }

    // ---------------- GFM 表格（S2 返工） ----------------

    @Test
    fun `gfm table parsed with header and rows`() {
        val md = "| 名称 | 数量 |\n| --- | --- |\n| 档案盒 | 12 |\n| 档案柜 | 3 |"
        val table = MarkdownRenderer.parse(md).single() as MarkdownRenderer.Block.Table
        assertEquals(listOf("名称", "数量"), table.header.map { it.single().text })
        assertEquals(2, table.rows.size)
        assertEquals("档案柜", table.rows[1][0].single().text)
        assertEquals("3", table.rows[1][1].single().text)
    }

    @Test
    fun `table without outer pipes parsed`() {
        val md = "名称 | 数量\n--- | ---\n档案盒 | 12"
        val table = MarkdownRenderer.parse(md).single() as MarkdownRenderer.Block.Table
        assertEquals(2, table.header.size)
        assertEquals("12", table.rows[0][1].single().text)
    }

    @Test
    fun `pipe line without separator stays paragraph`() {
        // 流式安全：分隔行未到齐不猜表格（表头行先到时按普通段落渲染）
        val blocks = MarkdownRenderer.parse("a | b\n没有分隔行")
        assertTrue(blocks.single() is MarkdownRenderer.Block.Paragraph)
    }

    @Test
    fun `table row cell count normalized to header width`() {
        val md = "| a | b |\n| - | - |\n| 1 | 2 | 3 |\n| 4 |"
        val table = MarkdownRenderer.parse(md).single() as MarkdownRenderer.Block.Table
        assertEquals(2, table.rows[0].size) // 多出的列砍掉
        assertEquals("4", table.rows[1][0].single().text)
        assertTrue(table.rows[1][1].isEmpty()) // 缺的列补空（空单元格无行内片段）
    }

    @Test
    fun `table cells inline parsed`() {
        val md = "| 命令 | 说明 |\n| --- | --- |\n| `gradlew test` | **必跑** |"
        val table = MarkdownRenderer.parse(md).single() as MarkdownRenderer.Block.Table
        assertEquals(MarkdownRenderer.Segment("gradlew test", false, true), table.rows[0][0].single())
        assertEquals(MarkdownRenderer.Segment("必跑", true), table.rows[0][1].single())
    }

    // ---------------- 表格列宽提示（盲测「序号列过宽」返工） ----------------

    @Test
    fun `display units count cjk as two ascii as one`() {
        assertEquals(0, MarkdownRenderer.displayUnits(""))
        assertEquals(2, MarkdownRenderer.displayUnits("ab"))
        assertEquals(4, MarkdownRenderer.displayUnits("序号"))
        assertEquals(10, MarkdownRenderer.displayUnits("《档案法》"))
        assertEquals(4, MarkdownRenderer.displayUnits("a1档")) // 1+1+2
    }

    @Test
    fun `table carries per-column max width hints`() {
        val md = "| 序号 | 功能模块 |\n| --- | --- |\n| 1 | 法规条款查询 |\n| 22 | 指导 |"
        val table = MarkdownRenderer.parse(md).single() as MarkdownRenderer.Block.Table
        // 序号列：max(序号=4, 1, 22=2)=4；功能列：max(功能模块=8, 法规条款查询=12, 指导=4)=12
        assertEquals(listOf(4, 12), table.colMaxUnits)
    }

    @Test
    fun `table width hints treat missing cells as zero`() {
        val md = "| a | b |\n| - | - |\n| 长单元格 |"
        val table = MarkdownRenderer.parse(md).single() as MarkdownRenderer.Block.Table
        assertEquals(listOf(8, 1), table.colMaxUnits)
    }

    // ---------------- 引用块（S2 返工，盲测「裸 >」） ----------------

    @Test
    fun `quote lines grouped and inline parsed`() {
        val blocks = MarkdownRenderer.parse("前文\n> **免责声明**：仅供参考\n> 第二行\n后文")
        assertEquals(3, blocks.size)
        val q = blocks[1] as MarkdownRenderer.Block.Quote
        assertEquals(2, q.lines.size)
        assertEquals(MarkdownRenderer.Segment("免责声明", true), q.lines[0][0])
        assertTrue(blocks[2] is MarkdownRenderer.Block.Paragraph)
    }

    @Test
    fun `quote marker without space parsed`() {
        val blocks = MarkdownRenderer.parse(">直接引用")
        val q = blocks.single() as MarkdownRenderer.Block.Quote
        assertEquals("直接引用", q.lines.single().single().text)
    }

    // ---------------- 斜体（盲测 v4 返工：平台回复单星斜体星号原样漏出） ----------------

    @Test
    fun `italic inline parsed`() {
        val blocks = MarkdownRenderer.parse("这是 *斜体* 没错")
        val p = blocks.single() as MarkdownRenderer.Block.Paragraph
        assertEquals(
            listOf(
                MarkdownRenderer.Segment("这是 ", false),
                MarkdownRenderer.Segment("斜体", false, italic = true),
                MarkdownRenderer.Segment(" 没错", false),
            ),
            p.segments,
        )
    }

    @Test
    fun `bold italic nested via triple star`() {
        val blocks = MarkdownRenderer.parse("***粗斜*** 普通")
        val p = blocks.single() as MarkdownRenderer.Block.Paragraph
        assertEquals(MarkdownRenderer.Segment("粗斜", true, italic = true), p.segments[0])
        assertEquals(MarkdownRenderer.Segment(" 普通", false), p.segments[1])
    }

    @Test
    fun `unclosed italic kept literal for streaming`() {
        val blocks = MarkdownRenderer.parse("前半 *还没闭合")
        val p = blocks.single() as MarkdownRenderer.Block.Paragraph
        assertEquals(listOf(MarkdownRenderer.Segment("前半 *还没闭合", false)), p.segments)
    }

    @Test
    fun `bold takes priority over italic on same line`() {
        val blocks = MarkdownRenderer.parse("粗 **体** 与 *斜* 体")
        val p = blocks.single() as MarkdownRenderer.Block.Paragraph
        assertEquals(
            listOf(
                MarkdownRenderer.Segment("粗 ", false),
                MarkdownRenderer.Segment("体", true),
                MarkdownRenderer.Segment(" 与 ", false),
                MarkdownRenderer.Segment("斜", false, italic = true),
                MarkdownRenderer.Segment(" 体", false),
            ),
            p.segments,
        )
    }

    // ---------------- 表格灰字排查（盲测 v4：🏛️ 行灰淡） ----------------

    @Test
    fun `table row with surrogate emoji and vs16 keeps bold segments`() {
        // 取证结论：喂真机原文行，解析层 bold/文本完全正确（含代理对 emoji + VS16 不拆段），
        // 「灰字」根因在渲染层顶部淡出遮罩（见 ChatScreen.MessageList），非 emoji+VS16 拆解问题
        val md = "| 序号 | 功能模块 | 服务内容 |\n" +
            "| --- | --- | --- |\n" +
            "| **1** | ⚖️ **法规条款查询** | 实时解答 |\n" +
            "| **2** | 🏛️ **业务合规指导** | 移交进馆流程 |"
        val table = MarkdownRenderer.parse(md).single() as MarkdownRenderer.Block.Table
        val row2 = table.rows[1]
        assertEquals(listOf(MarkdownRenderer.Segment("2", true)), row2[0])
        assertEquals(
            listOf(
                MarkdownRenderer.Segment("🏛️ ", false),
                MarkdownRenderer.Segment("业务合规指导", true),
            ),
            row2[1],
        )
        assertEquals(listOf(MarkdownRenderer.Segment("移交进馆流程", false)), row2[2])
    }

    // ---------------- 非 ASCII 破折号分割线（S2 返工） ----------------

    @Test
    fun `non ascii dash run becomes rule block`() {
        val blocks = MarkdownRenderer.parse("上段\n———\n下段")
        assertEquals(3, blocks.size)
        assertTrue(blocks[1] is MarkdownRenderer.Block.Rule)
        // 双破折号整行（中文排版常见）同样归一为细线
        val two = MarkdownRenderer.parse("——")
        assertTrue(two.single() is MarkdownRenderer.Block.Rule)
    }

    @Test
    fun `inline em dash in prose stays text`() {
        // 行内破折号不是分割线（行首行尾锚定，不误伤正文）
        val blocks = MarkdownRenderer.parse("这是——重点")
        assertTrue(blocks.single() is MarkdownRenderer.Block.Paragraph)
    }
}
