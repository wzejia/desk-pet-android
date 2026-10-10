package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.voice.kws.KwsCore
import com.xiaoduo.deskpet.voice.kws.KwsEngine
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * KwsCore：KWS 解码线程/能量门控/检出率统计/release 时序（Fake 注入，被测对象真实实例）。
 *
 * 背景：旧 KwsEngine 整类绑死 sherpa-onnx native（JVM 不可载），竞态与统计逻辑零测。
 * KwsCore 把 native 边界抽为 [KwsCore.Native]，全部逻辑下沉 JVM 可测；
 * 真 sherpa 适配层留在 KwsEngine（Android 绑定层，critic 审查 + 真机取证）。
 */
class KwsCoreTest {

    /** sherpa native 假身：事件序列 + 可控 decode 阻塞/异常/关键词。 */
    private class FakeNative : KwsCore.Native {
        val events = CopyOnWriteArrayList<String>()
        val decodeEntered = CountDownLatch(1)
        val freeCount = AtomicInteger(0)

        /** 非空时 decode 阻塞在此闸门上（撑开 release 竞态窗）。 */
        @Volatile var decodeGate: CountDownLatch? = null
        /** 下一次 decode 抛异常（验证单块异常不杀线程）。 */
        @Volatile var throwOnNextDecode = false
        /** getResult 返回的关键词；reset 后自动清空（一块至多命中一次）。 */
        @Volatile var keyword: String = ""

        @Volatile private var ready = false

        override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
            events += "accept"
            ready = true
        }

        override fun isReady(): Boolean = ready

        override fun decode() {
            events += "decode-enter"
            decodeEntered.countDown()
            if (throwOnNextDecode) {
                throwOnNextDecode = false
                ready = false
                throw RuntimeException("fake native decode boom")
            }
            decodeGate?.awaitUninterruptibly() // native JNI 调用不响应线程中断——假身须同构
            ready = false
            events += "decode-exit"
        }

        /** 不可中断等待：吞掉 InterruptedException 直到闸门真正开启（模拟 native decode 语义；
         *  检查 await 返回值——超时不算开闸，否则「永久卡死」10s 后假身自开，失去建模能力）。 */
        private fun CountDownLatch.awaitUninterruptibly() {
            while (true) {
                try {
                    if (await(10, SECONDS)) return
                    // 超时返回 false：继续等，保持「卡死」语义
                } catch (ie: InterruptedException) {
                    // native 解码不响应 interrupt，继续等闸门
                }
            }
        }

        override fun resultKeyword(): String = keyword

        override fun reset() {
            events += "reset"
            keyword = ""
        }

