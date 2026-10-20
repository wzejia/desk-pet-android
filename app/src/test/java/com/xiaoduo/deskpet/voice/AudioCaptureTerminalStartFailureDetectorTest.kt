package com.xiaoduo.deskpet.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自愈重启钩回归守卫（纯逻辑面，JVM 可测；样式照 AudioCaptureZeroBlockDetectorTest）：
 * - [TerminalStartFailureDetector]：2 轮不触发 / 第 3 轮触发（阈值锚 3≠2）/ fire 后须重新
 *   计满（自尽与冷却两场景统一「fire 即复位」）/ 成功进录音态复位（真实录到音才算病愈，
 *   无关 stop/start 不清证据链）/ 多段成功复位不得跨段凑数。
 * - 常数钉死：阈值 3（连续 3 轮才自尽）；冷却 5min 且 ≥60s（保 STICKY 退避恒复位 1s，
 *   AOSP 7.1.1_r6 ActiveServices：存活 <60s ×4 无封顶）。
 *
 * 变异方向：删 fire 复位（达阈不归零）→ fire 后重计组红；删成功复位（reset 空转）→
 * 成功复位组红；改阈值/冷却常数 → 常数锚红。
 * readLoop 计数/复位调用点在 Android IO 层（JVM 不可达）：以 AudioCapture.kt 内回归守卫
 * 注释锚定，降级 critic 代码审查 + 真机取证（HOTFIX-2 既定口径）。
 */
class AudioCaptureTerminalStartFailureDetectorTest {

    // ---------------- TerminalStartFailureDetector：阈值与连续性 ----------------

    /** 阈值锚（3≠2）：连续 2 轮终态失败不触发，第 3 轮触发（守卫四问 #3：形态 A 瞬态由
     *  轮内 3×250ms 重试兜住，全败才计 1 轮；连续 3 轮全败才是僵尸/长病态信号）。 */
    @Test
    fun `two consecutive terminal failures hold and the third fires`() {
        val d = TerminalStartFailureDetector()
        assertFalse("第 1 轮不得触发", d.onTerminalFailure())
        assertFalse("第 2 轮不得触发", d.onTerminalFailure())
        assertTrue("第 3 轮必须触发", d.onTerminalFailure())
    }

    /** M1 锚·fire 即复位：触发后须重新计满阈值才再触发（自尽=进程即死无副作用；冷却=
     *  复位后重新攒证据——删 fire 复位则触发后每轮都 fire，本组红）。 */
    @Test
    fun `after each fire the detector requires a fresh full streak repeatedly`() {
        val d = TerminalStartFailureDetector()
        repeat(5) { round ->
            repeat(2) { assertFalse("round=$round 重数期不得提前触发", d.onTerminalFailure()) }
            assertTrue("round=$round 第 3 轮必须再次触发", d.onTerminalFailure())
        }
    }

    /** M2 锚·成功进录音态复位：2 轮失败→成功→重新计满 3 轮才再触发（删 reset 语义则
     *  2+2=第 4 次喂入即提前 fire，本组红）。 */
    @Test
    fun `success reset clears the streak and recount needs a full three`() {
        val d = TerminalStartFailureDetector()
        assertFalse(d.onTerminalFailure())
        assertFalse(d.onTerminalFailure())
        d.reset() // 成功进录音态（readLoop isRunning=true 处调用）
        assertFalse("复位后第 1 轮不得触发", d.onTerminalFailure())
        assertFalse("复位后第 2 轮不得触发", d.onTerminalFailure())
        assertTrue("复位后须重满 3 轮才触发", d.onTerminalFailure())
    }

    /** 多段「2 轮失败+成功复位」不得跨段凑数：证据链只在真实成功时清，凑不满 3 连不触发。 */
    @Test
    fun `repeated resets never accumulate toward the threshold`() {
        val d = TerminalStartFailureDetector()
        repeat(4) {
            assertFalse("段内第 1 轮不得触发", d.onTerminalFailure())
            assertFalse("段内第 2 轮不得触发", d.onTerminalFailure())
            d.reset() // 成功进录音态
        }
        assertFalse("复位后重数：第 1 轮不得触发", d.onTerminalFailure())
        assertFalse("复位后重数：第 2 轮不得触发", d.onTerminalFailure())
        assertTrue("复位后重数：第 3 轮才触发", d.onTerminalFailure())
    }

    // ---------------- 常数钉死 ----------------

    /** M3 锚·阈值钉死 3（改 2 → 本锚+连败组双红）。 */
    @Test
    fun `threshold constant pins three rounds`() {
        assertEquals(3, TERMINAL_START_FAILURE_THRESHOLD)
    }

    /** 冷却常数钉死：建议 5min；且必须 ≥60s——STICKY 重启退避在存活 <60s 时 ×4 无封顶
     *  （AOSP 7.1.1_r6 ActiveServices.java:98/102/106），冷却窗兼保存活 ≥60s 使退避恒复位 1s。 */
    @Test
    fun `cooldown constant pins five minutes and at least sixty seconds`() {
        assertEquals(5 * 60 * 1000L, SELF_HEAL_RESTART_COOLDOWN_MS)
        assertTrue("冷却窗必须 ≥60s（保 STICKY 退避复位）", SELF_HEAL_RESTART_COOLDOWN_MS >= 60_000L)
    }
}
