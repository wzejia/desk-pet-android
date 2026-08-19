package com.xiaoduo.deskpet.chat

/**
 * Markdown 最小集解析（手实现，不引第三方库，R1）：
 * - 粗体 **x**（行内；流式未闭合的尾部 ** 按普通文本处理）
 * - 斜体 *x*（行内；** 优先配对，落单单星才按斜体；未闭合 * 按字面值保留）
 * - 行内代码 `x`（等宽+底色；code 段内不解析粗体，GFM 语义；未闭合 ` 按字面值保留）
 * - 无序列表：行首 "- " / "* " / "• "
 * - 有序列表：行首 "1. " / "2) " / "3、 "（保留原文编号）
 * - 围栏代码块 ``` ... ```（原样保留，UI 等宽+底色块；未闭合收到流尾即收尾）
 * - 标题 #..######（KD14 视觉补充：平台回复重度使用标题，剥 # 按层级加粗）
 * - 引用块 >（连续 > 行归并，逐行行内解析，UI 左侧竖条——盲测「裸 >」返工）
 * - GFM 表格 | a | b | + 分隔行（分隔行未到齐前不猜，按普通段落处理，流式安全）
 * - 分隔线 ---（整行 ≥3 个 - 或 *；平台中文回复实测的非 ASCII 破折号整行 ——/–– 同样渲染为细线）
 *
 * 输出为块序列，Compose 侧逐块渲染；纯 Kotlin 零 Android import，可 JVM 单测。
 */
object MarkdownRenderer {

    /** 行内片段：bold=加粗；italic=斜体；code=行内代码（等宽+底色，code 段内 bold/italic 不解析）。 */
    data class Segment(val text: String, val bold: Boolean, val code: Boolean = false, val italic: Boolean = false)

    /** 有序列表项：label 为原文编号（如 "1."），渲染时原样展示。 */
    data class OrderedItem(val label: String, val segments: List<Segment>)

    sealed interface Block {
        /** 普通段落（连续非特殊行以 \n 连接）。 */
        data class Paragraph(val segments: List<Segment>) : Block

        /** 无序列表。 */
        data class Bullets(val items: List<List<Segment>>) : Block

        /** 有序列表（保留原文编号）。 */
        data class Ordered(val items: List<OrderedItem>) : Block

        /** 围栏代码块（不含 ``` 行本身）。 */
        data class Code(val code: String) : Block

        /** 标题（level 1-6，segments 走行内解析）。 */
        data class Heading(val level: Int, val segments: List<Segment>) : Block

        /** 水平分隔线（--- / *** / 非 ASCII 破折号整行）。 */
        data object Rule : Block

        /** 引用块（> 行，逐行行内解析）。 */
        data class Quote(val lines: List<List<Segment>>) : Block

        /** GFM 表格：header 一行 + rows；单元格数量对齐 header（多砍少补空）。
         *  [colMaxUnits] 为派生列宽提示（不参与 equals），供 UI 做列宽分配
         *  （盲测「序号列过宽挤折中文词」返工）。 */
        data class Table(val header: List<List<Segment>>, val rows: List<List<List<Segment>>>) : Block {
            /** 各列单元格最大显示宽度（单位见 [displayUnits]）；缺列/空单元格计 0。 */
            val colMaxUnits: List<Int>
                get() = List(header.size) { idx ->
                    (
                        sequenceOf(header.getOrNull(idx)) +
                            rows.asSequence().map { it.getOrNull(idx) }
                        )
                        .filterNotNull()
                        .maxOf { cell -> displayUnits(cell.joinToString("") { it.text }) }
                }
        }
    }

    /**
     * 显示宽度估算：码点 ≥ 0x2E80（CJK/全角/emoji 及代理对半区）计 2，其余计 1。
     * 粗略启发式（emoji 按代理对计 4），仅供列宽相对比较，不做精确排版。
     */
    fun displayUnits(s: String): Int = s.fold(0) { acc, c -> acc + if (c.code >= 0x2E80) 2 else 1 }

    private val BULLET = Regex("^\\s*[-*•]\\s+(.*)$")
    private val ORDERED = Regex("^\\s*(\\d+[.、)])\\s+(.*)$")
    private val HEADING = Regex("^\\s*(#{1,6})\\s+(.*)$")
    private val RULE = Regex("^\\s*(-{3,}|\\*{3,}|[—–―]{2,})\\s*$")
    private val QUOTE = Regex("^\\s*>\\s?(.*)$")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

    fun parse(text: String): List<Block> {
        val lines = text.split("\n")
        val blocks = ArrayList<Block>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trimStart().startsWith("```") -> {
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        if (code.isNotEmpty()) code.append("\n")
                        code.append(lines[i])
                        i++
                    }
                    if (i < lines.size) i++ // 跳过收尾 ```（流式未闭合则自然到 EOF）
                    blocks.add(Block.Code(code.toString()))
                }

                QUOTE.matches(line) -> {
                    val quoteLines = ArrayList<List<Segment>>()
                    while (i < lines.size) {
                        val m = QUOTE.matchEntire(lines[i]) ?: break
                        quoteLines.add(parseInline(m.groupValues[1]))
                        i++
                    }
                    blocks.add(Block.Quote(quoteLines))
                }

