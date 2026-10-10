package com.xiaoduo.deskpet.util

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 异步缓冲日志落盘核心（ocr[364]：PetLogger 全局锁逐行 open/write/close → 缓冲+异步刷写）。
 * 纯 JVM（零 Android import），PetLogger 委托此件、对外 API 不变。
 *
 * 契约：
 * - 单写线程 FIFO：调用方入队即返回，磁盘 IO 不占调用线程、不抢全局锁；
 * - 行格式与时间戳定格在入队时刻，[enqueue] 内串行化捕获+入队——文件内顺序=调用顺序，不乱；
 * - [BufferedWriter] 缓冲（缓冲满自动落 OS），不再逐行 open/write/close；
 * - flush 时机 = [flush] 显式调用（进程退出钩子）/ ERROR 级行落盘后 / 跨天切文件 close 前；
 * - 跨天自动切文件（[LogRotator] 命名），旧文件 close（含 flush）。
 *
 * 权衡（有意为之）：进程被强杀（无退出钩子）最多丢缓冲内未满 8KB 的日志尾部——历史实现
 * 逐行 open/close 无此窗口，属本修复既定代价（任务书口径「进程退出尽力 flush」）。
 */
class LogWriter(
    dir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val logDir: File = dir.apply { mkdirs() }
    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pet-logger").apply { isDaemon = true }
    }

    // 以下两字段仅写线程访问（enqueue 串行化只保证提交顺序，不触碰它们）
    private var writer: BufferedWriter? = null
    private var activeDay: String? = null

    private val enqueueLock = Any() // 时间戳定格与入队原子化，保证文件内时间戳单调

    init {
        // 进程退出尽力 flush（kill -9 无钩子，尽力而已）
        Runtime.getRuntime().addShutdownHook(Thread { flush() })
    }

    /** 入队一行；时间戳此刻定格，FIFO 保证文件内顺序=调用顺序。 */
    fun enqueue(level: String, tag: String, msg: String) {
        synchronized(enqueueLock) {
            val at = now()
            executor.execute { writeLine(at, level, tag, msg) }
        }
    }

    /** 刷缓冲并阻塞等待写线程完成；进程退出钩子与测试共用。 */
    fun flush() {
        runCatching { executor.submit { writer?.flush() }.get() }
    }

    /** 等待队列清空（不 flush；缓冲内容仍留内存）。 */
    fun awaitIdle() {
        runCatching { executor.submit {}.get() }
    }

    private fun writeLine(atMs: Long, level: String, tag: String, msg: String) {
        try {
            val day = LogRotator.dayOf(atMs, zone)
            if (day != activeDay) rollTo(day) // 跨天自动切文件
            writer?.write("${ts.format(Date(atMs))} $level/$tag: $msg\n")
            if (level == "E") writer?.flush() // 错误行即刷：崩溃现场诊断价值最高
        } catch (_: IOException) {
            // 落盘失败静默降级（logcat 镜像已由 PetLogger 兜住），绝不因日志崩 App
        }
    }

    /** 仅写线程调用；close 前 flush 保证已写内容不丢。 */
    private fun rollTo(day: String) {
        runCatching {
            writer?.flush()
            writer?.close()
        }
        writer = BufferedWriter(
            OutputStreamWriter(
                FileOutputStream(File(logDir, LogRotator.fileNameFor(day)), true),
                StandardCharsets.UTF_8,
            ),
        )
        activeDay = day
    }
}
