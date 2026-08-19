package com.xiaoduo.deskpet.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃留痕（知识包 #44：release 无 console，崩溃必须文件化）。
 * UncaughtExceptionHandler → 写 files/logs/crash-yyyy-MM-dd.log（复用 PetLogger 目录、
 * 按天命名同 LogRotator 风格）→ 链式交还原默认 handler（不吞系统崩溃上报）。
 */
object CrashReporter {

    private const val PREFIX = "crash-"
    private const val SUFFIX = ".log"

    private var logDir: File? = null
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var installed = false
    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        logDir = File(context.filesDir, "logs").apply { mkdirs() }
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            writeCrash(thread, error)
            defaultHandler?.uncaughtException(thread, error)
        }
        PetLogger.i("CrashReporter", "installed")
    }

    fun crashFileFor(day: String): File? = logDir?.let { File(it, "$PREFIX$day$SUFFIX") }

    private fun writeCrash(thread: Thread, error: Throwable) {
        try {
            val now = System.currentTimeMillis()
            val file = crashFileFor(LogRotator.dayOf(now)) ?: return
            file.appendText(
                "${ts.format(Date(now))} FATAL/${thread.name}: ${error.javaClass.name}: ${error.message}\n" +
                    Log.getStackTraceString(error) + "\n"
            )
        } catch (_: Throwable) {
            // 崩溃处理中绝不再抛
        }
    }
}
