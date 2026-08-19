package com.xiaoduo.deskpet.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class LogRotatorTest {

    @Test
    fun `fileNameFor produces canonical name`() {
        assertEquals("xiaoduo-2026-07-30.log", LogRotator.fileNameFor("2026-07-30"))
    }

    @Test
    fun `dayOf formats epoch millis in given zone`() {
        val ms = 1782345600000L
        assertEquals(
            java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.of("UTC")).toLocalDate().toString(),
            LogRotator.dayOf(ms, ZoneId.of("UTC"))
        )
    }

    @Test
    fun `isLogFile matches only canonical names`() {
        assertTrue(LogRotator.isLogFile("xiaoduo-2026-07-30.log"))
        assertFalse(LogRotator.isLogFile("xiaoduo.log"))          // 旧单文件格式
        assertFalse(LogRotator.isLogFile("xiaoduo-2026-7-3.log")) // 非零填充
        assertFalse(LogRotator.isLogFile("other-2026-07-30.log"))
    }

    @Test
    fun `expiredFiles deletes only days before keep window`() {
        val names = listOf(
            "xiaoduo-2026-07-23.log", // 窗口外（today-7）→ 删
            "xiaoduo-2026-07-24.log", // 窗口边界（today-6）→ 留
            "xiaoduo-2026-07-30.log", // 当天 → 留
        )
        assertEquals(
            listOf("xiaoduo-2026-07-23.log"),
            LogRotator.expiredFiles(names, "2026-07-30")
        )
    }

    @Test
    fun `expiredFiles honors custom keepDays`() {
        val names = listOf(
            "xiaoduo-2026-07-29.log",
            "xiaoduo-2026-07-30.log",
        )
        // keepDays=1：只保留 today 当天，昨天即过期
        assertEquals(
            listOf("xiaoduo-2026-07-29.log"),
            LogRotator.expiredFiles(names, "2026-07-30", keepDays = 1)
        )
        // keepDays=2：昨天在窗口内
        assertEquals(
            emptyList<String>(),
            LogRotator.expiredFiles(names, "2026-07-30", keepDays = 2)
        )
    }

    @Test
    fun `expiredFiles leaves non-log and unparseable names alone`() {
        val names = listOf(
            "xiaoduo.log",               // 旧单文件格式
            "other-2020-01-01.log",      // 非本应用日志
            "xiaoduo-2026-13-99.log",    // 形如日志但日期解析失败
            "notes.txt",
        )
        assertEquals(
            emptyList<String>(),
            LogRotator.expiredFiles(names, "2026-07-30")
        )
    }
}
