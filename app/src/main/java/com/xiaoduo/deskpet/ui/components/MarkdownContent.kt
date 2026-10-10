package com.xiaoduo.deskpet.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoduo.deskpet.chat.MarkdownRenderer
import com.xiaoduo.deskpet.ui.theme.XiaoduoDimens

/**
 * Markdown 最小集渲染（2026-08-24 从 ChatScreen 抽取共享：聊天页消息泡 + S14 全屏字幕两用）。
 * 逐块渲染 [MarkdownRenderer] 输出：段落/列表/代码块/标题/分割线/引用/表格（行内代码等宽+底色）。
 *
 * 样式参数默认值 = 聊天页原样（bodyLarge 24sp / bodyMedium 20sp / onSurface）；
 * 全屏字幕传小一号样式与 TranscriptColor 即得紧凑观感，零分叉维护。
 */
@Composable
fun MarkdownContent(
    text: String,
    bodyStyle: TextStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
    smallStyle: TextStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(text) { MarkdownRenderer.parse(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(XiaoduoDimens.SpaceS)) {
        for (block in blocks) {
            when (block) {
                is MarkdownRenderer.Block.Paragraph -> Text(
                    text = styledText(block.segments, codeBg),
                    style = bodyStyle,
                    color = contentColor,
                )

                is MarkdownRenderer.Block.Bullets -> Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (item in block.items) {
                        Row {
                            Text("•  ", style = bodyStyle, color = contentColor)
                            Text(
                                text = styledText(item, codeBg),
                                style = bodyStyle,
                                color = contentColor,
                            )
                        }
                    }
                }

                is MarkdownRenderer.Block.Ordered -> Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (item in block.items) {
                        Row {
                            Text("${item.label} ", style = bodyStyle, color = contentColor)
                            Text(
                                text = styledText(item.segments, codeBg),
                                style = bodyStyle,
                                color = contentColor,
                            )
                        }
                    }
                }

                is MarkdownRenderer.Block.Code -> {
                    val codeScroll = rememberScrollState()
                    Box {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = block.code,
                                modifier = Modifier
                                    .horizontalScroll(codeScroll)
                                    .padding(12.dp),
                                style = smallStyle,
                                fontFamily = FontFamily.Monospace,
                                softWrap = false, // 等宽不换行，长行走横向滚动（盲测「代码块硬截断」返工）
                                color = contentColor,
                            )
                        }
                        // 右缘渐变淡出：内容超宽且未滚到底时提示可横滑；滚到底自然消失。
                        // matchParentSize 让遮罩拿到 Box 实测尺寸（有界），几何确定、不依赖运行时状态
                        // （fillMaxHeight 在 LazyColumn 无界测量下是 no-op，曾致渐变 48dp×0dp 从未渲染）。
                        if (codeScroll.maxValue > 0 && codeScroll.value < codeScroll.maxValue) {
                            Box(modifier = Modifier.matchParentSize()) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .fillMaxHeight()
                                        .width(48.dp)
                                        .background(
                                            // 48dp 宽 + 终点 onSurface 20% 暗纱（12% 实测渲染但人眼
                                            // 不可辨，盲测 v4「无任何提示」返工）
                                            Brush.horizontalGradient(
                                                listOf(
                                                    Color.Transparent,
                                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                                ),
                                            ),
                                            RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
                                        ),
                                )
                                // 右向 chevron：静态截图一眼可辨「右侧还有内容」（渐变单扛不够显著）
                                Text(
                                    text = "›",
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .padding(end = 6.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                )
                            }
                        }
                    }
                }

                is MarkdownRenderer.Block.Heading -> Text(
                    text = styledText(block.segments, codeBg),
                    style = (if (block.level <= 2) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        bodyStyle
                    }).copy(fontWeight = FontWeight.Bold),
                    color = contentColor,
                )

                is MarkdownRenderer.Block.Quote -> QuoteBlock(block, codeBg, bodyStyle, contentColor)

                is MarkdownRenderer.Block.Table -> TableBlock(block, codeBg, smallStyle, contentColor)

                MarkdownRenderer.Block.Rule -> HorizontalDivider(
                    // 消息流分隔线再内缩一档，不与卡片/屏幕边缘取齐（盲测「分隔线顶边」返工）
                    modifier = Modifier.padding(horizontal = XiaoduoDimens.SpaceM),
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** 引用块：左侧细竖条 + 缩进正文（盲测「裸 >」返工，ChatGPT 同款灰条引用）。 */
@Composable
private fun QuoteBlock(
    block: MarkdownRenderer.Block.Quote,
    codeBg: Color,
    bodyStyle: TextStyle,
    contentColor: Color,
) {
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
        )
        Column(
            modifier = Modifier.padding(start = XiaoduoDimens.SpaceS),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (line in block.lines) {
                Text(
                    text = styledText(line, codeBg),
                    style = bodyStyle,
                    color = contentColor,
                )
            }
        }
    }
}

