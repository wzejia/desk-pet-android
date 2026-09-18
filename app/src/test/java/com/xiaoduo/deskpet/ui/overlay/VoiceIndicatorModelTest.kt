package com.xiaoduo.deskpet.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * S49 语音指示泡纯模型锚点测试：归一化 clamp、镜像对称、摆动/跳动脉冲取值域、
 * 错相恒等式、水平居中。公式变更必须先改锚（防样式回退无感漂移）。
 */
class VoiceIndicatorModelTest {

    // ---- RMS 归一化 ----

    @Test
    fun normalizeClampsToBounds() {
        assertEquals(0f, VoiceIndicatorModel.normalize(VoiceIndicatorModel.RMS_FLOOR), 1e-6f)
        assertEquals(0f, VoiceIndicatorModel.normalize(-1f), 1e-6f) // 低于地板 clamp 到 0
        assertEquals(
            1f,
            VoiceIndicatorModel.normalize(VoiceIndicatorModel.RMS_FLOOR + VoiceIndicatorModel.RMS_SPAN),
            1e-6f,
        )
        assertEquals(1f, VoiceIndicatorModel.normalize(99f), 1e-6f) // 超_span clamp 到 1
    }

    @Test
    fun normalizeMidpointIsHalf() {
        val mid = VoiceIndicatorModel.RMS_FLOOR + VoiceIndicatorModel.RMS_SPAN / 2f
        assertEquals(0.5f, VoiceIndicatorModel.normalize(mid), 1e-6f)
    }

    // ---- 声纹镜像柱 ----

    @Test
    fun barEnvelopeIsMirrorSymmetric() {
        val n = VoiceIndicatorModel.BAR_COUNT
        for (i in 0 until n) {
            assertEquals(
                "bar $i 与镜像柱 ${n - 1 - i} 包络必须相等",
                VoiceIndicatorModel.barEnvelope(i),
                VoiceIndicatorModel.barEnvelope(n - 1 - i),
                1e-6f,
            )
        }
    }

    @Test
    fun barEnvelopePeaksAtCenterAndBounds() {
        val half = (VoiceIndicatorModel.BAR_COUNT - 1) / 2
        assertEquals(1f, VoiceIndicatorModel.barEnvelope(half), 1e-6f) // 中轴最高
        for (i in 0 until VoiceIndicatorModel.BAR_COUNT) {
            val e = VoiceIndicatorModel.barEnvelope(i)
            assertTrue("包络须在 (0,1]：i=$i e=$e", e in 0f..1f && e > 0f)
        }
    }

    @Test
    fun barWobbleStaysInBand() {
        // 摆动幅度 0.78±0.22：任意相位不越带（静音时柱体仍有生命感但不消失感）
        var t = 0f
        while (t < 10f) {
            for (i in 0 until VoiceIndicatorModel.BAR_COUNT) {
                val w = VoiceIndicatorModel.barWobble(i, t)
                assertTrue("wobble 越带: i=$i t=$t w=$w", w in 0.55f..1.01f)
            }
            t += 0.017f
        }
    }

    @Test
    fun barFractionBoundedByLevel() {
        // level=0：柱高=0（只剩 minH 由视图侧加）；level=1：≤ 包络峰值
        for (i in 0 until VoiceIndicatorModel.BAR_COUNT) {
            assertEquals(0f, VoiceIndicatorModel.barFraction(i, 0f, 0.3f), 1e-6f)
            val full = VoiceIndicatorModel.barFraction(i, 1f, 0.3f)
            assertTrue(full <= VoiceIndicatorModel.barEnvelope(i) + 1e-6f)
        }
    }

    // ---- 思考三点 ----

    @Test
    fun dotHopStaysInUnitRange() {
        var t = 0f
        while (t < 5f) {
            for (i in 0 until VoiceIndicatorModel.DOT_COUNT) {
                val h = VoiceIndicatorModel.dotHop(i, t)
                assertTrue("hop 越界: i=$i t=$t h=$h", h in 0f..1f)
            }
            t += 0.011f
        }
    }

    @Test
    fun dotHopPhaseShiftIdentity() {
        // 错相恒等式：hop(i, t + 周期/3) == hop(i+1, t)
        val step = VoiceIndicatorModel.HOP_PERIOD_S / VoiceIndicatorModel.DOT_COUNT
        var t = 0.13f
        while (t < 3f) {
            for (i in 0 until VoiceIndicatorModel.DOT_COUNT - 1) {
                assertEquals(
                    "错相恒等失败: i=$i t=$t",
                    VoiceIndicatorModel.dotHop(i + 1, t),
                    VoiceIndicatorModel.dotHop(i, t + step),
                    1e-4f,
                )
            }
            t += step / 3f
        }
    }

    @Test
    fun dotsAreHorizontallyCentered() {
        // 三点 X 中心均值 == 指示泡宽/2
        val w = VoiceIndicatorModel.INDICATOR_W_DP
        var sum = 0f
        for (i in 0 until VoiceIndicatorModel.DOT_COUNT) sum += VoiceIndicatorModel.dotCenterXFrac(i) * w
        assertEquals(w / 2f, sum / VoiceIndicatorModel.DOT_COUNT, 1e-4f)
    }

    @Test
    fun barsContentFitsIndicatorWidth() {
        assertTrue(
            "柱内容宽 ${VoiceIndicatorModel.BARS_CONTENT_W_DP}dp 必须窄于泡宽",
            VoiceIndicatorModel.BARS_CONTENT_W_DP < VoiceIndicatorModel.INDICATOR_W_DP,
        )
        val margin = VoiceIndicatorModel.INDICATOR_W_DP - VoiceIndicatorModel.BARS_CONTENT_W_DP
        assertTrue("两侧留白过窄：$margin", margin > 10f)
        assertTrue(abs(margin - 31f) < 0.01f) // 132-101=31dp（与原型一致）
    }
}
