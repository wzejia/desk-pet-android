package com.xiaoduo.deskpet.ui.overlay

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * S49 语音指示泡纯模型（聆听声纹 / 思考三点）：几何与动画公式集中此处，JVM 可测。
 *
 * 公式与全屏语音页 ListeningWaveform/ThinkingDots 同源（FullscreenVoiceScreen.kt），
 * 样式按用户定案改为：镜像柱 ×13（中轴对称展开）+ 三点错相跳动；显示位从全屏页
 * 状态区改为悬浮窗气泡（恒定 132×40dp，与单行文字泡同高，两态切换尺寸零变化）。
 *
 * RMS 口径：EnergyGate.rmsOf(block) × (32768/8000)（VAD diag / 全屏页同款），
 * 归一化 (rms−FLOOR)/SPAN 后 clamp——FLOOR/SPAN 为单一来源（FullscreenVoiceScreen
 * 直接引用本常量，P3-T5 收敛镜像，改这里两处同变）。
 */
object VoiceIndicatorModel {

    // ---- RMS 归一化（单一来源：FullscreenVoiceScreen 亦引用，勿在他处私有镜像） ----
    const val RMS_FLOOR = 0.005f
    const val RMS_SPAN = 0.045f

    // ---- 声纹镜像柱 ----
    const val BAR_COUNT = 13
    const val BAR_W_DP = 5f
    const val BAR_GAP_DP = 3f
    /** 13 柱内容总宽：13×5 + 12×3 = 101dp（132dp 气泡内居中，两侧各 ~15.5dp）。 */
    const val BARS_CONTENT_W_DP = BAR_COUNT * BAR_W_DP + (BAR_COUNT - 1) * BAR_GAP_DP
    /** 柱活动区高（气泡卡 40dp 内居中），最矮柱占比同全屏页 0.12。 */
    const val BARS_AREA_H_DP = 30f
    const val BAR_MIN_FRACTION = 0.12f

    // ---- 思考三点（FullscreenVoiceScreen ThinkingDots 同参数） ----
    const val DOT_COUNT = 3
    const val DOT_D_DP = 9f
    const val DOT_GAP_DP = 8f
    const val DOT_LIFT_DP = 7f
    /** 跳动脉冲周期（秒）：0.85s≈3 完整周期，短思考窗也明显在跳（S14-2 定案）。 */
    const val HOP_PERIOD_S = 0.85f

    // ---- 指示泡恒定尺寸（用户定案：与单行文字泡同高，两态切换零变化） ----
    const val INDICATOR_W_DP = 132f
    const val INDICATOR_H_DP = 40f

    /** RMS → [0,1] 显示电平。 */
    fun normalize(rms: Float): Float = ((rms - RMS_FLOOR) / RMS_SPAN).coerceIn(0f, 1f)

    /**
     * 柱包络因子：中轴最高，两端渐低（抛物线钟形），镜像对称——
     * f(i) == f(BAR_COUNT-1-i)（测试锚点）。
     */
    fun barEnvelope(i: Int): Float {
        val half = (BAR_COUNT - 1) / 2f
        val x = abs(i - half) / half
        return 1f - 0.62f * x * x
    }

    /** 柱间正弦相位摆动：静音时也微微起伏（有生命感），同全屏页 5.2/1.7 参数。 */
    fun barWobble(i: Int, tSec: Float): Float = 0.78f + 0.22f * sin(tSec * 5.2f + i * 1.7f)

    /** 柱高占比（0,1] → 乘 (活动区高−最矮高) 得实际高度；level 已归一化。 */
    fun barFraction(i: Int, level: Float, tSec: Float): Float {
        val lv = level.coerceIn(0f, 1f)
        return (lv * barEnvelope(i) * barWobble(i, tSec)).coerceIn(0f, 1f)
    }

    /**
     * 三点跳动脉冲（0..1）：升余弦，周期 0.85s，三点错相各偏 1/3——
     * hop(i, t + PERIOD/3) == hop(i+1, t)（错相恒等式，测试锚点）。
     */
    fun dotHop(i: Int, tSec: Float): Float {
        val phase = (tSec / HOP_PERIOD_S + i.toFloat() / DOT_COUNT) % 1f
        return 0.5f - 0.5f * cos(2.0 * PI * phase).toFloat()
    }

    /** 思考点 X 中心（第 i 点，比例 0..1）：三点整体水平居中。 */
    fun dotCenterXFrac(i: Int): Float {
        val total = DOT_COUNT * DOT_D_DP + (DOT_COUNT - 1) * DOT_GAP_DP
        val x0 = (INDICATOR_W_DP - total) / 2f + DOT_D_DP / 2f
        return (x0 + i * (DOT_D_DP + DOT_GAP_DP)) / INDICATOR_W_DP
    }
}
