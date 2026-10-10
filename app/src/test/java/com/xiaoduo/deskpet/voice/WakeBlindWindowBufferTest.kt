package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.voice.audio.AudioCapture
import com.xiaoduo.deskpet.voice.seam.WakeBlindWindowBuffer
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
        buf.add(a, 1L)
        buf.add(b, 2L)
        buf.add(c, 3L)
        assertEquals(listOf(a, b, c), buf.drain())
    }

    @Test
    fun capacityEvictsOldestKeepsNewest() {
        val buf = WakeBlindWindowBuffer(capacityBlocks = 3)
        val a = ByteArray(1) { 1 }
        val b = ByteArray(1) { 2 }
        val c = ByteArray(1) { 3 }
        val d = ByteArray(1) { 4 }
        buf.add(a, 4L)
        buf.add(b, 5L)
        buf.add(c, 6L)
        buf.add(d, 7L)
        assertEquals("满后淘汰最旧，保最新 3 块", listOf(b, c, d), buf.drain())
    }

    @Test
    fun drainClearsBuffer() {
        val buf = WakeBlindWindowBuffer(capacityBlocks = 2)
        buf.add(ByteArray(1) { 9 }, 8L)
        assertEquals(1, buf.drain().size)
        assertEquals("drain 后必空（回灌恰好一次）", 0, buf.size)
        assertTrue(buf.drain().isEmpty())
    }

    @Test
    fun clearEmptiesBuffer() {
        val buf = WakeBlindWindowBuffer(capacityBlocks = 2)
        buf.add(ByteArray(1) { 9 }, 9L)
        buf.add(ByteArray(1) { 9 }, 10L)
        buf.clear()
        assertEquals(0, buf.size)
        assertTrue(buf.drain().isEmpty())
    }

    @Test
    fun drainFromDropsEchoEraKeepsPostEra() {
        val buf = WakeBlindWindowBuffer(capacityBlocks = 8)
        val a = ByteArray(1) { 1 }
        val b = ByteArray(1) { 2 }
        val c = ByteArray(1) { 3 }
        buf.add(a, 100L)
        buf.add(b, 200L)
        buf.add(c, 300L)
        assertEquals("早于截止线全丢（回声纪元），其余 FIFO 保留", listOf(b, c), buf.drainFrom(150L))
        assertEquals("drainFrom 后必空（与 drain 同约）", 0, buf.size)
    }

    @Test
    fun drainFromBoundaryKeepsExactCutoff() {
        val buf = WakeBlindWindowBuffer()
        val a = ByteArray(1) { 1 }
        buf.add(a, 150L)
        assertEquals("恰好等于截止线 = 保留（判据 capturedAt < cutoff 才丢）", listOf(a), buf.drainFrom(150L))
    }

    @Test
    fun defaultCapacityIsThreeSeconds() {
        assertEquals("默认容量 30 块 = 3s（覆盖 KWS 尾延迟+应答播放+消费停顿）", 30, WakeBlindWindowBuffer().capacityBlocks)
    }

    /**
     * CONC-2：add/drain 可达采麦收集协程（Default），clear 可达 KWS 唤醒回调/UI barge-in
     * 线程（文档化的 ack 播放中唤醒打断场景即双写）——ArrayDeque 非线程安全，无锁并发
     * 必然概率性损坏（丢块/head-tail 错乱/越界异常）。压测锁定：多线程 hammer 不得抛、
     * 终态 size 不得超容量。修复前 ArrayDeque 裸奔大概率抛 NoSuchElementException/
     * AIOOBE → 红。
     */
    @Test
    fun `CONC-2 concurrent add drain clear never corrupts buffer`() {
        val buf = WakeBlindWindowBuffer(capacityBlocks = 8)
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val threads = (1..4).map { t ->
            java.lang.Thread {
                try {
                    var i = 0L
                    while (!stop.get()) {
                        buf.add(ByteArray(4), i++)
                        if (i % 3 == 0L) buf.drain()
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }.apply { start() }
        }
        val clearer = java.lang.Thread {
            try {
                while (!stop.get()) {
                    buf.clear()
                    buf.drainFrom(0L)
                }
            } catch (e: Throwable) {
                errors.add(e)
            }
        }.apply { start() }
        Thread.sleep(400)
        stop.set(true)
        threads.forEach { it.join(3000) }
        clearer.join(3000)
        assertTrue("并发操作不得损坏缓冲: $errors", errors.isEmpty())
        assertTrue("终态 size 不得超容量: ${buf.size}", buf.size <= buf.capacityBlocks)
    }
}
