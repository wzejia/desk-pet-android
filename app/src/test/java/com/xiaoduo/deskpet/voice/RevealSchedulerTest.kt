package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.voice.pipeline.RevealScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RevealSchedulerTest {

    @Test
    fun `pcm duration is evenly split across chars`() {
        // 48000 字节 = 48000/2/24000 = 1.0s，10 字 → 每字 100ms
        val delays = RevealScheduler.delaysFor("一二三四五六七八九十", 48000)
        assertEquals(10, delays.size)
        delays.forEach { assertEquals(100.0, it, 1e-9) }
        assertEquals(1000.0, delays.sum(), 1e-9)
    }

    @Test
    fun `per char delay follows exact formula len pcm over 2 over 24000`() {
        // 24000 字节 = 0.5s，3 字 → 每字 166.67ms，总和 500ms
        val delays = RevealScheduler.delaysFor("你好呀", 24000)
        assertEquals(3, delays.size)
        assertEquals(500.0 / 3, delays[0], 1e-9)
        assertEquals(500.0, delays.sum(), 1e-6)
    }

    @Test
    fun `null or zero pcm falls back to 4 point 5 chars per second`() {
        val expected = 1000.0 / 4.5
        RevealScheduler.delaysFor("你好呀！", null).forEach { assertEquals(expected, it, 1e-9) }
        RevealScheduler.delaysFor("你好呀！", 0).forEach { assertEquals(expected, it, 1e-9) }
        assertEquals(expected, RevealScheduler.fallbackPerCharDelayMs(), 1e-9)
    }

    @Test
    fun `empty sentence yields empty delay list`() {
        assertTrue(RevealScheduler.delaysFor("", 48000).isEmpty())
        assertTrue(RevealScheduler.delaysFor("", null).isEmpty())
    }

    @Test
    fun `delayForChar matches per-char value and rejects bad index`() {
        assertEquals(100.0, RevealScheduler.delayForChar(0, 10, 48000), 1e-9)
        assertEquals(100.0, RevealScheduler.delayForChar(9, 10, 48000), 1e-9)
        assertEquals(1000.0 / 4.5, RevealScheduler.delayForChar(0, 10, null), 1e-9)
        val ex = runCatching { RevealScheduler.delayForChar(10, 10, 48000) }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
    }
}
