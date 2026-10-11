package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.voice.wakeack.PrefetchSingleFlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * PrefetchSingleFlight 并发契约（ocr[394] 收口）：并发 tryEnter 仅一飞、leave 后重开。
 *
 * 独立成类的理由：WakeAckPrefetcher.maybePrefetch 绑 Context+真线程（JVM 不可测），
 * 而单飞保证是 PrefetchBackoff check-then-act 序列（ocr[394]）闭环的结构前提，
 * 必须有回归测试——否则未来重构无声拆门，竞态复活无人知。
 *
 * 变异锚：
 * - tryEnter 改 check-then-act（`if (!inflight.get()) inflight.set(true)`）→ 用例 1 红
 *   （8 线程 × 8 轮，多线程同过检查位）；
 * - 删 leave / leave 改空操作 → 用例 2 红（门永不重开，预取永久哑掉）。
 */
class PrefetchSingleFlightTest {

    @Test
    fun `并发 tryEnter 仅一飞——CAS 占位协议`() {
        val gate = PrefetchSingleFlight()
        repeat(8) {
            val n = 8
            val start = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val done = CountDownLatch(n)
            val winners = AtomicInteger(0)
            repeat(n) {
                thread {
                    start.await()
                    if (gate.tryEnter()) {
                        winners.incrementAndGet()
                        entered.countDown()
                        Thread.sleep(20) // 占位窗：让同轮其余线程全部撞门
                        gate.leave()
                    }
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue("round 未收敛", entered.await(2, TimeUnit.SECONDS))
            assertTrue("round 线程未收齐", done.await(5, TimeUnit.SECONDS))
            assertEquals("并发 tryEnter 仅一飞（变异锚：CAS→check-then-act → 本行红）", 1, winners.get())
        }
    }

    @Test
    fun `leave 后门重开——daemon finally 语义`() {
        val gate = PrefetchSingleFlight()
        assertTrue("首进放行", gate.tryEnter())
        gate.leave()
        assertTrue("leave 后须可再进（变异锚：删 leave 调用 → 本行红）", gate.tryEnter())
        gate.leave()
    }
}
