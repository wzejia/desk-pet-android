package com.xiaoduo.deskpet.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 文件日志（知识包教训 #44：release 无 console，日志必须文件化）。
 * 写 App 私有目录 files/logs/xiaoduo-yyyy-MM-dd.log，格式：时间戳 级别/tag: 消息。
 * 滚动纪律（偏差 D 裁决）：按天分文件，启动时清理 7 天前旧文件，跨天写入自动切文件。
 * P3-T4（ocr[364]）：落盘改 [LogWriter] 缓冲+异步单写线程——调用方不再持全局锁逐行
 * open/write/close；对外 API 不变，顺序不乱，flush 时机见 LogWriter。
 */
object PetLogger {

    private var logDir: File? = null
    private var writer: LogWriter? = null

    @Synchronized
    fun init(context: Context) {
        if (writer != null) return
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        logDir = dir
        // 启动时清理保留期外的旧日志（长驻进程跨天再清理=ocr[365]，不在本票）
        val today = LogRotator.dayOf(System.currentTimeMillis())
        val names = dir.list()?.toList() ?: emptyList()
        LogRotator.expiredFiles(names, today).forEach { name ->
            runCatching { File(dir, name).delete() }
                .onFailure { Log.w("Logger", "delete old log failed: $name") }
        }
        writer = LogWriter(dir)
        i("Logger", "init, file=${logFilePath()}")
    }

    /** 当天日志文件路径（导出用）。 */
    fun logFilePath(): String = logDir?.let { dir ->
        File(dir, LogRotator.fileNameFor(LogRotator.dayOf(System.currentTimeMillis()))).absolutePath
    } ?: "(not init)"

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

    private fun write(level: String, tag: String, msg: String) {
        // logcat 镜像保持同步、位置与历史一致（异常面不变）；落盘交异步写线程
        Log.println(levelToPriority(level), tag, msg)
        writer?.enqueue(level, tag, msg)
    }

    private fun levelToPriority(level: String) = when (level) {
        "D" -> Log.DEBUG
        "I" -> Log.INFO
        "W" -> Log.WARN
        else -> Log.ERROR
    }
}
