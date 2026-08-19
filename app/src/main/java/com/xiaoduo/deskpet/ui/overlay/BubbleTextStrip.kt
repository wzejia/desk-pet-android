package com.xiaoduo.deskpet.ui.overlay

/**
 * 气泡文本样式模型（S7-c：BubbleTextStrip 从「剥离输出纯文本」反转为「输出带样式模型」）。
 *
 * 纯 Kotlin 零 Android import（JVM 单测直接跑；android.text.SpannableString 在本地
 * 单测不可用，故样式用本模型承载，Android 侧由 BubbleView 转 SpannableString）。
 */
enum class Type { BOLD, ITALIC, CODE }

/** [start, end) 为 [StyledText.text] 内的半开区间。 */
data class Span(val type: Type, val start: Int, val end: Int)

data class StyledText(val text: String, val spans: List<Span> = emptyList()) {

    /** 打字机逐字切片：取前 [n] 字，span 截断到 n 内（越界端裁剪、完全在外的丢弃）。 */
    fun prefix(n: Int): StyledText {
        if (n <= 0) return StyledText("", emptyList())
        if (n >= text.length) return this
        return StyledText(
            text.substring(0, n),
            spans.mapNotNull { sp ->
                val end = minOf(sp.end, n)
                if (sp.start < end) sp.copy(end = end) else null
            },
        )
    }

    /** 去掉前 [n] 字（流式差分取尾巴用）；剩余 span 区间整体前移。 */
    fun dropStart(n: Int): StyledText {
        if (n <= 0) return this
        if (n >= text.length) return StyledText("", emptyList())
        return StyledText(
            text.substring(n),
            spans.mapNotNull { sp ->
                if (sp.end <= n) null
                else sp.copy(start = maxOf(sp.start - n, 0), end = sp.end - n)
            },
        )
    }

    /** 流式续打拼接：other 的 span 区间偏移到本文本末尾之后。 */
    operator fun plus(other: StyledText): StyledText {
        if (other.text.isEmpty()) return this
        if (text.isEmpty()) return other
        val base = text.length
        return StyledText(
            text + other.text,
            spans + other.spans.map { sp -> sp.copy(start = sp.start + base, end = sp.end + base) },
        )
    }
}

/**
 * 气泡文本 markdown 渲染（真机取证 s5-voice-round：平台回复重度使用 markdown，
 * BubbleView 纯文本打字机把 `**粗体**`、表格 `|`、反引号等标记原样裸露；
 * S7-c 由「剥离」反转为「渲染」：基础集产 span，表格/代码块/多级标题保持降级语义不渲染）。
 *
 * 纯 Kotlin 零 Android import，可 JVM 单测。两个入口：
 * - [render]：一次性全量渲染（纯函数），输出 [StyledText]。
 * - [Session]：流式增量（打字机逐字 append 场景）。内部缓冲全量 raw，
 *   每次重算「安全前缀」的渲染结果并前缀差分吐出新增部分：
 *   最后一个完整标记对之后的残余疑似标记（半截 `**粗`、未闭合反引号、
 *   疑似表格分隔行/标题/引用前缀的标记行）先 hold 不输出，等后续 delta 补齐，
 *   既不裸吐 `**` 也不吞内容。前缀稳定由设计不变式保证（渲染规则只依赖
 *   已固化字符、holdback 点只前进不后退）；万一被打破（陈旧 runnable 污染），
 *   走 reset 全量重打兜底（[Emit.reset]）。
 *
 * 渲染规则（与 chat/MarkdownRenderer 最小集对齐的气泡版）：
 * - `**粗体**`→BOLD span、`*斜体*`→ITALIC span、`` `代码` ``→CODE span（标记字符去掉）。
 *   粗体允许数字开头（**5星**）；斜体要求开标记前非字母数字、后非空白非数字、
 *   闭合≤24 字（保数学 2*3 / a*b 字面值）。
 *   空 code span（`` `` `` 两个紧邻反引号）剥掉两个标记不吐内容（空内容无语义，
 *   且防流式 hold 在首反引号处永久冻结后续输出）。
 *   流式反引号 hold 上限 48 字（体例同斜体 24 字封顶）：残缺/孤立反引号超限后
 *   丢标记留内容放行，防 LLM 半截反引号把打字机尾巴永久冻结；48 覆盖常见
 *   路径/命令/标识符行内码（比斜体长因 code 内容天然更长）。
 * - 列表行 `- `/`* ` 前缀→行首「• 」。
 * - 表格行：含 `|` 的行把管道符换成空格再压缩（| a | b | → a b）；GFM 分隔行整行跳过
 *   （表格本身不渲染，仅降级为可读纯文本）。
 * - 代码块围栏 ```（S8-4）：开围栏行（含 ```python 语言标签行）整行吞掉，闭围栏 ``` 独占行
 *   吞掉；块内内容行仍走原行级/行内管线降级（不原样透传，防代码内控制符字面裸显）。
 *   修复前围栏零处理：```python 经行内反引号规则剥成游离「python」文字行裸显（错位/残留）。
 * - 标题 `#`（S8-4 起空白可选：`##标题` 无空格形态同样剥除）、引用 `>` 前缀剥掉；
 *   规则线（---/***/破折号整行）整行跳过。
 * - 多余空白压缩：行内连续空白并一格、空行全部丢弃（气泡里段落间距无意义）。
 *   行首尾压缩先于行内渲染执行、标记剥除残留的空腔在行内渲染时折叠，
 *   span 区间基于压缩后的最终文本计算。
 * - 一次性全量下：未闭合 `**`/反引号丢标记留内容（**粗 → 粗，不产 span）；
 *   未闭合 `*` 字面值保留。
 */
