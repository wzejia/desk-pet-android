package com.xiaoduo.deskpet.ui.components

import com.xiaoduo.deskpet.chat.MarkdownRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 变异锚（审计 ocr[310]，2026-10-03 全量审计）：TableBlock 原实现对 colMaxUnits（计算属性，
 * 每次访问全量重扫 H 列×(R+1) 行并分配连接串）逐格访问 → 每次重组 O(H·(R+1)) 次重测，
 * 流式逐 token 重组放大为卡顿源。缓存判定：同 block 内容只测一次，内容变才重测。
 * 判定被删（unitsFor 直通 measure）→ 「同内容不重测」用例红。
 */
class TableColumnMeasureCacheTest {

    private class CountingMeasure(private val units: List<Int>) {
        var calls: Int = 0
        fun measure(): List<Int> {
            calls++
            return units
        }
    }

    @Test
    fun `first access measures exactly once and returns value`() {
        val cache = TableColumnMeasureCache()
        val m = CountingMeasure(listOf(2, 8, 24))
        assertEquals(listOf(2, 8, 24), cache.unitsFor("table-a", m::measure))
        assertEquals(1, m.calls)
    }

    @Test
    fun `same content key reuses cached measurement without re-measuring`() {
        val cache = TableColumnMeasureCache()
        val block = tableBlock()
        val m = CountingMeasure(block.colMaxUnits)
        val first = cache.unitsFor(block, m::measure)
        // 同帧内逐格/跨行再访问（原实现的 O(H·(R+1)) 重扫形态）
        val second = cache.unitsFor(block, m::measure)
        assertSame(first, second)
        assertEquals(1, m.calls)
    }

    @Test
    fun `content change re-measures exactly once with fresh value`() {
        val cache = TableColumnMeasureCache()
        val m1 = CountingMeasure(listOf(4, 6))
        val m2 = CountingMeasure(listOf(8, 24))
        assertEquals(listOf(4, 6), cache.unitsFor("stream-token-1", m1::measure))
        assertEquals(listOf(8, 24), cache.unitsFor("stream-token-2", m2::measure))
        assertEquals(1, m1.calls)
        assertEquals(1, m2.calls)
    }

    @Test
    fun `single slot holds latest key only`() {
        val cache = TableColumnMeasureCache()
        val mA = CountingMeasure(listOf(2))
        val mB = CountingMeasure(listOf(9))
        cache.unitsFor("A", mA::measure) // mA.calls=1，槽=A
        cache.unitsFor("B", mB::measure) // mB.calls=1，槽=B
        cache.unitsFor("A", mA::measure) // 回到旧键：单槽已被 B 占用 → 重测（记录容量语义）
        assertEquals(2, mA.calls)
        assertEquals(1, mB.calls)
    }

    @Test
    fun `cached units equal fresh colMaxUnits for parsed table`() {
        // 同输入同输出：缓存判定返回值 == 每格访问 getter 的逐格值（渲染逐像素等价前提）
        val text = "| 功能模块 | 序号 | 说明列内容较长用于撑大权重 |\n" +
            "| --- | --- | --- |\n" +
            "| 1 | 2 | 中文单元格若干字 |\n" +
            "| 打开 | 3 | 另一行更长一点的说明文本 |\n"
        val block = MarkdownRenderer.parse(text)
            .filterIsInstance<MarkdownRenderer.Block.Table>()
            .first()
        val cache = TableColumnMeasureCache()
        val units = cache.unitsFor(block) { block.colMaxUnits }
        assertEquals(block.header.size, units.size)
        assertEquals(block.colMaxUnits, units)
        // 缺列回退语义不变：越界列取 8（与渲染面 getOrElse(idx){8} 同源）
        assertEquals(8, units.getOrElse(units.size) { 8 })
    }

    private fun tableBlock(): MarkdownRenderer.Block.Table =
        MarkdownRenderer.parse("| a | b |\n| --- | --- |\n| 1 | 2 |\n")
            .filterIsInstance<MarkdownRenderer.Block.Table>()
            .first()
}
