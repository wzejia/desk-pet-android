package com.xiaoduo.deskpet.agui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * CONC-7：ThreadIdSlots 槽位逻辑单测（PrefsThreadIdStore 的可提取纯逻辑）。
 * 旧缺陷：DEFAULT 键与 legacy 键同槽 + promotion 把 legacy **move** 走——
 * 默认会话 threadId 被偷（服务端连续性静默丢失）；默认会话重建后新写入 legacy 又被
 * 新会话 adopt（两会话共享同一服务端 thread）；promotion 本身 check-then-act 无同步。
 */
class AguiClientFactoryThreadIdSlotsTest {

    /** 内存背衬（模拟 prefs 读写删）。 */
    private class Mem {
        val map = HashMap<String, String>()
        fun slots(): ThreadIdSlots = ThreadIdSlots(
            get = { map[it] },
            put = { k, v -> map[k] = v },
            del = { map.remove(it) },
        )
    }

    @Test
    fun `CONC-7 promotion must not steal default session threadId`() {
        val mem = Mem().apply { map["thread_id"] = "L" }
        val slots = mem.slots()

        assertEquals("首个非默认会话 adopt legacy（升级迁移语义保留）", "L", slots.load("s1"))
        assertEquals("legacy 槽必须保留（copy 不 move），实际=" + mem.map, "L", mem.map["thread_id"])
        assertEquals("默认会话 threadId 不动（服务端连续性）", "L", slots.load(AguiClient.DEFAULT_THREAD_KEY))
    }

    @Test
    fun `CONC-7 second new session must not adopt legacy after first promotion`() {
        val mem = Mem().apply { map["thread_id"] = "L" }
        val slots = mem.slots()
        assertEquals("L", slots.load("s1"))
        // 默认会话照常继续使用 legacy 槽（save(DEFAULT) 直写 legacy）
        slots.save(AguiClient.DEFAULT_THREAD_KEY, "D2")

        assertNull("迁移旗后的新会话不得 adopt legacy（防两会话共享服务端 thread）", slots.load("s2"))
        assertEquals("默认会话 id 不受影响", "D2", slots.load(AguiClient.DEFAULT_THREAD_KEY))
        assertEquals("s1 的拷贝不受影响", "L", slots.load("s1"))
    }

    @Test
    fun `CONC-7 legacy claim flag persists across instance recreation`() {
        val mem = Mem().apply { map["thread_id"] = "L" }
        assertEquals("L", mem.slots().load("s1"))

        // 进程重启：新实例（内存旗已失）也不得二次 adopt
        assertNull("迁移旗须持久（跨实例/重启）", mem.slots().load("s2"))
    }

    @Test
    fun `CONC-7 default key maps to legacy slot for save and remove`() {
        val mem = Mem()
        val slots = mem.slots()
        slots.save(AguiClient.DEFAULT_THREAD_KEY, "D")
        assertEquals("save(DEFAULT) 直写 legacy 槽（板端存量兼容）", "D", mem.map["thread_id"])
        slots.save("s1", "S")
        assertEquals("S", mem.map["thread_id_s1"])
        slots.remove("s1")
        assertNull(mem.map["thread_id_s1"])
        assertEquals("remove(s1) 不动 legacy 槽", "D", mem.map["thread_id"])
        slots.remove(AguiClient.DEFAULT_THREAD_KEY)
        assertNull("remove(DEFAULT) 清 legacy 槽", mem.map["thread_id"])
    }

    @Test
    fun `CONC-7 blank legacy is not adopted and promotion is one-shot`() {
        val mem = Mem().apply { map["thread_id"] = "  " }
        assertNull("空白 legacy 不 adopt", mem.slots().load("s1"))

        val mem2 = Mem()
        assertNull("无 legacy 时新会话直接 null（调用方 createThreadId）", mem2.slots().load("s1"))
    }

    /** promotion 并发 check-then-act：并发首载不同新键，legacy 只被 copy 一次、永不删除。 */
    @Test
    fun `CONC-7 concurrent promotions are synchronized and keep legacy`() {
        val mem = Mem().apply { map["thread_id"] = "L" }
        val slots = mem.slots()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = (1..4).map { i ->
                pool.submit(java.util.concurrent.Callable {
                    start.await(5, TimeUnit.SECONDS)
                    slots.load("s$i")
                })
            }.map { it.get(10, TimeUnit.SECONDS) }
            assertTrue("至多一个会话 adopt legacy: $results", results.count { it == "L" } <= 1)
            assertTrue("其余会话必须 null（各自 createThreadId）: $results", results.all { it == "L" || it == null })
            assertEquals("legacy 槽保留（copy 语义，永不删除）", "L", mem.map["thread_id"])
        } finally {
            pool.shutdownNow()
        }
    }
}
