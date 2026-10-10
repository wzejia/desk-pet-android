package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.voice.audio.AmbientCalibrator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S29 首次自动校准：环境稳定性判定（纯 Kotlin）。
 * 自动校准在无人监督时写入阈值，必须拒绝被污染的样本——有人在说话 / 突发噪声时
 * p95 相对 median 抬升（分布散），写出的门控会偏高导致漏唤醒。
 */
class AmbientCalibratorTest {

    private fun stats(median: Float, p95: Float, count: Int = 100, max: Float = p95 * 1.2f) =
        EnergyGate.CalibrationStats(count = count, min = 0f, median = median, p95 = p95, max = max)

    @Test
    fun `quiet stable ambient passes`() {
        // 纯噪声：p95 贴近 median（分布集中）
        assertTrue(AmbientCalibrator.stableEnough(stats(median = 0.002f, p95 = 0.003f)))
    }

    @Test
    fun `p95 slightly above median still passes`() {
        // 允许正常波动：比值恰在容忍线（p95 = median * 4）
        assertTrue(AmbientCalibrator.stableEnough(stats(median = 0.002f, p95 = 0.008f)))
    }

    @Test
    fun `speech-contaminated sample rejected`() {
        // 采集中有人说话：p95 远超 median（人声 RMS 比噪声底高一个量级）
        assertFalse(AmbientCalibrator.stableEnough(stats(median = 0.002f, p95 = 0.02f)))
    }

    @Test
    fun `burst noise rejected`() {
        assertFalse(AmbientCalibrator.stableEnough(stats(median = 0.001f, p95 = 0.03f)))
    }

    @Test
    fun `empty capture rejected`() {
        assertFalse(AmbientCalibrator.stableEnough(EnergyGate.CalibrationStats(0, 0f, 0f, 0f, 0f)))
    }

    @Test
    fun `all-zero rms rejected`() {
        // 采麦失败/静音设备：median=0 无从判定噪声底
        assertFalse(AmbientCalibrator.stableEnough(stats(median = 0f, p95 = 0f)))
    }
}
