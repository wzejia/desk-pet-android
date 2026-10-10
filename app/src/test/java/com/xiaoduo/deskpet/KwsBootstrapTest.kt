package com.xiaoduo.deskpet

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * KwsBootstrap 守卫测试（thread-discipline 件 2，ocr[205] 裸 Thread→service-scoped
 * scope 修复；设计定案 docs/audit/2026-10-10-phase0/thread-discipline-guard-design.md）。
 *
 * 防线目标：service 销毁（scope.cancel）能传播到在途 init——engineFactory 结果
 * 必须被丢弃（onReady 不回调 = 不做 engine 赋值/挂 sink 等破坏性动作）；销毁后
 * 再 bootstrap 拒绝；在途 bootstrap 拒绝重入（原 OnceGate initGate 语义）。
 *
 * 取消构造说明：engineFactory 用 CountDownLatch 普通阻塞（非挂起等待）——
 * 协程 cancel 不打断 latch，factory 正常返回后协程恢复到 isActive 检查点，
 * 才能精确测「结果在检查点被丢弃」（挂起等待会被取消异常提前拆掉，测不到）。
 *
 * 变异锚：删 onReady 前 isActive 检查 → cancelWhileFactoryBlocked 红；
 * 删 job?.isActive 在途拒绝 → reentryRejectedWhileInFlight 红。
 */
class KwsBootstrapTest {

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun cancelWhileFactoryBlocked_dropsResult() {
        val scope = newScope()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val readyCount = AtomicInteger(0)
        val delivered = AtomicReference<String>(null)
        val bs = KwsBootstrap(scope)
        assertTrue(bs.bootstrap(
            engineFactory = {
                started.countDown()
                release.await(5, TimeUnit.SECONDS) // 普通阻塞：cancel 不打断
                "engine"
            },
            onReady = { delivered.set(it); readyCount.incrementAndGet() },
        ))
        assertTrue(started.await(5, TimeUnit.SECONDS))
        scope.cancel() // bootstrap 进行中销毁
        release.countDown() // 放行 factory → 结果应被 isActive 检查丢弃
        // 等 onReady 窗口（若守卫失效它会被调）：轮询至协程收尾
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && readyCount.get() == 0) {
            Thread.sleep(10)
        }
        Thread.sleep(100) // 再给错误路径一点余量
        assertEquals("销毁后 factory 结果必须被丢弃", 0, readyCount.get())
        assertEquals("onReady 不得收到引擎", null, delivered.get())
    }

    @Test
    fun bootstrapRejectedAfterCancel() {
        val scope = newScope()
        val bs = KwsBootstrap(scope)
        scope.cancel()
        var ran = false
        assertFalse("销毁后再 bootstrap 必须拒绝", bs.bootstrap({ "e" }, { ran = true }))
        assertFalse(ran)
    }

    @Test
    fun normalPath_onReadyExactlyOnce() {
        val scope = newScope()
        val readyCount = AtomicInteger(0)
        val bs = KwsBootstrap(scope)
        assertTrue(bs.bootstrap(engineFactory = { "engine" }, onReady = { readyCount.incrementAndGet() }))
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && readyCount.get() == 0) {
            Thread.sleep(10)
        }
        assertEquals(1, readyCount.get())
        scope.cancel()
    }

    @Test
    fun reentryRejectedWhileInFlight() {
        val scope = newScope()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bs = KwsBootstrap(scope)
        assertTrue(bs.bootstrap(
            engineFactory = { started.countDown(); release.await(5, TimeUnit.SECONDS); "e1" },
            onReady = {},
        ))
        assertTrue(started.await(5, TimeUnit.SECONDS))
        var secondRan = false
        assertFalse("在途 bootstrap 必须拒绝重入（原 initGate 语义）", bs.bootstrap({ "e2" }, { secondRan = true }))
        assertFalse(secondRan)
        release.countDown()
        scope.cancel()
    }

    @Test
    fun factoryThrows_reportsFailure_notReady_andAllowsRetry() {
        val scope = newScope()
        val failure = AtomicReference<Throwable>(null)
        val failed = CountDownLatch(1)
        val readyCount = AtomicInteger(0)
        val bs = KwsBootstrap(scope)
        assertTrue(bs.bootstrap(
            engineFactory = { throw IllegalStateException("boom") },
            onReady = { readyCount.incrementAndGet() },
            onFailure = { failure.set(it); failed.countDown() },
        ))
        assertTrue("onFailure 必须被回调", failed.await(5, TimeUnit.SECONDS))
        assertTrue(failure.get() is IllegalStateException)
        assertEquals("失败的 factory 结果不得进 onReady", 0, readyCount.get())
        // 失败后 job 已结束 → 允许重试（原 initGate.release 放行语义）
        assertTrue(bs.bootstrap(engineFactory = { "ok" }, onReady = { readyCount.incrementAndGet() }))
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && readyCount.get() == 0) {
            Thread.sleep(10)
        }
        assertEquals(1, readyCount.get())
        scope.cancel()
    }
}
