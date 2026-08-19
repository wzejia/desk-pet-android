package com.xiaoduo.deskpet.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件日志（知识包教训 #44：release 无 console，日志必须文件化）。
 * 写 App 私有目录 files/logs/xiaoduo-yyyy-MM-dd.log，格式：时间戳 级别/tag: 消息。
 * 滚动纪律（偏差 D 裁决）：按天分文件，启动时清理 7 天前旧文件，跨天写入自动切文件。
 */
object PetLogger {

    private var logDir: File? = null
    private var logFile: File? = null
    private var activeDay: String? = null
    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun init(context: Context) {
        if (logFile != null) return
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        logDir = dir
        // 启动时清理保留期外的旧日志
        val today = LogRotator.dayOf(System.currentTimeMillis())
        val names = dir.list()?.toList() ?: emptyList()
        LogRotator.expiredFiles(names, today).forEach { name ->
            runCatching { File(dir, name).delete() }
                .onFailure { Log.w("Logger", "delete old log failed: $name") }
        }
        rollTo(today)
        i("Logger", "init, file=${logFile?.absolutePath}")
    }

    fun logFilePath(): String = logFile?.absolutePath ?: "(not init)"

    /** 当前保留的全部日志文件（导出用），新的在前。 */
    @Synchronized
    fun logFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return (dir.listFiles()?.toList() ?: emptyList())
            .filter { LogRotator.isLogFile(it.name) }
            .sortedByDescending { it.name }
    }

    fun d(tag: String, msg: String) = write("D", tag, msg)
    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun w(tag: String, msg: String) = write("W", tag, msg)
    fun e(tag: String, msg: String, tr: Throwable? = null) =
        write("E", tag, msg + (tr?.let { "\n${Log.getStackTraceString(it)}" } ?: ""))

    @Synchronized
    private fun rollTo(day: String) {
        val dir = logDir ?: return
        activeDay = day
        logFile = File(dir, LogRotator.fileNameFor(day))
    }

    @Synchronized
    private fun write(level: String, tag: String, msg: String) {
        val now = System.currentTimeMillis()
        Log.println(levelToPriority(level), tag, msg)
        try {
            val today = LogRotator.dayOf(now)
            if (today != activeDay) rollTo(today) // 跨天自动切文件
            val line = "${ts.format(Date(now))} $level/$tag: $msg\n"
            logFile?.appendText(line)
        } catch (_: Throwable) {
            // 日志失败静默降级到 logcat，绝不因日志崩 App
        }
    }

    private fun levelToPriority(level: String) = when (level) {
        "D" -> Log.DEBUG
        "I" -> Log.INFO
        "W" -> Log.WARN
        else -> Log.ERROR
    }
}
