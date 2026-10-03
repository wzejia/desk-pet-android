package com.xiaoduo.deskpet.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** ROB-4：feed/release 串行门不变量（喂入不落 release 之后、teardown 恰一次且见稳定态）。 */
class FeedReleaseGateTest {

    @Test
    fun `feed executes body before close`() {
        val gate = FeedReleaseGate()
        var ran = false
        gate.feed { ran = true }
        assertTrue(ran)
    }

    @Test
    fun `feed after close skips body`() {
        val gate = FeedReleaseGate()
        gate.close { }
        var ran = false
        gate.feed { ran = true }
        assertTrue("close 后 feed 主体不得执行（use-after-release 防线）", !ran)
    }

    @Test
    fun `close teardown runs exactly once`() {
        val gate = FeedReleaseGate()
        var teardowns = 0
        gate.close { teardowns++ }
        gate.close { teardowns++ }
        assertEquals(1, teardowns)
    }

    /** 并发压测：feed 线程与 close 竞跑——teardown 观察到的计数 == 终态计数（无在途喂入）。 */
    @Test
    fun `teardown observes stable counter with feeds racing close`() {
        val gate = FeedReleaseGate()
        var counter = 0
        var seenInTeardown = -1L
        val startGate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)
        val feedDone = CountDownLatch(2)
        repeat(2) {
            pool.submit {
                try {
                    startGate.await()
                    repeat(50_000) { gate.feed { counter++ } }
                } catch (_: InterruptedException) {
                } finally {
                    feedDone.countDown()
                }
            }
        }
        pool.submit {
            try {
                startGate.await()
                gate.close { seenInTeardown = counter.toLong() }
            } catch (_: InterruptedException) {
            }
        }
        startGate.countDown()
        feedDone.await()
        pool.shutdown()
        gate.close { }
        assertEquals("teardown 时刻计数必须等于终态计数（feed 与 release 串行）", counter.toLong(), seenInTeardown)
        // close 后不再有喂入：终态计数稳定
        val stable = counter
        gate.feed { counter++ }
        assertEquals(stable, counter)
    }
}
