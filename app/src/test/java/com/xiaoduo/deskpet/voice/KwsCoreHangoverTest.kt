package com.xiaoduo.deskpet.voice

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * KwsCore 能量门挂起窗 + 间隙 reset（真机「第一声必漏、第二声秒中」修复）。
 *
 * 真机探针石锤的两个机制（2026-08-19）：
 * ① 能量门「整块不喂」挖掉喊词内部字间气口的低能量块 → 时间轴压缩，
 *    transducer 打分被压到阈值下（第一声必漏）；
 * ② 深静默块不进流 → 波束里差一口气的部分分永不冲刷，冻结到下一声音事件
 *    补完（假「第二声必中」/应答音莫名迟到）。
 * 修复：过门块后挂起窗（[KwsDefaults.GATE_HANGOVER_BLOCKS] 块）内低于门仍喂真实块
 * （保时间轴连续，不补零——气口弱音对打分有意义）；连续低于门超窗 → 不喂（省电
 * 初衷保住）且对 KWS 流 gap-reset 恰好一次（幂等沿，冲刷冻结尾分）。
 */
class KwsCoreHangoverTest {

    /** 记录型 fake：accept/reset 计数 + 样本留证（验「窗内喂的是真实弱音块，不是补零」）。 */
    private class RecNative : KwsCore.Native {
        val accepts = AtomicInteger(0)
        val resets = AtomicInteger(0)
        val lastSamples = CopyOnWriteArrayList<FloatArray>()

        override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
            accepts.incrementAndGet()
            lastSamples += samples
        }

        override fun isReady(): Boolean = false
        override fun decode() {}
        override fun resultKeyword(): String = ""
        override fun reset() { resets.incrementAndGet() }
        override fun release() {}
    }

    /** 定序 fake：首个 accept 卡住撑开积压窗，钉死 gap-reset sentinel 的时间轴落点。 */
    private class OrderNative : KwsCore.Native {
        val events = CopyOnWriteArrayList<String>()
        val firstAcceptEntered = CountDownLatch(1)
        val firstAcceptGate = CountDownLatch(1)
        private val seen = AtomicInteger(0)

        override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
            if (seen.getAndIncrement() == 0) {
                firstAcceptEntered.countDown()
                firstAcceptGate.await(10, SECONDS)
            }
            events += "accept"
        }

        override fun isReady(): Boolean = false
        override fun decode() {}
        override fun resultKeyword(): String = ""
        override fun reset() { events += "reset" }
        override fun release() {}
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

    /** 低于门但非零（幅值 100：rms ≈ 0.003 < 0.015）——留证窗内喂的是真实弱音块。 */
    private fun faintBlock(): ByteArray {
        val out = ByteArray(3200)
        for (i in 0 until 1600) {
            out[2 * i] = (100 and 0xFF).toByte()
            out[2 * i + 1] = (100 shr 8 and 0xFF).toByte()
        }
        return out
    }

    private fun newCore(native: KwsCore.Native, hangoverBlocks: Int) = KwsCore(
        native = native,
        gateProvider = { 0.015f }, // 对账表默认门限
        onKeyword = {},
        logger = {},
        sampleRate = 16_000,
        queueCapacity = 64,
        hangoverBlocks = hangoverBlocks,
    )

    /** 轮询等条件成立（10ms 间隔，与 KwsCoreTest.awaitLog 同体例）。 */
    private fun awaitTrue(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(10)
        }
        fail("condition not met within ${timeoutMs}ms: $what")
    }

    @Test
    fun `below-gate blocks inside hangover window are fed as real audio`() {
        // 回归守卫（挂起窗）：删此机制 → 窗内低能块被挖 → 时间轴压缩 → 本测试红
        val native = RecNative()
        val core = newCore(native, hangoverBlocks = 3)

        core.feed(loudBlock()) // 过门，开窗 3 块
        core.feed(faintBlock()) // 窗内 1/3：仍喂
        core.feed(faintBlock()) // 窗内 2/3：仍喂
        core.feed(faintBlock()) // 窗内 3/3：仍喂
        awaitTrue("1 loud + 3 in-window faint blocks all fed") { native.accepts.get() == 4 }

        // 喂进去的必须是真实弱音（≈100/32768），不是补零
        val faint = native.lastSamples.last()
        assertTrue(
            "hangover-fed block must be the real faint audio, not zero-fill",
            faint.all { it > 0.002f && it < 0.004f },
        )
        assertEquals("窗内不得 reset", 0, native.resets.get())
        core.release()
    }

    @Test
    fun `below-gate streak beyond window is dropped and gap resets exactly once`() {
        // 回归守卫（gap-reset 幂等沿）：删此机制 → resets=0 → 红；幂等沿破了重复 reset → 红
        val native = RecNative()
        val core = newCore(native, hangoverBlocks = 3)

        core.feed(loudBlock()) // 过门
        repeat(3) { core.feed(faintBlock()) } // 窗内喂
        repeat(5) { core.feed(faintBlock()) } // 超窗：不喂；首个超窗块触发唯一一次 gap-reset
        awaitTrue("gap reset consumed") { native.resets.get() == 1 }
        awaitTrue("queue drained") { native.accepts.get() == 4 }

        Thread.sleep(300) // 反向断言窗口：超窗 streak 不得重复 reset
        assertEquals("连续超窗幂等沿：reset 恰好一次", 1, native.resets.get())
        assertEquals("超窗块不得进流", 4, native.accepts.get())
        core.release()
    }

    @Test
    fun `hangover state machine re-arms on new energy after a flushed streak`() {
        // 全迁移：过门 → 窗内 → 超窗（reset×1）→ 再过门 → 窗内 → 超窗（reset×2）
        val native = RecNative()
        val core = newCore(native, hangoverBlocks = 2)

        core.feed(loudBlock()) // 过门 #1
        repeat(2) { core.feed(faintBlock()) } // 窗内
        repeat(3) { core.feed(faintBlock()) } // 超窗 → reset #1
        awaitTrue("first gap reset") { native.resets.get() == 1 }

        core.feed(loudBlock()) // 过门 #2（重新开窗 + 重新武装 reset 沿）
        repeat(2) { core.feed(faintBlock()) } // 窗内
        repeat(3) { core.feed(faintBlock()) } // 超窗 → reset #2
        awaitTrue("second gap reset after re-arm") { native.resets.get() == 2 }
        awaitTrue("all window blocks fed") { native.accepts.get() == 6 }

        assertEquals(2, native.resets.get())
        assertEquals("2 loud + 2×2 in-window faint", 6, native.accepts.get())
        core.release()
    }

    @Test
    fun `gap reset lands after already-queued audio on the timeline`() {
        // sentinel 定序（守卫四问①）：reset 必须落在积压音频之后、新一轮音频之前——
        // 若退化为 feed 线程直接 reset 或 pending-flag，本定序保证即失去。
        val native = OrderNative()
        val core = newCore(native, hangoverBlocks = 2)

        core.feed(loudBlock()) // 首个 accept 卡住，制造消费积压
        assertTrue(native.firstAcceptEntered.await(10, SECONDS))
        repeat(2) { core.feed(faintBlock()) } // 窗内：入队
        repeat(3) { core.feed(faintBlock()) } // 超窗：首个超窗块入队 GapReset sentinel
        core.feed(loudBlock()) // 新一轮过门音频

        native.firstAcceptGate.countDown()
        awaitTrue("all queued items consumed") { native.events.size == 5 }

        // 时间轴：loud + faint×2 → reset → loud
        assertEquals(
            listOf("accept", "accept", "accept", "reset", "accept"),
            native.events.toList(),
        )
        core.release()
    }
}
