package com.xiaoduo.deskpet.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * refPacerLoop 纯逻辑单测（CONC-4）：配速线程任何退出路径都必须清 running 标志。
 * 线程死了标志残留 = startRefPacer 永远早退 = AEC 参考静默死亡 + refPending 无界增长。
 */
class VoicePlayerRefPacerTest {

    private class Harness(var head: Int = 0, var headBoom: Boolean = false, var sleepBoom: Boolean = false) {
        var running = false // 旧实现缺陷形态：setRunning(true) 后异常路径不清
        val drained = mutableListOf<Int>()
        val runningFlag get() = running

        fun loop(): Boolean = refPacerLoop(
            running = { running },
            setRunning = { running = it },
            trackHead = {
                if (headBoom) throw IllegalStateException("track released")
                head
            },
            drain = { drained.add(it) },
            sleep = { if (sleepBoom) throw InterruptedException("stop join race") },
        )
    }

    /** 正常退出：外部 stopper 翻 running=false → 循环退出，返回 true（正常路径标志由
     *  stopper 直写清零，与真实 stopRefPacer 同构，此处只锚「退出即报正常」）。 */
    @Test
    fun `CONC-4 normal stop exits and reports normal`() {
        val h = Harness(head = 100)
        var spins = 0
        val normal = refPacerLoop(
            running = { spins++ < 3 }, // 第 3 次检查翻 false → 正常退出
            setRunning = { h.running = it },
            trackHead = { h.head },
            drain = { h.drained.add(it) },
            sleep = { },
        )
        assertTrue("外部停机 = 正常退出", normal)
        assertTrue("按播放头增量推参考", h.drained.isNotEmpty())
    }

    /** CONC-4 核心红测：trackHead 抛（AudioTrack 已 release）→ 循环退出且 running 必须清零。 */
    @Test
    fun `CONC-4 head exception exits and clears running flag`() {
        val h = Harness(headBoom = true)
        val normal = h.loop()
        assertFalse("异常退出报告 false", normal)
        assertFalse("异常退出后 running 标志必须已清（旧 alive 式实现漏清 → 此处红）", h.runningFlag)
    }

    /** sleep 中断（stopRefPacer join 竞态）→ 同样必须清标志。 */
    @Test
    fun `CONC-4 sleep interrupt exits and clears running flag`() {
        val h = Harness(sleepBoom = true)
        val caught = runCatching { h.loop() }
        assertTrue("中断异常应传播给线程体（由 runCatching 兜）", caught.isFailure)
        assertFalse("中断退出后 running 标志必须已清", h.runningFlag)
    }

    /** 播放头推进 → 参考按帧数×2 字节推给 drain（增量语义回归锚）。 */
    @Test
    fun `CONC-4 head advance drains reference in byte units`() {
        var head = 0
        val drained = mutableListOf<Int>()
        var spins = 0
        refPacerLoop(
            running = { spins++ < 3 },
            setRunning = { },
            trackHead = { head },
            drain = { drained.add(it) },
            sleep = { head += 100 }, // 每轮 sleep 期间播放头前进 100 帧
        )
        assertEquals("100 帧 = 200 字节参考（两轮推进）", listOf(200, 200), drained)
    }
}
