package com.xiaoduo.deskpet.ir

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * S70 IrWakeController 触发链集成测试（FakeGpioReader 注入 seam，被测对象零 mock）：
 * 验证 enabled 门 / bind 时序 / setInput 门 / IDLE 门 / 异常隔离 / stop 幂等。
 * 协程用真实 Dispatchers.IO + CountDownLatch 门控（项目模式 A/B，无 coroutines-test）。
 */
class IrWakeControllerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** seam fake：手控 open/onReady 时序与电平，全计数可断言（模式 B 手写 fake）。 */
    private class FakeGpioReader : GpioReader {
        val openCalls = AtomicInteger(0)
        val closeCalls = AtomicInteger(0)
        val setInputCalls = AtomicInteger(0)
        val readCalls = AtomicInteger(0)

        @Volatile var onReadyCallback: (() -> Unit)? = null
        @Volatile var setInputResult = true
        @Volatile var level: Boolean? = false
        @Volatile var readThrows: Throwable? = null

        /** P2-1 竞窗用例钩子：setInput 执行期间触发（模拟回调中途并发 stop），null=no-op。 */
        @Volatile var stopHook: (() -> Unit)? = null

        override fun open(onReady: () -> Unit) {
            openCalls.incrementAndGet()
            onReadyCallback = onReady
        }

        override fun close() {
            closeCalls.incrementAndGet()
        }

        override fun setInput(pin: Int): Boolean {
            setInputCalls.incrementAndGet()
            stopHook?.invoke()
            return setInputResult
        }

        override fun read(pin: Int): Boolean? {
            readCalls.incrementAndGet()
            readThrows?.let { throw it }
            return level
        }

        fun fireReady() {
            onReadyCallback?.invoke()
        }
    }

    private class WakeRecorder {
        val count = AtomicInteger(0)
        val latch = CountDownLatch(1)

        fun onWake() {
            if (count.incrementAndGet() == 1) latch.countDown()
        }

        /** 等首次触发；返回是否等到。 */
        fun awaitFirst(timeoutSec: Long = 5): Boolean =
            latch.await(timeoutSec, TimeUnit.SECONDS)
    }

    private fun newController(
        fake: FakeGpioReader,
        enabled: Boolean = true,
        idle: Boolean = true,
        wake: WakeRecorder = WakeRecorder(),
        cooldownMs: Long = 0,
    ): IrWakeController =
        IrWakeController(
            scope = scope,
            gpioReader = fake,
            enabledProvider = { enabled },
            idleGate = { idle },
            onWake = { wake.onWake() },
            filter = PirEdgeFilter(cooldownMs = cooldownMs),
            pollIntervalMs = 5,
            log = {}, // PetLogger 底层 android.util.Log JVM 不可达，测试静音
        )

    // ---- enabled=false：完全不 bind（S70 反例④）----

    @Test
    fun `enabled为false时start完全不打开通道`() {
        val fake = FakeGpioReader()
        val c = newController(fake, enabled = false)
        c.start()
        Thread.sleep(200) // 给错误实现留暴露窗口：若 bind 了这里 openCalls 会 >0
        assertEquals(0, fake.openCalls.get())
        assertEquals(0, fake.setInputCalls.get())
        c.stop()
    }

    // ---- 正链路：bind → onConnect → setInput → 轮询 → 上升沿 → onWake ----

    @Test
    fun `onReady后setInput成功起轮询_上升沿触发onWake`() {
        val fake = FakeGpioReader()
        val wake = WakeRecorder()
        val c = newController(fake, wake = wake)
        c.start()
        assertEquals(1, fake.openCalls.get())
        fake.fireReady() // 模拟 onConnect 回调（binder 线程）
        assertEquals(1, fake.setInputCalls.get())
        fake.level = true
        assertTrue("上升沿应在 5s 内触发 onWake", wake.awaitFirst())
        c.stop()
    }

    // ---- IDLE 门（S70 反例①；S70 变异锚 C：删 idleGate 检查 → 本用例红）----

    @Test
    fun `非IDLE态命中不调onWake`() {
        val fake = FakeGpioReader()
        val wake = WakeRecorder()
        val c = newController(fake, idle = false, wake = wake)
        c.start()
        fake.fireReady()
        fake.level = true
        Thread.sleep(300) // 轮询间隔 5ms，300ms 足够跑 50+ 轮
        assertEquals("非 IDLE 态不得触发 onWake", 0, wake.count.get())
        c.stop()
    }

    // ---- setInput 失败：不起轮询 + 收通道 ----

    @Test
    fun `setInput失败则不起轮询并关闭通道`() {
        val fake = FakeGpioReader()
        val wake = WakeRecorder()
        fake.setInputResult = false
        val c = newController(fake, wake = wake)
        c.start()
        fake.fireReady()
        fake.level = true
        Thread.sleep(300)
        assertEquals(0, wake.count.get())
        assertEquals("setInput 失败应收通道降级", 1, fake.closeCalls.get())
        c.stop()
    }

    // ---- onConnect 永不达：通道静默禁用（S70 反例②）----

    @Test
    fun `onConnect永不达则静默禁用零触发`() {
        val fake = FakeGpioReader()
        val wake = WakeRecorder()
        val c = newController(fake, wake = wake)
        c.start()
        fake.level = true // 电平已在，但通道未就绪：不得触发
        Thread.sleep(300)
        assertEquals(0, fake.setInputCalls.get())
        assertEquals(0, wake.count.get())
        c.stop()
    }

    // ---- stop：幂等 + 迟到 onReady 不轮询 ----

    @Test
    fun `stop幂等且stop后迟到onReady不轮询`() {
        val fake = FakeGpioReader()
        val wake = WakeRecorder()
        val c = newController(fake, wake = wake)
        c.start()
        c.stop()
        c.stop() // 幂等：二次 stop 不得抛异常
        fake.fireReady() // stop 之后 onConnect 才到（binder 迟到）
        fake.level = true
        Thread.sleep(300)
        assertEquals("stop 后 setInput 不得执行", 0, fake.setInputCalls.get())
        assertEquals(0, wake.count.get())
    }

    // ---- P2-1 竞窗：onConnect 回调执行中途并发 stop 不得起孤轮询（S70 变异锚 M5）----

    @Test
    fun `onConnect回调中途并发stop则不起孤轮询`() {
        val fake = FakeGpioReader()
        val wake = WakeRecorder()
        val c = newController(fake, wake = wake)
        // setInput 执行期间并发完成 stop（此时 pollJob 尚为 null）：
        // 若 launch 前无二次 running 检查，setInput 返回后会 launch 无人 cancel 的孤轮询
        fake.stopHook = { c.stop() }
        c.start()
        fake.fireReady()
        fake.level = true
        Thread.sleep(300) // 轮询间隔 5ms，300ms 足以让孤轮询暴露
        assertEquals("并发 stop 后不得起孤轮询触发", 0, wake.count.get())
        assertEquals("stop 已关闭通道", 1, fake.closeCalls.get())
        c.stop() // 收尾幂等
    }

    // ---- 板测 2026-10-26 修复：被状态门丢弃的边沿不得起表冷却（S70 变异锚 M6）----

    @Test
    fun `被状态门丢弃的边沿不得起表冷却`() {
        val fake = FakeGpioReader()
        val wake = WakeRecorder()
        val idle = java.util.concurrent.atomic.AtomicBoolean(false)
        val c = IrWakeController(
            scope = scope,
            gpioReader = fake,
            enabledProvider = { true },
            idleGate = { idle.get() },
            onWake = { wake.onWake() },
            filter = PirEdgeFilter(cooldownMs = 30_000), // 真 30s 冷却：区分「起表 vs 零副作用」
            pollIntervalMs = 5,
            log = {},
        )
        c.start()
        fake.fireReady()
        // 边沿#1：非 IDLE 态 → 被 idleGate 丢弃（旧实现：此处 filter 已起 30s 冷却表）
        fake.level = true
        Thread.sleep(150) // 足够跑完去抖 2 样本 + 丢弃
        assertEquals("非 IDLE 边沿不得触发", 0, wake.count.get())
        // 门开：回落再上升构成边沿#2（与#1 间隔 <5s，远在 30s 冷却窗内）
        idle.set(true)
        fake.level = false
        Thread.sleep(100)
        fake.level = true
        assertTrue(
            "门开后的新边沿应立即触发（被吞边沿不得起表冷却）",
            wake.awaitFirst(),
        )
        c.stop()
    }

    // ---- read 抛异常：log+停轮询降级，绝不向调用方抛 ----

    @Test
    fun `read抛异常则轮询降级停转且不崩溃`() {
        val fake = FakeGpioReader()
        val wake = WakeRecorder()
        fake.readThrows = RuntimeException("binder dead")
        val c = newController(fake, wake = wake)
        c.start()
        fake.fireReady()
        Thread.sleep(200)
        val readsAtFailure = fake.readCalls.get()
        assertTrue("异常前应至少跑过数轮轮询", readsAtFailure >= 1)
        // 异常后轮询停转：读计数不再增长
        Thread.sleep(150)
        assertEquals("read 异常应停轮询（失败降级）", readsAtFailure, fake.readCalls.get())
        // 恢复电平也不得再触发（通道已降级）
        fake.readThrows = null
        fake.level = true
        Thread.sleep(200)
        assertEquals(0, wake.count.get())
        c.stop()
    }

    // ---- 连续两轮触发（cooldownMs=0）：边沿记忆回落后再触发 ----

    @Test
    fun `电平回落后的新上升沿可再次触发`() {
        val fake = FakeGpioReader()
        val wake = WakeRecorder()
        val c = newController(fake, wake = wake)
        c.start()
        fake.fireReady()
        fake.level = true
        assertTrue(wake.awaitFirst())
        fake.level = false  // 回落 2 样本稳定化
        Thread.sleep(100)
        fake.level = true   // 新上升沿
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (wake.count.get() < 2 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals("第二轮上升沿应再触发", 2, wake.count.get())
        c.stop()
    }
}
