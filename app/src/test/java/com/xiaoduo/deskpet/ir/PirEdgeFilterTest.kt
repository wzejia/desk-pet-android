package com.xiaoduo.deskpet.ir

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S70 PirEdgeFilter 纯逻辑测试（零 Android import，JVM 可测）。
 * 时钟由调用方注入：用毫秒数推进模拟时间；轮询间隔 200ms 用 +200 步进贴合真实节奏。
 */
class PirEdgeFilterTest {

    // ---- 基础上升沿：连续 2 样本去抖 ----

    @Test
    fun `连续2个true样本确认上升沿触发`() {
        val f = PirEdgeFilter()
        val t0 = 1_000_000L
        assertFalse(f.onSample(false, t0))        // 稳定低
        assertFalse(f.onSample(true, t0 + 200))   // 候选 1（单样本不触发）
        assertTrue(f.onSample(true, t0 + 400))    // 候选 2 → 触发
    }

    @Test
    fun `首样本true单样本不触发`() {
        val f = PirEdgeFilter()
        assertFalse(f.onSample(true, 0L))
    }

    // ---- 去抖反例：单样本毛刺 ----

    @Test
    fun `单样本毛刺不触发且不污染稳定态`() {
        val f = PirEdgeFilter()
        val t0 = 1_000_000L
        assertFalse(f.onSample(false, t0))
        assertFalse(f.onSample(true, t0 + 200))   // 毛刺
        assertFalse(f.onSample(false, t0 + 400))  // 回落
        // 毛刺后稳定态仍为 false：后续正常上升沿照常触发
        assertFalse(f.onSample(true, t0 + 600))
        assertTrue(f.onSample(true, t0 + 800))
    }

    // ---- 冷却窗（S70 变异锚 A：删冷却检查 → 本用例红）----

    @Test
    fun `冷却期内完整边沿往返不触发`() {
        val f = PirEdgeFilter()
        val t0 = 1_000_000L
        assertFalse(f.onSample(false, t0))
        assertFalse(f.onSample(true, t0 + 200))
        assertTrue(f.onSample(true, t0 + 400))      // 触发 @t0+400，冷却至 t0+30400
        // 冷却窗内（<30s）完成一次 false→false→true→true 完整往返：
        // 若无冷却检查，这里会走完稳定化+新上升沿 → 在窗口内二次触发
        assertFalse(f.onSample(false, t0 + 1_000))
        assertFalse(f.onSample(false, t0 + 1_200))
        assertFalse(f.onSample(true, t0 + 1_400))
        assertFalse(f.onSample(true, t0 + 1_600))   // 距首触发仅 1.2s → 必须被冷却挡住
    }

    @Test
    fun `冷却窗外新边沿可再触发`() {
        val f = PirEdgeFilter()
        val t0 = 1_000_000L
        assertFalse(f.onSample(false, t0))
        assertFalse(f.onSample(true, t0 + 200))
        assertTrue(f.onSample(true, t0 + 400))        // 触发，冷却至 t0+30400
        assertFalse(f.onSample(false, t0 + 31_000))   // 窗外回落候选
        assertFalse(f.onSample(false, t0 + 31_200))   // 稳定态→false
        assertFalse(f.onSample(true, t0 + 31_400))
        assertTrue(f.onSample(true, t0 + 31_600))     // 30s 外新上升沿 → 再触发
    }

    // ---- 上升沿记忆（S70 变异锚 B：删 stableLevel 记忆 → 本用例红）----

    @Test
    fun `持续true跨冷却边界不重复触发`() {
        val f = PirEdgeFilter()
        val t0 = 1_000_000L
        assertFalse(f.onSample(false, t0))
        assertFalse(f.onSample(true, t0 + 200))
        assertTrue(f.onSample(true, t0 + 400))       // 触发（唯一一次），稳定态→true
        // 人未离开（PIR 持续高电平），跨过 30s 冷却窗：
        assertFalse(f.onSample(true, t0 + 20_000))
        assertFalse(f.onSample(true, t0 + 40_000))   // 窗外但无新边沿 → 不触发
        assertFalse(f.onSample(true, t0 + 60_000))
    }

    // ---- 下降沿 ----

    @Test
    fun `下降沿确认不触发`() {
        val f = PirEdgeFilter()
        val t0 = 1_000_000L
        assertFalse(f.onSample(false, t0))
        assertFalse(f.onSample(true, t0 + 200))
        assertTrue(f.onSample(true, t0 + 400))     // 上升沿触发
        assertFalse(f.onSample(false, t0 + 600))   // 下降候选
        assertFalse(f.onSample(false, t0 + 800))   // 下降沿确认：只更新稳定态，永不触发
    }

    // ---- null 样本（读失败）----

    @Test
    fun `null样本不参与边沿判定且不污染稳定态`() {
        val f = PirEdgeFilter()
        val t0 = 1_000_000L
        assertFalse(f.onSample(false, t0))
        assertFalse(f.onSample(true, t0 + 200))
        assertFalse(f.onSample(null, t0 + 400))    // 读失败：不触发、不污染稳定态、不打断候选
        assertTrue(f.onSample(true, t0 + 600))     // 候选延续 → 触发
    }

    @Test
    fun `纯null序列不触发`() {
        val f = PirEdgeFilter()
        assertFalse(f.onSample(null, 0L))
        assertFalse(f.onSample(null, 200L))
        assertFalse(f.onSample(null, 400L))
    }
}
