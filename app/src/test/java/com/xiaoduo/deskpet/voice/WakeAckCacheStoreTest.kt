package com.xiaoduo.deskpet.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * WakeAckCacheStore 单测（S9-2）：roundtrip / 缺文件 null / 无 tmp 残留 /
 * 坏缓存删+null / exists / 旧版本目录清理 / 非法字节拒写。
 */
class WakeAckCacheStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 合法 PCM：12000B ∈[9600,144000]，峰 1999 ≥1000。 */
    private fun validPcm(): ByteArray = WakeAckAudio.toBytes(ShortArray(6000) { (it % 2000).toShort() })

    private fun newStore(): WakeAckCacheStore {
        val root = File(tmp.root, "wake_ack/v2")
        return WakeAckCacheStore(root)
    }

    @Test
    fun `write then read roundtrip`() {
        val store = newStore()
        val name = WakeAckSelector.fileName(WakeAckSelector.cacheKey(
            WakeAckSelector.Snapshot("cloud", "aliyun", "m", "v"),
        ))
        assertTrue(store.write(name, validPcm()))
        assertTrue(store.exists(name))
        val back = store.read(name)
        assertNotNull(back)
        assertArrayEquals(validPcm(), back!!)
    }

    @Test
    fun `read missing file returns null`() {
        val store = newStore()
        assertNull(store.read("ack-none-0123456789.pcm"))
        assertFalse(store.exists("ack-none-0123456789.pcm"))
    }

    @Test
    fun `write overwrites atomically and leaves no tmp residue`() {
        // S9-2 回归守卫：tmp+rename 原子写（删 move 改直写 → 并发读者可见半文件；残 tmp 断言 → 红）
        val store = newStore()
        val name = "ack-x-abcdef0123.pcm"
        val first = validPcm()
        val second = WakeAckAudio.toBytes(ShortArray(7000) { (it % 1500).toShort() })
        assertTrue(store.write(name, first))
        assertTrue(store.write(name, second)) // 覆盖（Windows rename 不覆盖会失败，须原子替换）
        assertArrayEquals(second, store.read(name))
        val root = File(tmp.root, "wake_ack/v2")
        val tmpLeftovers = root.listFiles { f -> f.name.endsWith(".tmp") } ?: emptyArray()
        assertTrue("tmp 残留: ${tmpLeftovers.map { it.name }}", tmpLeftovers.isEmpty())
    }

    @Test
    fun `corrupt cache is deleted and read returns null`() {
        // S9-2 回归守卫：坏缓存删除并返 null（删 f.delete() → 文件残留 → exists 断言 → 红）
        val store = newStore()
        val name = "ack-bad-deadbeef.pcm"
        val root = File(tmp.root, "wake_ack/v2")
        root.mkdirs()
        File(root, name).writeBytes(ByteArray(12000)) // 长度合法但全零（峰 0 → validate 拒）
        assertNull(store.read(name))
        assertFalse("坏缓存应被删除", store.exists(name))
    }

    @Test
    fun `exists reflects file presence`() {
        val store = newStore()
        val name = "ack-e-abcdef0123.pcm"
        assertFalse(store.exists(name))
        assertTrue(store.write(name, validPcm()))
        assertTrue(store.exists(name))
    }

    @Test
    fun `legacy sibling version dirs are cleaned on first use`() {
        // S9-2 回归守卫：构造即清兄弟旧版本目录（删 cleanLegacySiblingDirs → v1 残留断言 → 红）
        val wakeAck = tmp.newFolder("wake_ack2")
        val v1 = File(wakeAck, "v1").apply {
            mkdirs()
            File(this, "stale.pcm").writeBytes(ByteArray(64))
        }
        val v3 = File(wakeAck, "v3").apply { mkdirs() } // 另一旧版本
        val store = WakeAckCacheStore(File(wakeAck, "v2"))
        assertTrue(store.write("ack-l-abcdef0123.pcm", validPcm()))
        assertFalse("旧版本目录 v1 应被清理", v1.exists())
        assertFalse("旧版本目录 v3 应被清理", v3.exists())
        assertTrue(store.exists("ack-l-abcdef0123.pcm")) // 当前版本不受影响
    }

    @Test
    fun `non-version sibling dirs are not touched`() {
        // S9-2 回归守卫：清理白名单只认 v\d+（误传 filesDir 时不得清空非版本兄弟目录；
        // 删白名单条件回退到「删一切兄弟」→ 本用例 → 红）
        val wakeAck = tmp.newFolder("wake_ack3")
        val logs = File(wakeAck, "logs").apply {
            mkdirs()
            File(this, "x.log").writeBytes(ByteArray(8))
        }
        val cache = File(wakeAck, "cache").apply { mkdirs() } // 非版本兄弟
        val v0 = File(wakeAck, "v0").apply { mkdirs() }        // 旧版本（应删）
        WakeAckCacheStore(File(wakeAck, "v2"))
        assertTrue("非版本目录 logs 被误删", logs.exists() && File(logs, "x.log").exists())
        assertTrue("非版本目录 cache 被误删", cache.exists())
        assertFalse(v0.exists())
    }

    @Test
    fun `write rejects invalid pcm without touching disk`() {
        val store = newStore()
        val name = "ack-i-abcdef0123.pcm"
        assertFalse(store.write(name, ByteArray(100)))      // 过短
        assertFalse(store.write(name, ByteArray(200000)))   // 过长
        assertFalse(store.write(name, ByteArray(12000)))    // 全零低峰
        assertFalse(store.exists(name))
    }
}
