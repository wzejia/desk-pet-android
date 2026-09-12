package com.xiaoduo.deskpet.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S40 唤醒盲区环形缓冲单测：容量淘汰/FIFO 序/drain 清空/clear。
 *
 * 语义锚（票据点名）：容量满淘汰最旧 = 保最新音频（与 AudioCapture DROP_OLDEST、
 * VAD pre-roll deque 同策）；drain 后缓冲必空（回灌恰好一次，防双灌撕时间轴）。
 */
class WakeBlindWindowBufferTest {

    @Test
    fun fifoOrderPreserved() {
        val buf = WakeBlindWindowBuffer(capacityBlocks = 4)
        val a = ByteArray(1) { 1 }
        val b = ByteArray(1) { 2 }
        val c = ByteArray(1) { 3 }
        buf.add(a)
        buf.add(b)
        buf.add(c)
        assertEquals(listOf(a, b, c), buf.drain())
    }

    @Test
    fun capacityEvictsOldestKeepsNewest() {
        val buf = WakeBlindWindowBuffer(capacityBlocks = 3)
        val a = ByteArray(1) { 1 }
        val b = ByteArray(1) { 2 }
        val c = ByteArray(1) { 3 }
        val d = ByteArray(1) { 4 }
        buf.add(a)
        buf.add(b)
        buf.add(c)
        buf.add(d)
        assertEquals("满后淘汰最旧，保最新 3 块", listOf(b, c, d), buf.drain())
    }

    @Test
    fun drainClearsBuffer() {
        val buf = WakeBlindWindowBuffer(capacityBlocks = 2)
        buf.add(ByteArray(1) { 9 })
        assertEquals(1, buf.drain().size)
        assertEquals("drain 后必空（回灌恰好一次）", 0, buf.size)
        assertTrue(buf.drain().isEmpty())
    }

    @Test
    fun clearEmptiesBuffer() {
        val buf = WakeBlindWindowBuffer(capacityBlocks = 2)
        buf.add(ByteArray(1) { 9 })
        buf.add(ByteArray(1) { 9 })
        buf.clear()
        assertEquals(0, buf.size)
        assertTrue(buf.drain().isEmpty())
    }

    @Test
    fun defaultCapacityIsThreeSeconds() {
        assertEquals("默认容量 30 块 = 3s（覆盖 KWS 尾延迟+应答播放+消费停顿）", 30, WakeBlindWindowBuffer().capacityBlocks)
    }
}