object BubbleTextStrip {

    /** 一次吐出的增量：reset=false 时 [text] 为新增尾巴（打字机续打）；
     *  reset=true 为防御兜底，[text] 是渲染后全文（调用方应全量重打）。 */
    data class Emit(val text: StyledText, val reset: Boolean)

    /** 斜体闭合 span 上限（字符）；与流式 hold 上限一致，保证「超上限按字面值」判定确定。 */
    private const val ITALIC_MAX = 24

    /** 反引号流式 hold 上限（字符）：超限按「丢标记留内容」放行（与一次性渲染的未闭合规则一致）。 */
    private const val BACKTICK_MAX = 48

    private val RULE = Regex("^\\s*(-{3,}|\\*{3,}|[—–―]{2,})\\s*$")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$")
    // S8-4：标题空白改可选（##标题 无空格形态同样剥除，行首 # 串不裸显；
    // 权衡：行首 #tag/#1 会被剥首字符，气泡场景远低于无空格标题命中率）
    private val HEADING = Regex("^\\s*#{1,6}\\s*")
    private val QUOTE = Regex("^\\s*>+\\s?")
    private val LIST_ITEM = Regex("^\\s*[-*]\\s+")
    // S8-4：代码块围栏。开围栏行（含 ```python 语言标签）整行吞；闭围栏仅 ``` 独占行。
    private val FENCE_OPEN = Regex("^\\s*`{3,}.*")
    private val FENCE_CLOSE = Regex("^\\s*`{3,}\\s*")
    private val WS = Regex("[ \\t]+")
    /** 只由标记字符组成的行（流式中可能是半截表格分隔行/标题/引用/规则线/标记对），先 hold。 */
    private val MARKER_ONLY = Regex("^[#>*|\\-:`\\s]+$")

