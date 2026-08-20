package com.xiaoduo.deskpet.voice

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AudioProbe 纯逻辑单测（S12 延迟取证探针）：
 * - 变异锚：删 [AudioProbe.shouldLog] 的 armed 判定（只剩 rms>=gate）→
 *   `disarmed probe never logs even above gate` 红。
 * - 安静期零日志量钉死：armed 但能量不过门不打。
 * - lagMs 纳秒→毫秒换算与负值透传。
 */
class AudioProbeTest {

    @After
    fun disarm() {
        AudioProbe.armed = false // 全局开关，防串扰其它测试类（如 KwsCoreTest）
    }

    @Test
    fun `disarmed probe never logs even above gate`() {
        AudioProbe.armed = false
        assertFalse(AudioProbe.shouldLog(rms = 0.5f, gate = 0.015f))
        assertFalse(AudioProbe.shouldLog(rms = 0.036f, gate = 0.015f))
    }

    @Test
    fun `armed probe stays silent below gate`() {
        AudioProbe.armed = true
        assertFalse(AudioProbe.shouldLog(rms = 0.005f, gate = 0.015f))
        assertFalse(AudioProbe.shouldLog(rms = 0.0149f, gate = 0.015f))
    }

    @Test
    fun `armed probe logs at or above gate`() {
        AudioProbe.armed = true
        assertTrue(AudioProbe.shouldLog(rms = 0.036f, gate = 0.015f))
        // 边界取等：rms==gate 放行（与 EnergyGate.passes / KwsCore.feed 同口径）
        assertTrue(AudioProbe.shouldLog(rms = 0.015f, gate = 0.015f))
    }

    @Test
    fun `lagMs converts nanos to millis and passes negative skew through`() {
        assertEquals(500L, AudioProbe.lagMs(2_000_000_000L, 1_500_000_000L))
        assertEquals(0L, AudioProbe.lagMs(1_000L, 1_000L))
        // 时钟异常（跨时钟域/回拨）原样透出负值，不钳位——取证数据保真优先
        assertEquals(-1L, AudioProbe.lagMs(1_000_000L, 2_000_000L))
    }

    @Test
    fun `arm toggle is idempotent memory-only flag`() {
        AudioProbe.armed = true
        AudioProbe.armed = true // 重复 arm 幂等
        assertTrue(AudioProbe.armed)
        AudioProbe.armed = false
        assertFalse(AudioProbe.armed)
        assertFalse(AudioProbe.shouldLog(rms = 1.0f, gate = 0.015f))
    }
}
