package com.xiaoduo.deskpet.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 日志滚动纯逻辑（可 JVM 单测）：按天分文件 + 保留最近 KEEP_DAYS 天。
 * 文件名约定：xiaoduo-yyyy-MM-dd.log
 * （偏差 D 裁决：追认手写 PetLogger，补滚动/保留期，不引 Timber）
 */
object LogRotator {

    const val PREFIX = "xiaoduo-"
    const val SUFFIX = ".log"
    const val KEEP_DAYS = 7

    private val dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val nameRegex = Regex("""xiaoduo-(\d{4}-\d{2}-\d{2})\.log""")

    fun fileNameFor(day: String): String = "$PREFIX$day$SUFFIX"

    fun dayOf(timeMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(timeMs).atZone(zone).format(dayFormatter)

    fun isLogFile(name: String): Boolean = nameRegex.matches(name)

    /**
     * 返回应删除的文件名：匹配日志命名且日期早于保留窗口（含 today 在内共 keepDays 天）。
     * 非日志文件、日期解析失败的文件一律不动。
     */
    fun expiredFiles(names: List<String>, today: String, keepDays: Int = KEEP_DAYS): List<String> {
        val cutoff = LocalDate.parse(today).minusDays((keepDays - 1).toLong())
        return names.filter { name ->
            val date = nameRegex.matchEntire(name)
                ?.let { runCatching { LocalDate.parse(it.groupValues[1]) }.getOrNull() }
                ?: return@filter false
            date.isBefore(cutoff)
        }
    }
}
