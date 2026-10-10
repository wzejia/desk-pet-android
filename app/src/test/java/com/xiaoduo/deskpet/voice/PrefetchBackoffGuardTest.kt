package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.voice.policy.PrefetchBackoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PrefetchBackoff 并发/边界守卫单测（ocr[392]/[393]/[395]）。
 *
 * 变异锚：
 * - [393] 删负差值放行门（回退 nowMs - last < windowMs）→ 用例 1 红（时钟回拨被判窗内
 *   无限跳过；回拨期 shouldSkip 早退永不 recordSuccess，卡死超过 10min 契约）；
 * - [392] 删过期条目两参 remove 顺手清理 → 用例 2 红（台账无界增长，size 不归零；
 *   两参 remove 防覆盖并发更新的失败时间）；
 * - [395] 边界 '<' 改 '<=' → 用例 3 红（恰满 windowMs 应放行重试）。
 */
class PrefetchBackoffGuardTest {

    @Test
    fun `时钟回拨按过期放行——负差值不得判窗内`() {
        val backoff = PrefetchBackoff()
        backoff.recordFailure("k", nowMs = 1_000)
        // 回拨 100ms：旧式 nowMs - last = -100 < windowMs → 恒 true 跳过（卡死）
        assertFalse("负差值=过期放行（变异锚：删门 → 本行红）", backoff.shouldSkip("k", nowMs = 900))
    }

    @Test
    fun `过期条目顺手清理——台账不无界增长`() {
        val backoff = PrefetchBackoff()
        backoff.recordFailure("k", nowMs = 0)
        val window = PrefetchBackoff.DEFAULT_WINDOW_MS
        assertFalse("过期放行", backoff.shouldSkip("k", nowMs = window + 1))
        assertEquals("过期条目已被清理（变异锚：删两参 remove → size 仍 1）", 0, backoff.sizeForTest())
    }

    @Test
    fun `边界钉死——恰满 windowMs 放行，差 1ms 窗内`() {
        val backoff = PrefetchBackoff()
        backoff.recordFailure("a", nowMs = 1_000)
        backoff.recordFailure("b", nowMs = 1_000)
        assertFalse("恰满 windowMs：放行重试（'<' 语义）", backoff.shouldSkip("a", nowMs = 1_000 + PrefetchBackoff.DEFAULT_WINDOW_MS))
        assertTrue("差 1ms：窗内跳过", backoff.shouldSkip("b", nowMs = 1_000 + PrefetchBackoff.DEFAULT_WINDOW_MS - 1))
    }
}
