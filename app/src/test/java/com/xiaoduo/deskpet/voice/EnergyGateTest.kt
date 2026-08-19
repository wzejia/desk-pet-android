package com.xiaoduo.deskpet.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KWS 能量门控（对账表：默认 0.015，rms<gate 整块不喂不判；校准 p95×2.5 钳位 [0.003, 0.05]）。
 */
class EnergyGateTest {

    /** 造 100ms 块（1600 样本 Int16 小端），幅值 amplitude（0..32767）。 */
    private fun blockOf(amplitude: Int, samples: Int = 1600): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            out[2 * i] = (amplitude and 0xFF).toByte()
            out[2 * i + 1] = (amplitude shr 8 and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `default gate matches reconciliation table 0_015`() {
        assertEquals(0.015f, EnergyGate.DEFAULT_GATE)
    }

    @Test
    fun `silence block has zero rms and fails gate`() {
        val silence = ByteArray(3200)
        assertEquals(0f, EnergyGate.rmsOf(silence))
        assertFalse(EnergyGate.passes(silence, EnergyGate.DEFAULT_GATE))
    }

    @Test
    fun `rms is normalized to 0-1 domain`() {
        // 满幅直流：rms = 32767/32768 ≈ 0.99997
        val full = blockOf(32767)
        assertEquals(1.0f, EnergyGate.rmsOf(full), 0.001f)
        // 桌面真麦说话量级 0.036 ≈ 1179/32768
        val speech = blockOf(1179)
        assertEquals(0.036f, EnergyGate.rmsOf(speech), 0.001f)
    }

    @Test
    fun `gate boundary is inclusive pass`() {
        // 静音底噪 0.006（桌面真麦静音上沿）应被 0.015 拦下；说话 0.036 应放行
        assertFalse(EnergyGate.passes(blockOf(197), EnergyGate.DEFAULT_GATE)) // ≈0.006
        assertTrue(EnergyGate.passes(blockOf(1180), EnergyGate.DEFAULT_GATE)) // ≈0.036
        // rms == gate 恰好相等放行（>= 判定）
        val atGate = blockOf(492) // 492/32768 ≈ 0.01501
        assertTrue(EnergyGate.passes(atGate, EnergyGate.DEFAULT_GATE))
    }

    @Test
    fun `int16 conversion is little endian signed`() {
        // 0xFF80 = -128 → -128/32768
        val block = byteArrayOf(0x80.toByte(), 0xFF.toByte())
        val samples = EnergyGate.int16ToFloat(block)
        assertEquals(-128 / 32768.0f, samples[0], 1e-7f)
    }

    @Test
    fun `stats of empty list is all zero`() {
        val stats = EnergyGate.statsOf(emptyList())
        assertEquals(0, stats.count)
        assertEquals(0f, stats.min)
        assertEquals(0f, stats.p95)
        assertEquals(0f, stats.max)
    }

    @Test
    fun `stats computes distribution with nearest rank percentiles`() {
        // 100 块：1..100 × 0.0001 → median(最近秩 ceil(0.5*100)=50) 0.0050 / p95 0.0095
        val values = (1..100).map { it * 0.0001f }
        val stats = EnergyGate.statsOf(values)
        assertEquals(100, stats.count)
        assertEquals(0.0001f, stats.min, 1e-6f)
        assertEquals(0.0050f, stats.median, 1e-6f)
        assertEquals(0.0095f, stats.p95, 1e-6f)
        assertEquals(0.0100f, stats.max, 1e-6f)
    }

    @Test
    fun `suggest threshold reproduces desktop calibration value`() {
        // 桌面静音 p95≈0.006 → 0.006×2.5=0.015（对账表默认值的由来，公式回归钉死）
        val stats = EnergyGate.CalibrationStats(50, 0.001f, 0.003f, 0.006f, 0.008f)
        assertEquals(0.015f, EnergyGate.suggestThreshold(stats), 1e-6f)
    }

    @Test
    fun `suggest threshold clamps to contract bounds`() {
        val tooQuiet = EnergyGate.CalibrationStats(50, 0f, 0.0005f, 0.0008f, 0.001f)
        assertEquals(EnergyGate.MIN_GATE, EnergyGate.suggestThreshold(tooQuiet))
        val tooLoud = EnergyGate.CalibrationStats(50, 0.01f, 0.03f, 0.05f, 0.08f)
        assertEquals(EnergyGate.MAX_GATE, EnergyGate.suggestThreshold(tooLoud))
        assertEquals(0.003f, EnergyGate.MIN_GATE)
        assertEquals(0.05f, EnergyGate.MAX_GATE)
    }
}
