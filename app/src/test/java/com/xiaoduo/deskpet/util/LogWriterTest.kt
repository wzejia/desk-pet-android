package com.xiaoduo.deskpet.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * LogWriter（ocr[364]：PetLogger 全局锁逐行 IO → 缓冲+异步刷写）纯 JVM 测试。
 *
 * 变异锚（任务书 P3-T4 §3）：
 * - 删 flush（flush() 不再刷缓冲）→ `flushed lines land on disk...` 红；
 * - 丢缓冲边界（逐行直写不缓冲）→ `buffered lines stay off disk until flush` 红。
 */
class LogWriterTest {

    private lateinit var dir: File
    private var clockMs = 1782345600000L // 2026-06-25 UTC（LogRotatorTest 同源锚点）

    private fun writer(zone: ZoneId = ZoneId.of("UTC")): LogWriter {
        dir = File(System.getProperty("java.io.tmpdir"), "logwriter-test-${System.nanoTime()}")
        dir.mkdirs()
        return LogWriter(dir, now = { clockMs }, zone = zone)
    }

    @After
    fun cleanup() {
        dir?.takeIf { it.exists() }?.deleteRecursively()
    }

    private fun fileOf(day: String): File = File(dir, LogRotator.fileNameFor(day))
    private val day1 = "2026-06-25"
    private val day2 = "2026-06-26"

    @Test
    fun `flushed lines land on disk in enqueue order`() {
        val w = writer()
        w.enqueue("I", "Tag1", "first")
        w.enqueue("W", "Tag2", "second")
        w.enqueue("I", "Tag1", "third")
        w.flush()

        // 时钟部分用本地时区渲染（运行时行为如此），断言保持时区无关：
        // 日期取 UTC 日界（与文件名同源），顺序与「级别/tag: 消息」格式逐字钉死
        val lines = fileOf(day1).readText().lines().filter { it.isNotBlank() }
        assertEquals(3, lines.size)
        lines.forEach { assertTrue("行格式：$it", it.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \w/[\w.]+: .+"""))) }
        assertEquals("2026-06-25", lines[0].take(10))
        assertEquals(
            listOf("I/Tag1: first", "W/Tag2: second", "I/Tag1: third"),
            lines.map { it.split(" ", limit = 3)[2] },
        )
    }

    @Test
    fun `buffered lines stay off disk until flush`() {
        val w = writer()
        w.enqueue("I", "Tag", "buffered-a")
        w.enqueue("I", "Tag", "buffered-b")
        w.awaitIdle()

        // 写线程已消费（文件已打开）但缓冲未刷：磁盘上不得提前出现内容
        assertTrue("首行写入时日志文件应已创建", fileOf(day1).exists())
        assertEquals("缓冲未刷前不得落盘", 0L, fileOf(day1).length())

        w.flush()
        assertTrue("flush 后必须落盘", fileOf(day1).length() > 0L)
    }

    @Test
    fun `day roll splits files and preserves per-file order`() {
        val w = writer()
        w.enqueue("I", "Tag", "d1-a")
        w.enqueue("I", "Tag", "d1-b")
        clockMs += 24L * 60 * 60 * 1000 // 跨天
        w.enqueue("I", "Tag", "d2-a")
        w.flush()

        assertEquals(
            "d1-a\nd1-b\n",
            fileOf(day1).readText().lines().filter { it.isNotBlank() }
                .map { it.substringAfter(": ") }.joinToString("\n", postfix = "\n"),
        )
        assertEquals(
            "d2-a\n",
            fileOf(day2).readText().lines().filter { it.isNotBlank() }
                .map { it.substringAfter(": ") }.joinToString("\n", postfix = "\n"),
        )
    }

    @Test
    fun `error level lines flush without explicit flush`() {
        val w = writer()
        w.enqueue("E", "Tag", "boom")
        w.awaitIdle()

        assertTrue("E 级行须即刷（崩溃现场诊断）", fileOf(day1).length() > 0L)
        assertTrue(fileOf(day1).readText().endsWith("E/Tag: boom\n"))
    }

    @Test
    fun `flush and await are safe before any write`() {
        val w = writer()
        w.flush()
        w.awaitIdle()
        assertTrue(dir.listFiles().isNullOrEmpty())
    }
}
