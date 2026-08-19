package com.xiaoduo.deskpet.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧表与桌面端 pet/pet_states.json 逐项对账（知识包教训 #47：第三栈必须参数对账）。
 */
class PetFrameTableTest {

    @Test
    fun `每行帧数与帧表一致`() {
        // (name, row, frames, durationMs) 与 pet_states.json 逐行一致
        val expected = listOf(
            listOf("idle", 0, 6, 1100L),
            listOf("running-right", 1, 8, 1060L),
            listOf("running-left", 2, 8, 1060L),
            listOf("waving", 3, 6, 900L),
            listOf("jumping", 4, 5, 840L),
            listOf("failed", 5, 8, 1220L),
            listOf("waiting", 6, 6, 1600L),
            listOf("talking", 7, 6, 720L),
            listOf("review", 8, 6, 1500L),
        )
        assertEquals(9, PetFrameTable.ALL.size)
        PetFrameTable.ALL.forEachIndexed { i, s ->
            val e = expected[i]
            assertEquals("state[$i].name", e[0], s.name)
            assertEquals("state[$i].row", e[1], s.row)
            assertEquals("state[$i].frames", e[2], s.frames)
            assertEquals("state[$i].durationMs", e[3], s.durationMs)
        }
        // 行号 0-8 不重复、帧数不超过 8 列
        assertEquals(9, PetFrameTable.ALL.map { it.row }.distinct().size)
        assertTrue(PetFrameTable.ALL.all { it.frames <= PetFrameTable.COLS })
    }

    @Test
    fun `帧时长换算正确`() {
        assertEquals(183L, FrameAnimator(PetFrameTable.IDLE).perFrameMs)       // 1100/6
        assertEquals(132L, FrameAnimator(PetFrameTable.RUNNING_RIGHT).perFrameMs) // 1060/8
        assertEquals(168L, FrameAnimator(PetFrameTable.JUMPING).perFrameMs)    // 840/5
        assertEquals(150L, FrameAnimator(PetFrameTable.WAVING).perFrameMs)     // 900/6
        assertEquals(120L, FrameAnimator(PetFrameTable.TALKING).perFrameMs)    // 720/6
    }

    @Test
    fun `状态切换重置到第0帧`() {
        val a = FrameAnimator(PetFrameTable.IDLE)
        a.tick(183 * 3 + 10) // 推进到第 3 帧
        assertEquals(3, a.frameIndex)
        a.setState(PetFrameTable.WAITING)
        assertEquals(0, a.frameIndex)
        assertEquals(PetFrameTable.WAITING, a.state)
    }

    @Test
    fun `循环模式播完回卷到第0帧`() {
        val a = FrameAnimator(PetFrameTable.IDLE) // 6 帧, 183ms/帧
        a.tick(1100) // 恰好一轮
        assertEquals(0, a.frameIndex)
        assertFalse(a.finished)
    }

    @Test
    fun `单次模式播完停在末帧并回调`() {
        var called = 0
        val a = FrameAnimator(PetFrameTable.IDLE)
        a.setState(PetFrameTable.WAVING, once = true) { called++ } // 6 帧, 150ms/帧
        a.tick(900 + 500) // 超过整轮时长
        assertTrue(a.finished)
        assertEquals(1, called)
        assertEquals(5, a.frameIndex) // 停在最后一帧
        a.tick(1000) // 结束后不再推进
        assertEquals(5, a.frameIndex)
        assertEquals(1, called) // 回调只触发一次
    }

    @Test
    fun `源矩形坐标按帧索引与行号计算`() {
        val a = FrameAnimator(PetFrameTable.TALKING) // row 7
        assertEquals(0, a.sourceLeft)
        assertEquals(7 * 208, a.sourceTop)
        a.tick(120 * 2 + 5) // 第 2 帧
        assertEquals(2 * 192, a.sourceLeft)
        assertEquals(7 * 208, a.sourceTop)
    }
}
