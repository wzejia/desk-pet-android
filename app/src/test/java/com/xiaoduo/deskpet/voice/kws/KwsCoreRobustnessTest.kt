package com.xiaoduo.deskpet.voice.kws

import com.xiaoduo.deskpet.voice.kws.KwsCore.Native
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * KwsCore decode 线程族健壮性批（spec §5.2 / ocr[13..19]，2026-10-10 P3-T1）：
 * - [13] GapReset sentinel 被 drop-oldest 静默丢（假命中源，不可恢复）；
 * - [14] onKeyword 异常误报 decode error + decoded 计数漂移；
 * - [15] native 桥 Error 逃出只 catch Exception → 线程静默死亡 KWS 全聋；
 * - [16] running check-then-act：release 返回后命中仍可派发（幻影唤醒）；
 * - [17] feed rms 探针行默认 locale（逗号小数破 grep 口径）；
 * - [18] 统计/uptime 默认墙钟（NTP 回拨冻统计行）→ 单调钟；
 * - [19] release 的 join 被 InterruptedException 冲出 → native 永久漏 free。
 *
 * 变异锚逐测试内注明（删守卫 → 实测红）。
 */
class KwsCoreRobustnessTest {

    /** 可控假身：accept 闸门（撑积压窗）+ 关键词 + 可注入 decode 异常/fatal。 */
    private class FakeNative : Native {
        val events = CopyOnWriteArrayList<String>()
        val acceptEntered = CountDownLatch(1)
        val freeCount = AtomicInteger(0)

        /** 非空时首个 accept 卡闸（复刻消费积压，队列得以填满）。 */
        @Volatile var acceptGate: CountDownLatch? = null
        @Volatile var keyword: String = ""

        /** 下一次 decode 抛该 Throwable（RuntimeException=块异常 / Error=fatal）。 */
        @Volatile var throwOnNextDecode: Throwable? = null

        @Volatile private var ready = false

        override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
            acceptEntered.countDown()
            acceptGate?.let { gate ->
                acceptGate = null // 只卡首块
                gate.awaitUninterruptiblyLoop()
            }
            events += "accept"
            ready = true
        }

        /** native 调用不响应中断——假身同构（吞中断直到真开闸，超时续等保「卡死」语义）。 */
        private fun CountDownLatch.awaitUninterruptiblyLoop() {
            while (!await(10, SECONDS)) { /* 卡死语义：继续等 */ }
        }

        override fun isReady(): Boolean = ready

        override fun decode() {
            events += "decode-enter"
            throwOnNextDecode?.let { t ->
                throwOnNextDecode = null
                ready = false
                throw t
            }
            ready = false
            events += "decode-exit"
        }