    /** 一次性全量渲染（纯函数）。 */
    fun render(text: String): StyledText {
        if (text.isEmpty()) return StyledText("")
        val out = StringBuilder()
        val spans = ArrayList<Span>()
        // S8-4：代码块围栏状态。块内内容行不原样透传、仍走原行级/行内管线降级——
        // 防代码内 |/*/# 字面裸显违反「气泡无裸控制符」红线（围栏行本身整行吞）。
        // 前缀稳定：fence 状态只依赖行序列中更早的已固化行，流式重算不回头。
        var inFence = false
        for (line0 in text.split('\n')) {
            var line = line0
            if (!inFence && FENCE_OPEN.matches(line)) { inFence = true; continue }
            if (inFence && FENCE_CLOSE.matches(line)) { inFence = false; continue }
            if (RULE.matches(line)) continue
            if (TABLE_SEP.matches(line)) continue
            line = line.replace(HEADING, "")
            while (true) {
                val m = QUOTE.find(line) ?: break
                line = line.substring(m.value.length)
            }
            if (line.contains('|')) line = line.replace('|', ' ')
            line = line.replace(LIST_ITEM, "• ")
            // 空白压缩先于行内渲染：span 区间基于最终文本计算
            line = line.replace(WS, " ").trim()
            if (line.isEmpty()) continue
            val styled = renderInline(line)
            if (out.isNotEmpty()) out.append('\n')
            appendStyled(out, spans, styled, wrap = null)
        }
        return StyledText(out.toString(), spans)
    }

    /** 把 inner 并入输出：text 追加、span 偏移；wrap 非空时整体再包一层 span（闭合标记对内）。 */
    private fun appendStyled(
        out: StringBuilder,
        spans: MutableList<Span>,
        inner: StyledText,
        wrap: Type?,
    ) {
        val base = out.length
        out.append(inner.text)
        for (sp in inner.spans) spans.add(sp.copy(start = sp.start + base, end = sp.end + base))
        if (wrap != null && inner.text.isNotEmpty()) spans.add(Span(wrap, base, out.length))
    }

    /** 行内渲染：反引号优先（code 段内容原样不递归），再 `**`，最后单 `*`。 */
    private fun renderInline(s: String): StyledText {
        val out = StringBuilder()
        val spans = ArrayList<Span>()
        var i = 0
        while (i < s.length) {
            when {
                s[i] == '`' -> {
                    val close = s.indexOf('`', i + 1)
                    when {
                        close > i + 1 -> {
                            var code = s.substring(i + 1, close)
                            if (code.length > 1 && code.startsWith(" ") && code.endsWith(" ") && code.isNotBlank()) {
                                code = code.substring(1, code.length - 1)
                            }
                            val start = out.length
                            out.append(code)
                            spans.add(Span(Type.CODE, start, out.length))
                            i = close + 1
                        }
                        close == i + 1 -> {
                            // 空 code span（``）：剥掉两个标记不吐内容（与流式 holdback 同语义）
                            i = close + 1
                        }
                        else -> {
                            // 未闭合反引号：丢标记留内容不产 span（流式场景已被 holdback 拦截）
                            out.append(s.substring(i + 1))
                            i = s.length
                        }
                    }
                }

                s.startsWith("**", i) -> {
                    val after = s.getOrNull(i + 2)
                    if (after != null && !after.isWhitespace()) {
                        val close = s.indexOf("**", i + 2)
                        if (close >= 0) {
                            appendStyled(out, spans, renderInline(s.substring(i + 2, close)), Type.BOLD)
                            i = close + 2
                        } else {
                            // 未闭合粗体：丢标记留内容不产 span（流式场景已被 holdback 拦截）
                            appendStyled(out, spans, renderInline(s.substring(i + 2)), wrap = null)
                            i = s.length
                        }
                    } else {
                        out.append("**"); i += 2
                    }
                }

                s[i] == '*' -> {
                    val prevOk = i == 0 || !s[i - 1].isLetterOrDigit()
                    val after = s.getOrNull(i + 1)
                    val afterOk = after != null && !after.isWhitespace() && !after.isDigit()
                    val close = if (prevOk && afterOk) findItalicClose(s, i) else -1
                    if (close >= 0) {
                        appendStyled(out, spans, renderInline(s.substring(i + 1, close)), Type.ITALIC)
                        i = close + 1
                    } else {
                        // 未闭合/不合规单星：字面值保留（数学 2*3、a*b 安全）
                        out.append('*'); i++
                    }
                }

                else -> {
                    // 标记剥除可能残留相邻空格（如「见 `` 与」）：行内折叠为单空格
                    // （输入行已预压缩+trim，此处只处理剥除产生的空腔）
                    if (s[i] == ' ' && (out.isEmpty() || out.last() == ' ')) {
                        i++
                    } else {
                        out.append(s[i]); i++
                    }
                }
            }
        }
        return StyledText(out.toString(), spans)
    }

