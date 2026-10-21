package com.xiaoduo.deskpet.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HOTFIX-2 接线 seam 回归守卫（ce-review 20261020 #1 定向测试）：
 * readLoop 三个裁决点（启动重试 / 终态失败计数 / 全零自愈）统一收敛为纯 seam 后，
 * 裁决序列语义在本类钉死；判定本体各有专属组锚（AudioCaptureZeroBlockDetectorTest /
 * AudioCaptureTerminalStartFailureDetectorTest / SoftwareAgcTest），本类管「裁决怎么接」。
 * readLoop 调用点属 Android 域 JVM 不可达，由守卫注释 + 审查 + 真机取证兜底。
 *
 * 变异方向：改 [startRecordRetryOutcome] 上限比较 → 重试裁决组红；
 * 删 [onCaptureStartOutcome] 成功复位分支 → 接线复位组红；
 * 删 [onCaptureStartOutcome] 失败转发 → 接线连败组红；
 * 改 [onZeroBlockOutcome] 归零/达阈转发 → 全零裁决组红；
 * 改 MAX_START_ATTEMPTS / CAPTURE_REBUILD_DELAY_MS / ZERO_BLOCK_SELF_HEAL_THRESHOLD → 常数锚红。
 */
class AudioCaptureWiringSeamTest {

    // ---- 锚组 1：形态 A 重试裁决（1→RETRY / 2→RETRY / 3→GIVE_UP）----

    @Test
    fun `retry outcome - attempts 1 and 2 retry, attempt 3 gives up`() {
        assertEquals(StartRecordRetry.RETRY, startRecordRetryOutcome(1))
        assertEquals(StartRecordRetry.RETRY, startRecordRetryOutcome(2))
        assertEquals(StartRecordRetry.GIVE_UP, startRecordRetryOutcome(3))
    }

    @Test
    fun `retry outcome - give up is inclusive at the cap (boundary ge, not gt)`() {
        assertEquals(StartRecordRetry.GIVE_UP, startRecordRetryOutcome(3, maxAttempts = 3))
        assertEquals(StartRecordRetry.RETRY, startRecordRetryOutcome(2, maxAttempts = 3))
    }

    @Test
    fun `hotfix2 constants pinned - maxAttempts 3, rebuild delay 250ms, zero threshold 30`() {
        assertEquals(3, MAX_START_ATTEMPTS)
        assertEquals(250L, CAPTURE_REBUILD_DELAY_MS)
        assertEquals(30, ZERO_BLOCK_SELF_HEAL_THRESHOLD)
    }

    // ---- 锚组 2：启动终态失败计数接线（败攒证 / 成复位）----

    @Test
    fun `capture start outcome - three consecutive failures fire exactly once on the third`() {
        val guard = TerminalStartFailureDetector()
        assertFalse(onCaptureStartOutcome(guard, startedOk = false))
        assertFalse(onCaptureStartOutcome(guard, startedOk = false))
        assertTrue(onCaptureStartOutcome(guard, startedOk = false))
    }

    @Test
    fun `capture start outcome - success resets the counter (fail fail succeed never fires)`() {
        val guard = TerminalStartFailureDetector()
        assertFalse(onCaptureStartOutcome(guard, startedOk = false))
        assertFalse(onCaptureStartOutcome(guard, startedOk = false))
        assertFalse(onCaptureStartOutcome(guard, startedOk = true))
        // 复位后须重新计满（跨段不凑数）
        assertFalse(onCaptureStartOutcome(guard, startedOk = false))
        assertFalse(onCaptureStartOutcome(guard, startedOk = false))
        assertTrue(onCaptureStartOutcome(guard, startedOk = false))
    }

    @Test
    fun `capture start outcome - lone success never fires`() {
        val guard = TerminalStartFailureDetector()
        assertFalse(onCaptureStartOutcome(guard, startedOk = true))
    }

    // ---- 锚组 3：全零自愈裁决接线（29 不触发 / 30 触发 / 非零打断重计）----

    @Test
    fun `zero block outcome - 29 all-zero blocks hold, the 30th fires`() {
        val guard = ZeroBlockDetector()
        val zero = ByteArray(VadDetector.BLOCK_BYTES)
        repeat(29) { assertFalse(onZeroBlockOutcome(guard, zero)) }
        assertTrue(onZeroBlockOutcome(guard, zero))
    }

    @Test
    fun `zero block outcome - a non-zero block resets the streak`() {
        val guard = ZeroBlockDetector()
        val zero = ByteArray(VadDetector.BLOCK_BYTES)
        val noisy = ByteArray(VadDetector.BLOCK_BYTES).also { it[0] = 1 }
        repeat(10) { assertFalse(onZeroBlockOutcome(guard, zero)) }
        assertFalse(onZeroBlockOutcome(guard, noisy))
        repeat(29) { assertFalse(onZeroBlockOutcome(guard, zero)) }
        assertTrue(onZeroBlockOutcome(guard, zero))
    }
}
