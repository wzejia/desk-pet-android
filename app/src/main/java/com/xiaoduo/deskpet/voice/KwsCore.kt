package com.xiaoduo.deskpet.voice

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * KWS 解码核心（纯 JVM，零 Android import，Fake 注入可测，见 KwsCoreTest）。
 *
 * 职责：能量门控 → 入队 → 消费线程 decode → 命中回调 + 检出率统计日志 + release 时序。
 * sherpa-onnx native 边界抽象为 [Native]，真适配层在 KwsEngine（Android 绑定层）。
 *
 * 线程模型（知识包 §5「音频回调只推队列、decode 在消费线程，防 underrun」）：
 * [feed] 在采麦回调线程只做 门控+转 float+入队（满丢最旧，与 AudioCapture 同策保最新）；
 * decode 全在内部消费线程。命中经 [onKeyword] 在消费线程回调（实现方自行切线程）。
 *
 * 检出率度量（观察项「KWS 检出率无日志」收口）：
 * 四个计数器 fed（过门入队）/ gated（门控拦）/ decoded（消费）/ hits（命中），
 * 每 [statsIntervalMs] 落一行 `kws stats:`，release 落汇总行；命中行带流水号 `#N`。
 * 分母（fed/gated）与分子（hits）同口径累计，检出率可由日志原始数据回算。
 */
class KwsCore(
    private val native: Native,
    private val gateProvider: () -> Float,
    private val onKeyword: (String) -> Unit,
    private val logger: (String) -> Unit,
    private val sampleRate: Int,
    queueCapacity: Int,
    private val now: () -> Long = System::currentTimeMillis,
    private val statsIntervalMs: Long = STATS_INTERVAL_MS,
) {

    /** sherpa-onnx 原生边界（Android 绑定层，JVM 不可载；逻辑全部下沉本类）。 */
    interface Native {
        fun acceptWaveform(samples: FloatArray, sampleRate: Int)
        fun isReady(): Boolean
        fun decode()
        fun resultKeyword(): String
        fun reset()
        fun release()
    }

    private val queue = ArrayBlockingQueue<FloatArray>(queueCapacity)

    @Volatile
    private var running = true

    private val released = AtomicBoolean(false)

    private val fed = AtomicLong(0)
    private val gated = AtomicLong(0)
    private val decoded = AtomicLong(0)
    private val hits = AtomicLong(0)

    private val startedAt = now()

    @Volatile
    private var lastStatsAt = startedAt

    private val decodeThread = Thread({ decodeLoop() }, "kws-decode").apply {
        isDaemon = true
        start()
    }

    /**
     * 麦块入口（采麦回调线程，100ms/3200B）：能量门控 → Int16→float32 → 入队。
     * 队列满丢最旧（KWS 语义保最新音频，陈块无唤醒价值）。
     */
    fun feed(block: ByteArray) {
        if (!running) return
        val samples = EnergyGate.int16ToFloat(block)
        val rms = EnergyGate.rmsOfSamples(samples)
        if (rms < gateProvider()) {
            gated.incrementAndGet() // 整块不喂不判，但计数（检出率分母之一）
            return
        }
        fed.incrementAndGet()
        if (!queue.offer(samples)) {
            queue.poll()
            queue.offer(samples)
        }
    }

    private fun decodeLoop() {
        while (running) {
            val samples = try {
                queue.take()
            } catch (ie: InterruptedException) {
                break // release 的 interrupt 落点：空转等待时立即退出
            }
            try {
                native.acceptWaveform(samples, sampleRate)
                while (native.isReady()) {
                    native.decode()
                    val keyword = native.resultKeyword()
                    if (keyword.isNotBlank()) {
                        // 回归守卫：release 进行中命中只记日志不派发（删此 → 已毁服务
                        // 幻影唤醒 startListening → KwsCoreTest 红）
                        if (!running) {
                            logger("keyword hit dropped (release in progress): $keyword")
                            runCatching { native.reset() }
                            break
                        }
                        val n = hits.incrementAndGet()
                        logger("keyword hit: $keyword (#$n, fed=${fed.get()}, gated=${gated.get()})")
                        onKeyword(keyword)
                        native.reset() // 命中即 reset（对账表）
                    }
                }
                decoded.incrementAndGet()
            } catch (e: Exception) {
                // 单块解码异常不杀线程：透传真实 msg，丢块续跑
                logger("decode error, block dropped: ${e.message}")
                runCatching { native.reset() }
            }
            maybeLogStats()
        }
    }

    private fun maybeLogStats() {
        val t = now()
        if (t - lastStatsAt < statsIntervalMs) return
        lastStatsAt = t
        logger(statsLine("kws stats:"))
    }

    private fun statsLine(prefix: String): String {
        val uptimeSec = (now() - startedAt) / 1000
        return "$prefix fed=${fed.get()} gated=${gated.get()} decoded=${decoded.get()} " +
            "hits=${hits.get()} uptime=${uptimeSec}s"
    }

    /**
     * 释放 native 资源（幂等；服务 onDestroy 调）。
     *
     * 时序（审计 P1 修复）：旧实现 interrupt 后立即 free native——若 decode 线程恰在
     * acceptWaveform/decode 的 native 调用内即 use-after-free → SIGSEGV 无 Java 堆栈。
     * 现先 join 确认消费线程退出再 free；join 超时（native 卡死）宁可泄漏句柄也不硬 free
     * （崩溃规避 > 内存泄漏，进程退出时 OS 回收）。
     */
    fun release() {
        // 回归守卫：CAS 单次放行（删此 → 并发/重复 release 双 free → KwsCoreTest 红）
        if (!released.compareAndSet(false, true)) return
        running = false
        decodeThread.interrupt()
        // 回归守卫：join（删此 → free 抢在 in-flight decode 前 → KwsCoreTest 红）
        decodeThread.join(JOIN_TIMEOUT_MS)
        if (decodeThread.isAlive) {
            logger("release: decode thread stuck after ${JOIN_TIMEOUT_MS}ms, skip native free (leak over crash)")
            return
        }
        runCatching { native.release() }
            .onFailure { logger("native free failed: ${it.message}") } // 纪律 5：不静默吞
        logger(statsLine("released,"))
    }

    companion object {
        /** release 等 decode 线程退出的上限：单次 decode 毫秒级，2s 已极宽。 */
        internal const val JOIN_TIMEOUT_MS = 2_000L

        /** 检出率统计日志间隔（有音频活动时按块检查）。 */
        internal const val STATS_INTERVAL_MS = 60_000L
    }
}