    /** 斜体闭合星：前字符非空白非数字、距开标记 ≤ITALIC_MAX（与流式 hold 上限同阈值）。 */
    private fun findItalicClose(s: String, opener: Int): Int {
        val upper = minOf(s.length - 1, opener + ITALIC_MAX)
        var j = opener + 1
        while (j <= upper) {
            if (s[j] == '*' && !s[j - 1].isWhitespace() && !s[j - 1].isDigit()) return j
            j++
        }
        return -1
    }

    /**
     * 流式 holdback 点：返回 raw 中应开始 hold 的下标（含），-1 表示无 hold。
     * 只看最后一行（行级标记不跨行；行内配对也不跨行，跨行标记由一次性渲染的
     * 「丢标记留内容」规则兜住，且与 hold 释放后的输出一致）。
     */
    private fun findHoldback(raw: String): Int {
        val lineStart = raw.lastIndexOf('\n') + 1
        val lastLine = raw.substring(lineStart)
        if (lastLine.isNotEmpty() && MARKER_ONLY.matches(lastLine)) return lineStart
        var i = 0
        while (i < lastLine.length) {
            when {
                lastLine[i] == '`' -> {
                    val close = lastLine.indexOf('`', i + 1)
                    when {
                        close > i + 1 -> i = close + 1
                        // 空 code span（``）：视作完整标记对直接跳过，不 hold
                        close == i + 1 -> i = close + 1
                        // 反引号未闭合：上限内先 hold；超上限丢标记留内容放行继续扫
                        // （防残缺反引号把打字机尾巴永久冻结，体例同斜体 ITALIC_MAX）
                        raw.length - (lineStart + i) <= BACKTICK_MAX -> return lineStart + i
                        else -> i++
                    }
                }

                lastLine.startsWith("**", i) -> {
                    val after = lastLine.getOrNull(i + 2)
                    if (after == null || !after.isWhitespace()) {
                        val close = if (after == null) -1 else lastLine.indexOf("**", i + 2)
                        if (close >= 0) i = close + 2 else return lineStart + i
                    } else {
                        i += 2
                    }
                }

                lastLine[i] == '*' -> {
                    val prevOk = i == 0 || !lastLine[i - 1].isLetterOrDigit()
                    val after = lastLine.getOrNull(i + 1)
                    val afterOk = after == null || (!after.isWhitespace() && !after.isDigit())
                    if (prevOk && afterOk) {
                        val close = if (after == null) -1 else findItalicClose(lastLine, i)
                        when {
                            close >= 0 -> i = close + 1
                            // 斜体未闭合：上限内先 hold；超上限按字面值放行继续扫
                            // （防数学/孤立单星把打字机尾巴永久冻结）
                            raw.length - (lineStart + i) <= ITALIC_MAX -> return lineStart + i
                            else -> i++
                        }
                    } else {
                        i++
                    }
                }

                else -> i++
            }
        }
        return -1
    }

    /** 流式增量会话：每个气泡 round 一个实例（showBubble 全量重置时重建）。 */
    class Session {
        private val raw = StringBuilder()
        private var emitted = StyledText("")

        fun feed(delta: String): Emit {
            raw.append(delta)
            val s = raw.toString()
            val h = findHoldback(s)
            val safe = if (h >= 0) s.substring(0, h) else s
            val rendered = render(safe)
            return if (rendered.prefix(emitted.text.length) == emitted) {
                // 前缀稳定（文本与 span 区间均一致）：只吐新增尾巴
                val tail = rendered.dropStart(emitted.text.length)
                emitted = rendered
                Emit(tail, reset = false)
            } else {
                // 防御兜底：设计不变式（前缀稳定）保证不走；陈旧 runnable 污染时全量重打
                emitted = rendered
                Emit(rendered, reset = true)
            }
        }
    }
}