/** GFM 表格：细边框卡片 + 表头加粗 + 头体分隔线；列宽按内容提示分配（见 [tableColumnModifier]）。 */
@Composable
private fun TableBlock(
    block: MarkdownRenderer.Block.Table,
    codeBg: Color,
    smallStyle: TextStyle,
    contentColor: Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // 列宽测量缓存（审计 ocr[310]）：colMaxUnits 是计算属性（每访问全量重扫 H·(R+1) 行列），
            // 原实现逐格访问 → 每次重组 O(H·(R+1)) 次重测；收敛为每内容变更只测一次
            // （key=block 内容 equals，重组/滚动零重测）。判定逻辑抽 TableColumnMeasureCache（变异锚测试）。
            val colUnits = remember { TableColumnMeasureCache() }.unitsFor(block) { block.colMaxUnits }
            Row(horizontalArrangement = Arrangement.spacedBy(XiaoduoDimens.SpaceS)) {
                block.header.forEachIndexed { idx, cell ->
                    Text(
                        text = styledText(cell, codeBg),
                        modifier = tableColumnModifier(colUnits.getOrElse(idx) { 8 }),
                        style = smallStyle.copy(fontWeight = FontWeight.Bold),
                        color = contentColor,
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.outline,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (row in block.rows) {
                    Row(horizontalArrangement = Arrangement.spacedBy(XiaoduoDimens.SpaceS)) {
                        row.forEachIndexed { idx, cell ->
                            Text(
                                text = styledText(cell, codeBg),
                                modifier = tableColumnModifier(colUnits.getOrElse(idx) { 8 }),
                                style = smallStyle,
                                color = contentColor,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 表格列宽策略（盲测「序号列过宽、功能模块列挤折中文词」返工）：
 * - 内容极短列（≤6 显示单位，如序号/数字列）按内容固定小宽，不参与权重分配；
 * - 其余列按内容最大宽度做权重，并压缩动态范围到 14..24：
 *   下限保证短词列（如 6 字词组）整词不折，上限防长文列挤窄邻列。
 * 显示单位定义见 [MarkdownRenderer.displayUnits]（CJK 计 2，1 单位 ≈ 7dp @bodyMedium）。
 */
private fun RowScope.tableColumnModifier(units: Int): Modifier =
    if (units <= 6) {
        Modifier.width((units * 7 + 10).dp)
    } else {
        Modifier.weight(units.coerceIn(14, 24).toFloat())
    }

/** 行内样式合成：粗体/斜体/行内代码（等宽+底色）→ AnnotatedString。 */
private fun styledText(segments: List<MarkdownRenderer.Segment>, codeBg: Color): AnnotatedString =
    buildAnnotatedString {
        for (seg in segments) {
            val style = SpanStyle(
                fontWeight = if (seg.bold) FontWeight.Bold else null,
                fontStyle = if (seg.italic) FontStyle.Italic else null,
                fontFamily = if (seg.code) FontFamily.Monospace else null,
                background = if (seg.code) codeBg else Color.Unspecified,
            )
            withStyle(style) { append(seg.text) }
        }
    }