        override fun release() {
            freeCount.incrementAndGet()
            events += "free"
        }
    }

    private fun loudBlock(): ByteArray {
        // 幅值 4000：rms ≈ 4000/32768 ≈ 0.122 > 0.015 门控
        val out = ByteArray(3200)
        for (i in 0 until 1600) {
            out[2 * i] = (4000 and 0xFF).toByte()
            out[2 * i + 1] = (4000 shr 8 and 0xFF).toByte()
        }
        return out
    }

    private fun quietBlock(): ByteArray = ByteArray(3200) // rms=0，必被门控拦

    private fun newCore(
        native: KwsCore.Native,
        logs: MutableList<String>,
        now: () -> Long = System::currentTimeMillis,
        onKeyword: (String) -> Unit = {},
    ) = KwsCore(
        native = native,
        gateProvider = { 0.015f }, // 对账表默认门限
        onKeyword = onKeyword,
        logger = { msg -> logs += msg },
        sampleRate = 16_000,
        queueCapacity = 64,
        now = now,
    )

    /** 轮询等日志行出现（与 awaitState 同体例：10ms 间隔）。 */
    private fun awaitLog(logs: List<String>, needle: String, timeoutMs: Long = 10_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            logs.firstOrNull { it.contains(needle) }?.let { return it }
            Thread.sleep(10)
        }
        fail("log line not found within ${timeoutMs}ms: '$needle'; logs=$logs")
        error("unreachable") // JUnit4 fail() 返回 Unit，编译器需要显式不返回
    }

    @Test
    fun `decode thread priority is configurable - main elevated shadow stays min`() {
        // S35（2026-09-11 取证）：MIN_PRIORITY 在 rk3568 对话期被饿到 7.5 块/s（喂 10），
        // 队列恒满丢最旧 → 时间轴撕裂 → 唤醒必漏（diff 实测 +940/+283 块）。
        // 主引擎默认 6（高于普通 5、低于 UI nice -4）：争抢时保解码吞吐，UI 不被饿
        // （S25b 白屏回归锚：装机后 Choreographer 复测）。影子仍 MIN。
        // 变异锚：删 decodeThread 的 priority 赋值 → 默认 6 断言红。
        val logs = CopyOnWriteArrayList<String>()
        val coreMain = newCore(FakeNative(), logs)
        assertEquals(
            "主引擎解码线程默认应为提档优先级 6",
            6,
            coreMain.decodeThreadForTest.priority,
        )
        coreMain.release()

        val coreShadow = KwsCore(
            native = FakeNative(),
            gateProvider = { 0.015f },
            onKeyword = {},
            logger = {},
            sampleRate = 16_000,
            queueCapacity = 64,
            decodeThreadPriority = Thread.MIN_PRIORITY,
        )
        assertEquals(
            "影子引擎保持 S25b MIN 档（观测通道不与主链争抢）",
            Thread.MIN_PRIORITY,
            coreShadow.decodeThreadForTest.priority,
        )
        coreShadow.release()
    }

    @Test
    fun `release frees native only after in-flight decode returns`() {
        // 回归守卫：release 的 join（删此 → free 抢在 decode-exit 前 → 本测试红）
        val native = FakeNative()
        native.decodeGate = CountDownLatch(1) // decode 阻塞 = 复刻 native 解码进行中
        val logs = CopyOnWriteArrayList<String>()
        val core = newCore(native, logs)

        core.feed(loudBlock())
        assertTrue("decode thread should enter native decode", native.decodeEntered.await(10, SECONDS))

        val releaseDone = CountDownLatch(1)
        Thread({ core.release(); releaseDone.countDown() }, "test-release").start()
        Thread.sleep(200) // 给无 join 的旧实现一个确定的抢跑窗口
        assertFalse(
            "decode 仍在 native 内，native 句柄绝不得先释放（use-after-free）",
            native.events.contains("free"),
        )

        native.decodeGate!!.countDown()
        assertTrue("release should complete after decode returns", releaseDone.await(10, SECONDS))
        val freeIdx = native.events.indexOf("free")
        val exitIdx = native.events.indexOf("decode-exit")
        assertTrue("free($freeIdx) must come after decode-exit($exitIdx)", freeIdx > exitIdx)
    }

    @Test
    fun `release is idempotent and frees native exactly once`() {
        // 回归守卫：released CAS（删此 → 并发/重复 release 双 free → freeCount=2 → 红）
        val native = FakeNative()
        val logs = CopyOnWriteArrayList<String>()
        val core = newCore(native, logs)

        core.feed(loudBlock())
        assertTrue(native.decodeEntered.await(10, SECONDS))
        core.release()
        core.release()
        assertEquals(1, native.freeCount.get())
    }

    @Test
    fun `keyword hit is counted and logged with running tally`() {
        // 检出率度量核心：命中必须带流水号落日志，否则检出率无原始数据
        val native = FakeNative()
        native.keyword = "小朵小朵"
        val logs = CopyOnWriteArrayList<String>()
        val hitLatch = CountDownLatch(1)
        val hits = CopyOnWriteArrayList<String>()
        val core = newCore(native, logs, onKeyword = { kw -> hits += kw; hitLatch.countDown() })

        core.feed(loudBlock())
        assertTrue(hitLatch.await(10, SECONDS))
        assertEquals(listOf("小朵小朵"), hits.toList())
        val line = awaitLog(logs, "keyword hit: 小朵小朵")
        assertTrue("hit log must carry running tally: $line", line.contains("#1"))
        core.release()
    }

    @Test
    fun `decode error drops one block but decode thread survives`() {
        // 旧行为保持：单块解码异常透传 msg 丢块续跑，不杀线程
        val native = FakeNative()
        native.throwOnNextDecode = true
        val logs = CopyOnWriteArrayList<String>()
        val hitLatch = CountDownLatch(1)
        val core = newCore(native, logs, onKeyword = { hitLatch.countDown() })

        core.feed(loudBlock()) // 第一块 decode 抛异常
        awaitLog(logs, "decode error")
        assertTrue(native.events.contains("reset"))

        native.keyword = "小朵小朵"
        core.feed(loudBlock()) // 第二块正常命中 → 线程活着
        assertTrue("decode thread must survive a native exception", hitLatch.await(10, SECONDS))
        core.release()
    }

    @Test
    fun `stats tick logs fed gated decoded hits counters`() {
        // 检出率统计：周期行必须含四个计数，缺一个分母检出率就算不出来。
        // 确定性设计：先拨时钟再喂唯一 loud block——单次 decode 迭代必然触发 tick，
        // 不存在「迭代 1 的 maybeLogStats 看到已拨时钟」的竞态窗（review #6）。
        val clock = AtomicLong(0)
        val native = FakeNative()
        val logs = CopyOnWriteArrayList<String>()
        val core = newCore(native, logs, now = clock::get)

        core.feed(quietBlock()) // gated=1
        core.feed(quietBlock()) // gated=2
        clock.addAndGet(61_000) // 越过统计间隔（此刻无任何 decode 在飞）
        core.feed(loudBlock()) // fed=1, decoded→1, 触发 stats tick

        val line = awaitLog(logs, "kws stats:")
        assertTrue(line, line.contains("fed=1"))
        assertTrue(line, line.contains("gated=2"))
        assertTrue(line, line.contains("decoded=1"))
        assertTrue(line, line.contains("hits=0"))
        core.release()
    }

    @Test
    fun `release logs final summary with counters`() {
        val native = FakeNative()
        val logs = CopyOnWriteArrayList<String>()
        val core = newCore(native, logs)

        core.feed(loudBlock())
        assertTrue(native.decodeEntered.await(10, SECONDS))
        core.release()
        val line = awaitLog(logs, "released")
        assertTrue(line, line.contains("fed=1"))
        assertTrue(line, line.contains("hits="))
    }

    @Test
    fun `feed after release is dropped`() {
        val native = FakeNative()
        val logs = CopyOnWriteArrayList<String>()
        val core = newCore(native, logs)

        core.release()
        val acceptsBefore = native.events.count { it == "accept" }
        core.feed(loudBlock())
        Thread.sleep(200) // 反向断言：给潜在错误消费一个确定窗口
        assertEquals(acceptsBefore, native.events.count { it == "accept" })
    }

    @Test
    fun `release with idle engine returns promptly`() {
        // join 路径 sanity：队列空时 interrupt 立即使 queue.take 抛 InterruptedException，
        // release 不得拖到 join 超时（2s）
        val native = FakeNative()
        val logs = CopyOnWriteArrayList<String>()
        val core = newCore(native, logs)

        val t0 = System.currentTimeMillis()
        core.release()
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("idle release took ${elapsed}ms, should be near-instant", elapsed < 1_500)
        assertEquals(1, native.freeCount.get())
    }

    @Test
    fun `stuck decode thread skips native free and never frees late`() {
        // 回归守卫：join 超时分支（删此 → 卡死线程照 free → use-after-free 复活 → 本测试红）
        // leak-over-crash：decode 卡死超过 JOIN_TIMEOUT_MS → 不 free、落 skip 日志；
        // 之后闸门开启线程退出，也不得补 free（free 责任已随 skip 放弃）。
        val native = FakeNative()
        native.decodeGate = CountDownLatch(1) // 永不开闸（测试收尾才开）
        val logs = CopyOnWriteArrayList<String>()
        val core = newCore(native, logs)
        try {
            core.feed(loudBlock())
            assertTrue(native.decodeEntered.await(10, SECONDS))

            val t0 = System.currentTimeMillis()
            core.release() // 阻塞至 join 超时（~2s）
            val elapsed = System.currentTimeMillis() - t0
            assertTrue("stuck release should return near join timeout, took ${elapsed}ms", elapsed >= 1_000)
            assertEquals("decode 卡死时绝不得 free native", 0, native.freeCount.get())
            awaitLog(logs, "skip native free")
        } finally {
            native.decodeGate!!.countDown() // 放行卡死线程退出
        }
        Thread.sleep(300) // 给「补 free」一个确定的暴露窗口
        assertEquals("skip 之后不得补 free", 0, native.freeCount.get())
    }

    @Test
    fun `keyword decoded during release is logged but not dispatched`() {
        // 回归守卫：onKeyword 派发前的 running 复查（删此 → release 进行中命中照派发
        // → 已毁服务幻影唤醒 → 本测试红）
        val native = FakeNative()
        native.decodeGate = CountDownLatch(1)
        native.keyword = "小朵小朵"
        val logs = CopyOnWriteArrayList<String>()
        val hits = CopyOnWriteArrayList<String>()
        val core = newCore(native, logs, onKeyword = { kw -> hits += kw })

        core.feed(loudBlock())
        assertTrue(native.decodeEntered.await(10, SECONDS))
        val releaseDone = CountDownLatch(1)
        Thread({ core.release(); releaseDone.countDown() }, "test-release").start()
        Thread.sleep(200) // 确保 release 已置 running=false 并进入 join
        native.decodeGate!!.countDown() // decode 返回 → 命中在 release 进行中解码出来
        assertTrue(releaseDone.await(10, SECONDS))

        assertEquals("release 已开始，命中绝不得派发", 0, hits.size)
        awaitLog(logs, "dropped")
    }

    @Test
    fun `native free failure is logged not swallowed`() {
        // CONSTRUCTION 纪律 5：catch 不静默吞——free 失败必须落日志
        val native = object : KwsCore.Native {
            override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {}
            override fun isReady(): Boolean = false
            override fun decode() {}
            override fun resultKeyword(): String = ""
            override fun reset() {}
            override fun release() { throw RuntimeException("sherpa free boom") }
        }
        val logs = CopyOnWriteArrayList<String>()
        val core = newCore(native, logs)

        core.release()
        awaitLog(logs, "native free failed")
    }
}
