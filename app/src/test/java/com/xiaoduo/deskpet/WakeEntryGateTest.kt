package com.xiaoduo.deskpet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WakeEntryGate 守卫测试（thread-discipline 件 1，ocr[202] onWake TOCTOU 修复；
 * 设计定案 docs/audit/2026-10-10-phase0/thread-discipline-guard-design.md）。
 *
 * 防线目标：唤醒回调（解码线程）的「进入唤醒处理」与 service teardown（主线程）
 * 互斥——startListening 不得落在已清完 kwsSink/stopIdleCapture 的 session 上
 * （孤儿 capture 无主清理）；close 必须等待 in-flight 临界区完成。
 *
 * 变异锚：runExclusive 去锁（只查 volatile 标志，check 与执行分离）→
 * closeWaitsForInFlightBlock 与 stress 两测必红；变异存活 = 守卫不真 = 重设计。
 */
class WakeEntryGateTest {

    @Test
    fun closeFirst_runExclusiveIsNoop() {
        val gate = WakeEntryGate()
        gate.close()
        var ran = false
        val accepted = gate.runExclusive { ran = true }
        assertFalse("close 后 runExclusive 必须拒绝", accepted)
        assertFalse("close 后块不得执行", ran)
    }

    @Test
    fun closeWaitsForInFlightBlock() {
        val gate = WakeEntryGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closer = Thread {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            gate.close()
        }
        closer.start()
        var blockRan = false
        val runner = Thread {
            val accepted = gate.runExclusive {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                blockRan = true
            }
            assertTrue("close 前进入的临界区必须被接受", accepted)
        }
        runner.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        // close 线程已启动；barrier 未放行 → close 必须仍阻塞（等 in-flight）
        closer.join(300)
        assertNotEquals(
            "close 必须等待 in-flight 临界区完成（barrier 未放行即返回 = 守卫失效）",
            Thread.State.TERMINATED,
            closer.state,
        )
        release.countDown()
        runner.join(5_000)
        closer.join(5_000)
        assertFalse(runner.isAlive)
        assertFalse(closer.isAlive)
        assertTrue(blockRan)
    }

    @Test
    fun closeIsIdempotent() {
        val gate = WakeEntryGate()
        gate.close()
        gate.close()
        assertFalse(gate.runExclusive { })
    }

    @Test
    fun stress_blockNeverRunsAfterCloseReturns() {
        repeat(1_000) {
            val gate = WakeEntryGate()
            val closeDone = CountDownLatch(1)
            val afterClose = Array(4) { false }
            val spinner = Thread {
                repeat(50) { gate.runExclusive { /* 空临界区 */ } }
            }
            val closer = Thread {
                gate.close()
                closeDone.countDown()
            }
            val afters = Array(4) { i ->
                Thread {
                    assertTrue(closeDone.await(5, TimeUnit.SECONDS))
                    afterClose[i] = gate.runExclusive { }
                }
            }
            spinner.start(); closer.start()
            afters.forEach { it.start() }
            spinner.join(5_000); closer.join(5_000)
            afters.forEach { it.join(5_000) }
            afters.forEachIndexed { i, t ->
                assertFalse("轮 $i 线程 $i 卡死", t.isAlive)
                assertFalse("close 返回后 runExclusive 必须拒绝（轮 $i）", afterClose[i])
            }
        }
    }
}