        override fun resultKeyword(): String = keyword
        override fun reset() { events += "reset"; keyword = "" }
        override fun release() { freeCount.incrementAndGet(); events += "free" }
    }

    private fun loudBlock(): ByteArray {
        // 幅值 4000：rms ≈ 0.122 > 0.015 门控
        val out = ByteArray(3200)
        for (i in 0 until 1600) {
            out[2 * i] = (4000 and 0xFF).toByte()
            out[2 * i + 1] = (4000 shr 8 and 0xFF).toByte()
        }
        return out
    }

    /** 低于门但非零（rms ≈ 0.003 < 0.015）。 */
    private fun faintBlock(): ByteArray {
        val out = ByteArray(3200)
        for (i in 0 until 1600) {
            out[2 * i] = (100 and 0xFF).toByte()
            out[2 * i + 1] = (100 shr 8 and 0xFF).toByte()
        }
        return out
    }

    private class Harness {
        val native = FakeNative()
        val logs = CopyOnWriteArrayList<String>()
        val clock = AtomicLong(0)
        @Volatile var dispatchedNormally = false
    }

    private fun newCore(
        h: Harness,
        queueCapacity: Int = 64,
        hangoverBlocks: Int = KwsDefaults.GATE_HANGOVER_BLOCKS,
        onKeyword: (String) -> Unit = {},
    ) = KwsCore(
        native = h.native,
        gateProvider = { 0.015f },
        onKeyword = onKeyword,
        logger = { msg -> h.logs += msg },
        sampleRate = 16_000,
        queueCapacity = queueCapacity,
        now = h.clock::get,
        hangoverBlocks = hangoverBlocks,
    )

    private fun awaitLog(logs: List<String>, needle: String, timeoutMs: Long = 10_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            logs.firstOrNull { it.contains(needle) }?.let { return it }
            Thread.sleep(10)
        }
        fail("log line not found within ${timeoutMs}ms: '$needle'; logs=$logs")
        error("unreachable")
    }

    private fun awaitTrue(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(10)
        }
        fail("condition not met within ${timeoutMs}ms: $what")
    }

    // ---------------- ocr[13] sentinel 不可被 drop-oldest 丢弃 ----------------

    @Test
    fun `gap reset sentinel survives drop-oldest eviction under queue backlog`() {
        // 变异锚：offer() 回退纯 drop-oldest（挤到 sentinel 不回插）→ L9 入队把 sentinel
        // 挤出 → 消费时间轴无 reset → 本测试红（冻结部分分泄漏成假命中的通路复活）。
        val h = Harness()
        val firstAcceptGate = CountDownLatch(1)
        h.native.acceptGate = firstAcceptGate // 首 accept 卡闸 = 复刻消费积压
        val core = newCore(h, queueCapacity = 4, hangoverBlocks = 0)

        core.feed(loudBlock()) // L1：消费线程取走并卡闸
        assertTrue("decode 线程须先消费 L1 再填队（定序前提）", h.native.acceptEntered.await(10, SECONDS))
        repeat(4) { core.feed(loudBlock()) } // L2~L5：队列满 [L2,L3,L4,L5]

        core.feed(faintBlock()) // Q1：超窗触发 sentinel（满队挤掉 L2）→ [L3,L4,L5,S]
        repeat(4) { core.feed(loudBlock()) } // L6~L8 逐个挤陈旧块 → [S,L6,L7,L8]；L9 的 offer 撞上 sentinel
        // 旧实现：L9 挤掉 sentinel → [L6,L7,L8,L9]
        // 新实现：挤到 sentinel 回插它、改丢陈旧 L6 → [S,L7,L8,L9]

        firstAcceptGate.countDown() // 放行消费，排空队列
        awaitTrue("queue drained") { h.native.events.count { it == "accept" } >= 4 }
        Thread.sleep(200) // 反向断言窗：给潜在多消费一个暴露机会

        // 新时间轴（sentinel 回插落队尾，仍在最新音频 L9 之前）：
        // L1 → L7 → L8 → reset → L9（L3~L6 逐轮让位，L6 被让位丢弃）
        assertEquals(
            "sentinel 必须活着落到积压音频之后（events=${h.native.events}）",
            listOf("accept", "accept", "accept", "reset", "accept"),
            h.native.events.filter { it == "accept" || it == "reset" },
        )
        core.release()
    }

    // ---------------- ocr[14] onKeyword 异常与 decode 状态隔离 ----------------

    @Test
    fun `keyword callback exception is isolated from decode state and telemetry`() {
        // 变异锚：拆掉回调隔离（onKeyword 裸调回 try/catch(Exception) 内）→ 回调异常
        // 误报 "decode error" 且 decoded 漏计（stats 行 decoded=0）→ 两断言红。
        val h = Harness()
        h.native.keyword = "小朵小朵"
        var calls = 0
        val core = newCore(h, onKeyword = { _ ->
            calls++
            throw RuntimeException("callback boom")
        })
        h.clock.addAndGet(61_000) // 建核后拨钟再喂唯一块：本块解码完必触发 stats tick（无在飞竞态）

        core.feed(loudBlock()) // 命中 + 回调炸
        awaitLog(h.logs, "keyword callback failed")
        assertFalse(
            "回调异常不得误报 decode error（logs=$h.logs）",
            h.logs.any { it.contains("decode error") },
        )
        val line = awaitLog(h.logs, "kws stats:")
        assertTrue("回调炸掉不得漂移 decoded 计数: $line", line.contains("decoded=1"))
        assertTrue("命中计数照常: $line", line.contains("hits=1"))
        assertEquals("回调每命中恰好调一次", 1, calls)
        core.release()
    }

    // ---------------- ocr[15] fatal Error 可观测 + 停收 + release 照常 ----------------

    @Test
    fun `fatal error from native bridge is observable and stops feed churn`() {
        // 变异锚：删 catch(Throwable) 分支（回退只 catch Exception）→ 线程静默死亡：
        // 无 "decode fatal" 日志（awaitLog 超时红）、running 挂真 feed 照常入队（accept=2 红）。
        val h = Harness()
        h.native.throwOnNextDecode = LinkageError("fake unsatisfied link")
        val core = newCore(h)

        core.feed(loudBlock())
        awaitLog(h.logs, "decode fatal")

        core.feed(loudBlock()) // 线程已死：feed 必须停收（旧实现 running 挂真 → 照常入队 churn）
        Thread.sleep(200)
        assertEquals(
            "decode 线程死后 feed 不得继续入队（空转 churn）",
            1,
            h.native.events.count { it == "accept" },
        )

        core.release() // 死线程 join 立即返回：native 照常释放（一次）
        assertEquals(1, h.native.freeCount.get())
        awaitLog(h.logs, "released,")
    }

    // ---------------- ocr[16] 派发与 release 互斥（check-then-act 收口） ----------------

    @Test
    fun `release waits for in-flight dispatch instead of racing past it`() {
        // 变异锚：删 dispatchLock（回退裸 running 复查）→ release 的 running=false 写与
        // interrupt 抢进派发中段：onKeyword 被 interrupt 腰斩（误报 decode error）、
        // release 抢先返回 → 三断言红。
        val h = Harness()
        h.native.keyword = "小朵小朵"
        val dispatchEntered = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        val core = newCore(h, onKeyword = { _ ->
            dispatchEntered.countDown()
            releaseDispatch.await(10, SECONDS)
            h.dispatchedNormally = true
        })

        core.feed(loudBlock())
        assertTrue(dispatchEntered.await(10, SECONDS)) // 派发中段（持 dispatchLock）

        val releaseDone = CountDownLatch(1)
        Thread({ core.release(); releaseDone.countDown() }, "test-release").start()
        Thread.sleep(500) // 给旧实现「抢跑 + interrupt 打断回调 + join 快速返回」一个确定窗口

        assertFalse(
            "派发进行中 release 不得越过互斥先返回（幻影唤醒窗）",
            releaseDone.await(10, TimeUnit.MILLISECONDS),
        )
        releaseDispatch.countDown()
        assertTrue("release 应在派发完成后收尾", releaseDone.await(10, SECONDS))
        assertTrue("在飞派发必须完整走完（旧实现被 interrupt 腰斩）", h.dispatchedNormally)
        assertFalse(
            "派发不得被误报 decode error（logs=$h.logs）",
            h.logs.any { it.contains("decode error") },
        )
        assertEquals("release 完整收尾后 native 释放恰好一次", 1, h.native.freeCount.get())
    }

    // ---------------- ocr[19] release 自身被中断不炸、不漏 free ----------------

    @Test
    fun `release survives caller interruption and still frees native`() {
        // 变异锚：回退裸 join（不 catch InterruptedException）→ 调用方带中断进 release 时
        // 异常冲出、released CAS 已消费 → freeCount=0 + 异常外抛 → 两断言红。
        val h = Harness()
        val core = newCore(h)

        core.feed(loudBlock())
        awaitTrue("decode consumed") { h.native.events.count { it == "decode-exit" } == 1 }

        Thread.currentThread().interrupt() // 模拟 onDestroy 主线程带中断进入
        try {
            core.release() // 旧实现：InterruptedException 从 join 冲出 release()
        } catch (ie: InterruptedException) {
            fail("release 不得向调用方外抛 InterruptedException（native 永久漏 free）")
        }
        assertEquals("中断不得吞掉 native 释放", 1, h.native.freeCount.get())
        assertTrue("中断标志须还原给调用方", Thread.interrupted()) // 顺带清位，不污染后续测试
    }

    // ---------------- ocr[17] 探针行 locale 钉死 ----------------

    @Test
    fun `feed rms probe line is pinned to US decimal point regardless of default locale`() {
        // 变异锚：回退 ".format"（默认 locale）→ comma-decimal 环境渲染 "0,0421" → 红。
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("feed rms=0.0421", feedRmsLine(0.0421f))
        } finally {
            Locale.setDefault(original)
        }
    }

    // ---------------- ocr[18] 默认时钟单调域 ----------------

    @Test
    fun `default stats clock lives in monotonic nanotime domain`() {
        // 变异锚：把 KwsCore.MONOTONIC_NOW 回退 System::currentTimeMillis → 值落墙钟域
        // （epoch 与 nanoTime 域差为机器 uptime/epoch 量级，远超 60s 容差）→ 域断言红。
        val v1 = KwsCore.MONOTONIC_NOW()
        val nanoMs = System.nanoTime() / 1_000_000
        assertTrue(
            "默认统计钟须在 nanoTime 单调域（防 NTP 回拨冻统计行 / uptime 变墙钟时长）: v1=$v1 nano=$nanoMs",
            Math.abs(v1 - nanoMs) < 60_000,
        )
        assertTrue("单调钟读数非递减", KwsCore.MONOTONIC_NOW() >= v1)
    }
}
