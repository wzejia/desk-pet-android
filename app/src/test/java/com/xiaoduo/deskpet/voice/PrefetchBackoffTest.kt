package com.xiaoduo.deskpet.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PrefetchBackoff 单测（S9-2）：放行 / 同 key 窗内拒 / 异 key 不受阻 / 窗过放行+成功清账。
 */
class PrefetchBackoffTest {

    @Test
    fun `fresh key is allowed`() {
        val b = PrefetchBackoff()
        assertFalse(b.shouldSkip("k1", 1_000_000L))
    }

    @Test
    fun `same key within window is skipped`() {
        // S9-2 回归守卫：同 key 失败 10min 窗内拒（删窗比较 → 本用例 → 红）
        val b = PrefetchBackoff()
        val t0 = 1_000_000L
        b.recordFailure("k1", t0)
        assertTrue(b.shouldSkip("k1", t0 + 1))
        assertTrue(b.shouldSkip("k1", t0 + 599_999)) // 窗内最后一毫秒
        assertEquals(600_000L, PrefetchBackoff.DEFAULT_WINDOW_MS) // 10min 口径钉死
    }

    @Test
    fun `different keys do not interfere`() {
        val b = PrefetchBackoff()
        b.recordFailure("a", 1_000_000L)
        assertFalse(b.shouldSkip("b", 1_000_001L))
        assertTrue(b.shouldSkip("a", 1_000_001L))
    }

    @Test
    fun `window expiry allows again and success clears ledger`() {
        val b = PrefetchBackoff()
        val t0 = 1_000_000L
        b.recordFailure("k1", t0)
        assertFalse(b.shouldSkip("k1", t0 + 600_000)) // 恰出窗放行（[t, t+10min) 为窗内）
        // 失败刷新窗：窗末再失败 → 以新时刻起算
        b.recordFailure("k1", t0 + 600_000)
        assertTrue(b.shouldSkip("k1", t0 + 1_200_000 - 1))
        assertFalse(b.shouldSkip("k1", t0 + 1_200_000))
        // 成功清账
        b.recordSuccess("k1")
        assertFalse(b.shouldSkip("k1", t0 + 600_001))
    }
}
