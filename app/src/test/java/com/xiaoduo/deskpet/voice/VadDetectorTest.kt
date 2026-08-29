package com.xiaoduo.deskpet.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VadDetector 单测：合成 PCM 块（恒定值块 = 精确 RMS）拼场景。
 * 量纲：level = RMS/8000；loud(1000) → 0.125 ≥ 0.05；quiet(100) → 0.0125 < 0.05。
 */
class VadDetectorTest {

    private fun block(value: Int) = ShortArray(VadDetector.BLOCK_SAMPLES) { value.toShort() }

    private val loud = block(1000)
    private val quiet = block(100)
    private val zero = block(0)

    private fun feedN(vad: VadDetector, b: ShortArray, n: Int): List<VadDetector.Event> =
        (1..n).mapNotNull { vad.feed(b) }

    @Test
    fun `two loud blocks do not trigger speech start`() {
        val vad = VadDetector()
        vad.start()
        assertNull(vad.feed(loud))
        assertNull(vad.feed(loud))
        assertFalse(vad.isSpeaking)
        assertTrue(vad.isActive)
    }

    @Test
    fun `three loud blocks trigger and four silence blocks end utterance`() {
        val vad = VadDetector()
        vad.start()
        assertTrue(feedN(vad, loud, 3).isEmpty())
        assertTrue(vad.isSpeaking)

        assertTrue(feedN(vad, zero, 3).isEmpty()) // 3 块静音不判停（句中短停顿保护）
        val ev = vad.feed(zero) // 第 4 块判停（400ms，2026-08-29 拍板提速）
        assertTrue(ev is VadDetector.Event.Utterance)
        // deque 里只有 3 触发块 → utterance = 3 块 = 9600 字节，恰为 min_utterance 边界（>= 通过）
        assertEquals(3 * VadDetector.BLOCK_SAMPLES, (ev as VadDetector.Event.Utterance).pcm.size)
        assertFalse(vad.isActive) // 单次触发后停
    }

    @Test
    fun `pre-roll preserves three pre-trigger low blocks and excludes trailing silence`() {
        val vad = VadDetector()
        vad.start()
        feedN(vad, quiet, 3) // 触发前低电平句首段
        feedN(vad, loud, 3) // 判起
        assertTrue(vad.isSpeaking)
        feedN(vad, loud, 2) // 继续说话
        val events = feedN(vad, zero, 4) // 判停（400ms）

        assertEquals(1, events.size)
        val pcm = (events[0] as VadDetector.Event.Utterance).pcm
        // 3 pre-roll + 3 触发块 + 2 说话块 = 8 块；尾部 4 块静音不进 buffer
        assertEquals(8 * VadDetector.BLOCK_SAMPLES, pcm.size)
        assertEquals(100, pcm[0].toInt()) // 首块 = 低电平 pre-roll（防丢首字）
        assertEquals(100, pcm[3 * VadDetector.BLOCK_SAMPLES - 1].toInt())
        assertEquals(1000, pcm[3 * VadDetector.BLOCK_SAMPLES].toInt()) // 第 4 块起为触发块
    }

    @Test
    fun `short noise is discarded and detector keeps listening`() {
        val vad = VadDetector()
        vad.start()
        assertTrue(feedN(vad, loud, 2).isEmpty()) // 2 块噪音不判起
        assertTrue(feedN(vad, zero, 3).isEmpty()) // 连续计数被静音清零
        assertFalse(vad.isSpeaking)
        assertTrue(vad.isActive)

        // 噪音不累积，随后正常说话仍可触发
        feedN(vad, loud, 3)
        assertTrue(vad.isSpeaking)
        val events = feedN(vad, zero, 8)
        assertEquals(1, events.size)
        assertTrue(events[0] is VadDetector.Event.Utterance)
    }

