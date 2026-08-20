package com.xiaoduo.deskpet.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * KwsDefaults 锚点测试（S11-1）。
 *
 * 变异锚语义：KwsDefaults.KEYWORDS_THRESHOLD 被改（如事故恢复静默回退 0.5）
 * 或被删 → 本测试必须红。2026-08-18 仓库事故教训：无锚常量在转录重放重建时
 * 静默回退出厂默认，真机回归唤不醒；本测试即防再次静默回退的锚。
 */
class KwsDefaultsTest {

    @Test
    fun `keywords threshold is the 08-10 field-verified 0_3`() {
        // 精确断言：0.3 = 08-10 真机验证值（IDLE 态 10/10），禁范围断言。
        assertEquals(0.3f, KwsDefaults.KEYWORDS_THRESHOLD)
    }

    @Test
    fun `gate hangover window is the VAD-anchored 8 blocks`() {
        // 精确断言：8 块 × 100ms/块 = 800ms，与 VadDetector.SILENCE_END_TICKS=8
        // （800ms 判停）同源——短于 800ms 的静默在本项目语义里仍属同一口气。禁范围断言。
        assertEquals(8, KwsDefaults.GATE_HANGOVER_BLOCKS)
    }
}
