package com.xiaoduo.deskpet.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * OnceGate 单测（BLOCKED.md backlog #8 双竞态修复的共用门闩，#11 令牌式加固）：
 * - #8b AudioCapture.start 双调：并发 tryAcquire 只能一个赢家——旧 check-then-act
 *   （if (isRunning) return）下两个调用都过检查，起双读循环抢麦；
 * - #8a KWS init 双线程：同理只放一个 init 线程，后到者不得加载模型；
 * - 持令牌 release 放行下一轮（stop / init 失败后的重启路径），且重复 release 幂等
 *   （AudioCapture.stop 与 readLoop finally 双放不破坏状态）；
 * - #11 过期令牌 release 自动失效：stop→start2 换主后，旧 job finally 的过期 release
 *   不得错放 start2 持有的闩（否则并发 start3 可重现双读循环）。
 */
class OnceGateTest {

    @Test
    fun `concurrent tryAcquire has exactly one winner`() {
        val gate = OnceGate()
        val threads = 32
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val wins = AtomicInteger(0)
        val done = CountDownLatch(threads)
        repeat(threads) {
            Thread {
                ready.countDown()
                go.await()
                if (gate.tryAcquire() != null) wins.incrementAndGet()
                done.countDown()
            }.start()
        }
        assertTrue("worker threads not ready in time", ready.await(5, TimeUnit.SECONDS))
        go.countDown()
        assertTrue("worker threads not done in time", done.await(5, TimeUnit.SECONDS))
        assertEquals("check-then-act 竞态下会出现 >1 个赢家", 1, wins.get())
    }

    @Test
    fun `second caller loses while held`() {
        val gate = OnceGate()
        assertNotNull(gate.tryAcquire())
        assertNull(gate.tryAcquire())
        assertNull(gate.tryAcquire())
    }

    @Test
    fun `release with owner token reopens and double release is idempotent`() {
        val gate = OnceGate()
        val token = gate.tryAcquire()
        assertNotNull(token)
        gate.release(token!!)
        gate.release(token) // 幂等：stop 与读循环 finally 双放不得破坏占位状态
        assertNotNull(gate.tryAcquire())
        assertNull(gate.tryAcquire())
    }

    @Test
    fun `stale token release does not reopen gate`() {
        val gate = OnceGate()
        val stale = gate.tryAcquire()
        assertNotNull(stale)
        gate.release(stale!!)
        val current = gate.tryAcquire()
        assertNotNull(current)
        assertTrue("令牌必须递增不复用，stop 后新 start 的令牌必不同", current!! != stale)
        // backlog #11 理论窗：旧 job 的 finally 晚于 start2 —— 过期 release 必须无效
        gate.release(stale)
        assertNull("过期 release 错放闩后，并发 start3 会出现第二个赢家", gate.tryAcquire())
        // 当前持有者的令牌仍可正常放行
        gate.release(current)
        assertNotNull(gate.tryAcquire())
    }
}