    @Test
    fun `listen timeout fires after 30s and carries max level`() {
        val vad = VadDetector()
        vad.start()
        val blip = block(240) // level = 240/8000 = 0.03，低于阈值
        assertTrue(feedN(vad, blip, 2).isEmpty()) // 不达判起
        assertTrue(feedN(vad, zero, 298).isEmpty()) // 累计消费 300 块 = 30s
        assertTrue(vad.isActive)

        val ev = vad.feed(zero) // 第 301 块触发超时
        assertTrue(ev is VadDetector.Event.Timeout)
        assertEquals(0.03, (ev as VadDetector.Event.Timeout).maxLevel, 1e-9)
        assertFalse(vad.isActive)
    }

    @Test
    fun `max utterance 8s forces end with accumulated speech only`() {
        val vad = VadDetector()
        vad.start()
        feedN(vad, loud, 3) // 判起（buffer = 3 触发块）
        assertTrue(feedN(vad, loud, 80).isEmpty()) // 80 块 = 8s 全部消费
        val ev = vad.feed(loud) // 第 81 块：speechTicks=80 触发强制结束，本块不消费
        assertTrue(ev is VadDetector.Event.Utterance)
        assertEquals(
            (3 + 80) * VadDetector.BLOCK_SAMPLES,
            (ev as VadDetector.Event.Utterance).pcm.size,
        )
        assertFalse(vad.isActive)
    }

    @Test
    fun `single shot stops after utterance until caller restarts`() {
        val vad = VadDetector()
        vad.start()
        feedN(vad, loud, 3)
        feedN(vad, zero, 8)
        assertFalse(vad.isActive)
        assertNull(vad.feed(loud)) // 停止后喂入无效

        vad.start() // 调用方重新 start 进下一轮
        assertTrue(vad.isActive)
        feedN(vad, loud, 3)
        assertTrue(vad.isSpeaking)
    }

    @Test
    fun `sub-threshold blocks never trigger speech start (threshold guard)`() {
        // 负样本守护：level=0.03 < 0.05 亚阈值块，连续喂 10 块也不得判起。
        // 阈值若被改成 0.01，本条必须变红。
        val vad = VadDetector()
        vad.start()
        val subThreshold = block(240) // level = 240/8000 = 0.03
        val events = feedN(vad, subThreshold, 10)
        assertTrue(events.isEmpty())
        assertFalse(vad.isSpeaking)
        assertTrue(vad.isActive) // 未判起也未超时，仍在听
    }

    @Test
    fun `clipping signal level is clamped to 1`() {
        // 削波信号 rms/8000 = 3.75 → 钳位 1.0（voice_listen.py:48）；maxLevel 不应超 1.0
        val vad = VadDetector()
        vad.start()
        val clipping = block(30000)
        assertTrue(feedN(vad, clipping, 2).isEmpty()) // 2 块不判起
        assertTrue(feedN(vad, zero, 298).isEmpty()) // 凑满 300 块
        val ev = vad.feed(zero)
        assertTrue(ev is VadDetector.Event.Timeout)
        assertEquals(1.0, (ev as VadDetector.Event.Timeout).maxLevel, 1e-9)
    }

    @Test
    fun `byte array feed matches short array path`() {
        val bytes = ByteArray(VadDetector.BLOCK_BYTES) { i ->
            if (i % 2 == 0) (1000 and 0xFF).toByte() else (1000 shr 8).toByte()
        }
        val vad = VadDetector()
        vad.start()
        repeat(3) { assertNull(vad.feed(bytes)) }
        assertTrue(vad.isSpeaking)
        var ev: VadDetector.Event? = null
        repeat(4) { ev = vad.feed(ByteArray(VadDetector.BLOCK_BYTES)) } // 400ms 判停
        assertTrue(ev is VadDetector.Event.Utterance)
        assertEquals(3 * VadDetector.BLOCK_SAMPLES, (ev as VadDetector.Event.Utterance).pcm.size)
    }
}