                isTableStart(lines, i) -> {
                    val header = splitRow(lines[i])
                    i += 2 // 表头 + 分隔行
                    val rawRows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].isNotBlank() && lines[i].contains("|")) {
                        rawRows.add(splitRow(lines[i]))
                        i++
                    }
                    val width = header.size
                    fun norm(cells: List<String>): List<String> =
                        List(width) { idx -> cells.getOrElse(idx) { "" } }
                    blocks.add(
                        Block.Table(
                            header = norm(header).map { parseInline(it) },
                            rows = rawRows.map { row -> norm(row).map { parseInline(it) } },
                        ),
                    )
                }

                BULLET.matches(line) -> {
                    val items = ArrayList<List<Segment>>()
                    while (i < lines.size) {
                        val m = BULLET.matchEntire(lines[i]) ?: break
                        items.add(parseInline(m.groupValues[1]))
                        i++
                    }
                    blocks.add(Block.Bullets(items))
                }

                ORDERED.matches(line) -> {
                    val items = ArrayList<OrderedItem>()
                    while (i < lines.size) {
                        val m = ORDERED.matchEntire(lines[i]) ?: break
                        items.add(OrderedItem(m.groupValues[1], parseInline(m.groupValues[2])))
                        i++
                    }
                    blocks.add(Block.Ordered(items))
                }

                HEADING.matches(line) -> {
                    val m = HEADING.matchEntire(line)!!
                    blocks.add(Block.Heading(m.groupValues[1].length, parseInline(m.groupValues[2])))
                    i++
                }

                RULE.matches(line) -> {
                    blocks.add(Block.Rule)
                    i++
                }

                line.isBlank() -> i++

                else -> {
                    val para = StringBuilder(line)
                    i++
                    while (i < lines.size &&
                        lines[i].isNotBlank() &&
                        !lines[i].trimStart().startsWith("```") &&
                        !QUOTE.matches(lines[i]) &&
                        !isTableStart(lines, i) &&
                        !BULLET.matches(lines[i]) &&
                        !ORDERED.matches(lines[i]) &&
                        !HEADING.matches(lines[i]) &&
                        !RULE.matches(lines[i])
                    ) {
                        para.append("\n").append(lines[i])
                        i++
                    }
                    blocks.add(Block.Paragraph(parseInline(para.toString())))
                }
            }
        }
        return blocks
    }

    /** 表格起点：本行含 | 且下一行是 GFM 分隔行（分隔行未到齐不猜，流式安全）。 */
    private fun isTableStart(lines: List<String>, i: Int): Boolean =
        lines[i].contains("|") && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1])

    /** 拆表格行：去首尾 | 后按 | 切、单元格 trim；不支持转义 \|（最小集）。 */
    private fun splitRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && s.isNotEmpty()) s = s.dropLast(1)
        return s.split("|").map { it.trim() }
    }

    /**
     * 行内解析：行内代码优先（反引号段内不解析粗体，GFM 语义）；未闭合 ` 与空 code
     * span（``）按字面值保留（流式中途不吞字符，补齐后自然成对）。
     */
    fun parseInline(s: String): List<Segment> {
        if (!s.contains("`")) return parseBold(s)
        val out = ArrayList<Segment>()
        val buf = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '`') {
                val close = s.indexOf('`', i + 1)
                if (close > i + 1) {
                    out.addAll(parseBold(buf.toString()))
                    buf.clear()
                    var code = s.substring(i + 1, close)
                    // GFM：code 两端都是空格且非全空格时剥一层
                    if (code.length > 1 && code.startsWith(" ") && code.endsWith(" ") && code.isNotBlank()) {
                        code = code.substring(1, code.length - 1)
                    }
                    out.add(Segment(code, bold = false, code = true))
                    i = close + 1
                } else {
                    buf.append(s[i])
                    i++
                }
            } else {
                buf.append(s[i])
                i++
            }
        }
        out.addAll(parseBold(buf.toString()))
        return out
    }

    /**
     * 行内粗体/斜体：** 优先配对（粗体），落单的 * 再按斜体配对；两者都要求「后面还有
     * 闭合标记」才切换样式态，无闭合的 ** 与 * 按字面值保留（流式中途不吞字符，补齐后
     * 自然成对）。未通过闭合检查的 ** 整体按字面值消费，不降级成两次斜体切换。
     * ***x*** = 粗斜嵌套（先 ** 后 *，收尾对称）。
     */
    private fun parseBold(s: String): List<Segment> {
        if (!s.contains('*')) return if (s.isEmpty()) emptyList() else listOf(Segment(s, false))
        val out = ArrayList<Segment>()
        val buf = StringBuilder()
        var bold = false
        var italic = false
        var i = 0
        fun flush() {
            if (buf.isNotEmpty()) {
                out.add(Segment(buf.toString(), bold, italic = italic))
                buf.clear()
            }
        }
        while (i < s.length) {
            if (s.startsWith("**", i)) {
                if (bold || s.indexOf("**", i + 2) >= 0) {
                    flush()
                    bold = !bold
                } else {
                    buf.append("**") // 无闭合的 ** 整体字面值，不拆成两个斜体标记
                }
                i += 2
            } else if (s[i] == '*') {
                if (italic || s.indexOf('*', i + 1) >= 0) {
                    flush()
                    italic = !italic
                } else {
                    buf.append(s[i])
                }
                i++
            } else {
                buf.append(s[i])
                i++
            }
        }
        flush()
        return out
    }
}
