package com.xiaoduo.deskpet.voice.kws

import com.xiaoduo.deskpet.util.PrefContract
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * KWS 能量门控（纯逻辑，JVM 可测，零 Android import）。
 *
 * 依据：对账表 KWS 节 = 知识包 §5：RMS 能量门控默认 0.015，rms<gate 整块不喂不判
 * （防静音空转耗电；真麦静音 ~0.001-0.006 / 说话 ~0.036，0.015 居中）。
 * RMS 在 float32 域计算：Int16 样本 / 32768 归一化后取均方根（与桌面 sidecar.py 同域）。
 *
 * 校准公式：[suggestThreshold] = 环境 p95 × 2.5，钳位 [MIN_GATE, MAX_GATE]。
 * 桌面校准值回代验证：安静环境 p95≈0.006 → 0.006×2.5=0.015，恰为对账表默认值。
 */
object EnergyGate {

    const val DEFAULT_GATE = PrefContract.DEFAULT_KWS_GATE
    const val MIN_GATE = PrefContract.MIN_KWS_GATE
    const val MAX_GATE = PrefContract.MAX_KWS_GATE

    /** Int16 小端 PCM → float32（/32768，知识包 §5 喂法）。 */
    fun int16ToFloat(block: ByteArray): FloatArray {
        val out = FloatArray(block.size / 2)
        for (i in out.indices) {
            val lo = block[2 * i].toInt() and 0xFF
            val hi = block[2 * i + 1].toInt() shl 8
            out[i] = (hi or lo).toShort() / 32768.0f
        }
        return out
    }

    /** float32 样本均方根（0..1 域）。 */
    fun rmsOfSamples(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return sqrt(sum / samples.size).toFloat()
    }

    fun rmsOf(block: ByteArray): Float = rmsOfSamples(int16ToFloat(block))

    /** 门控判定：rms >= gate 才放行（低于门限整块不喂不判）。 */
    fun passes(block: ByteArray, gate: Float): Boolean = rmsOf(block) >= gate

    /** 环境 RMS 分布（校准报告用）。 */
    data class CalibrationStats(
        val count: Int,
        val min: Float,
        val median: Float,
        val p95: Float,
        val max: Float,
    )

    /** 由一组块级 RMS 计算分布；空列表返回全 0（调用方判 count==0 提示未采到音频）。 */
    fun statsOf(rmsValues: List<Float>): CalibrationStats {
        if (rmsValues.isEmpty()) return CalibrationStats(0, 0f, 0f, 0f, 0f)
        val sorted = rmsValues.sorted()
        return CalibrationStats(
            count = sorted.size,
            min = sorted.first(),
            median = percentile(sorted, 0.50),
            p95 = percentile(sorted, 0.95),
            max = sorted.last(),
        )
    }

    /** 建议阈值 = 环境 p95 × 2.5，钳位 [MIN_GATE, MAX_GATE]（公式依据见类注释）。 */
    fun suggestThreshold(stats: CalibrationStats): Float =
        (stats.p95 * 2.5f).coerceIn(MIN_GATE, MAX_GATE)

    /**
     * S18：VAD 说话电平阈值建议 = clamp(环境 p95 × 1.6, 0.05, 0.15)。
     * 依据：阈值须压过噪声底（×1.6 余量）且远低于人声；下限 0.05 = 对账表默认
     * （手机安静环境 p95≈0.006 回 0.05，行为零变化）；上限 0.15 防极端环境吞语音。
     * 实测锚点：rk3568 板子 p95=0.0496 -> 0.079（噪声回落阈值下，判停恢复）。
     */
    fun suggestVadSpeechLevel(stats: CalibrationStats): Double =
        (stats.p95 * 1.6).coerceIn(0.05, 0.15)

    /** 最近秩百分位：index = ceil(p*n)-1。 */
    private fun percentile(sorted: List<Float>, p: Double): Float {
        val idx = (ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
