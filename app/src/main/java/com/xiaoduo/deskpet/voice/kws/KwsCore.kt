package com.xiaoduo.deskpet.voice.kws

import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * KWS 解码核心（纯 JVM，零 Android import，Fake 注入可测，见 KwsCoreTest /
 * KwsCoreHangoverTest）。
 *
 * 职责：能量门控（挂起窗 + 间隙 reset）→ 入队 → 消费线程 decode → 命中回调 +
 * 检出率统计日志 + release 时序。
 * sherpa-onnx native 边界抽象为 [Native]，真适配层在 KwsEngine（Android 绑定层）。
 *
 * 线程模型（知识包 §5「音频回调只推队列、decode 在消费线程，防 underrun」）：
 * [feed] 在采麦回调线程只做 门控+转 float+入队（满丢最旧，与 AudioCapture 同策保最新）；
 * decode 与一切 native.reset 全在内部消费线程（sentinel 入队保序，feed 线程绝不直触
 * native——守卫四问①）。命中经 [onKeyword] 在消费线程回调（实现方自行切线程）。
 *
 * 能量门控两步语义（2026-08-19 真机探针石锤「第一声必漏、第二声秒中」修复；
 * 桌面 sidecar.py 只有第一步「整块不喂不判」，本机制是超越桌面 parity 的增强）：
 * ① 挂起窗：过门块之后 [hangoverBlocks] 块内低于门仍喂真实块——字间气口的弱音对
 *    transducer 打分有意义，挖掉会压缩时间轴把打分压到阈值下；
 * ② gap-reset：连续低于门超窗 → 不喂（省电初衷保住）且对 KWS 流 reset 恰好一次
 *    （幂等沿），冲刷波束里冻结尾分，防冻结部分分被下一声音事件补完造成假命中。
 *
 * 检出率度量（观察项「KWS 检出率无日志」收口）：
 * 四个计数器 fed（入队：过门或挂起窗内）/ gated（超窗丢弃）/ decoded（消费）/ hits（命中），
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
    private val now: () -> Long = MONOTONIC_NOW,
    private val statsIntervalMs: Long = STATS_INTERVAL_MS,
    private val hangoverBlocks: Int = KwsDefaults.GATE_HANGOVER_BLOCKS,
    /** 解码线程 Java 优先级：主引擎默认 [DEFAULT_DECODE_THREAD_PRIORITY]，影子传 MIN。 */
    private val decodeThreadPriority: Int = DEFAULT_DECODE_THREAD_PRIORITY,
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

    /** 消费队列元素：真实音频块，或间隙 reset sentinel（在队列里的位置即时间轴空缺处）。 */
    private sealed interface Work {
        class Samples(val data: FloatArray) : Work
        object GapReset : Work
    }

    private val queue = ArrayBlockingQueue<Work>(queueCapacity)

    @Volatile
    private var running = true

    /**
     * ocr[16]：命中派发段与 release 的 running=false 写互斥锁——running 复查+递增计数+
     * 派发+命中 reset 收进同一临界区，release 翻 running 必须持同一把锁，旧 check-then-act
     * 小窗（复查过后 release 整段返回、命中才派发）即被关闭。
     */
    private val dispatchLock = Any()

    private val released = AtomicBoolean(false)

    private val fed = AtomicLong(0)
    private val gated = AtomicLong(0)
    private val decoded = AtomicLong(0)
    private val hits = AtomicLong(0)

    // 挂起窗状态：仅 feed 线程（采麦回调，单线程）读写，无需原子。
    private var hangoverLeft = 0
    private var gapResetArmed = false // 喂过音频才武装：引擎新生的干净流无需 startup reset

    private val startedAt = now()

    @Volatile
    private var lastStatsAt = startedAt

    private val decodeThread = Thread({ decodeLoop() }, "kws-decode").apply {
        // S35（2026-09-11 取证）：旧值 MIN_PRIORITY（S25b 白屏修复）在 rk3568 对话期被
        // 饿到解码 7.5 块/s < 喂 10 块/s，队列（64 封顶丢最旧）恒满 → 音频时间轴撕裂
        // （实测 diff +940/+283 块）→ 唤醒词喂进去是碎片必漏（IDLE 连喊 2 次不中的现行）。
        // 主引擎提档 6（高于普通 5、低于 UI nice -4）：争抢时保解码吞吐，UI 仍高它 2-3 档
        // （S25b 白屏回归锚：装机后 Choreographer 复测）；影子观测通道保持 MIN 不争抢。
        priority = decodeThreadPriority
        // S25b：队列驱动+100ms 节奏，晚几毫秒无害；主+影子双引擎同降。
        isDaemon = true
        start()
    }

    /** 测试缝：解码线程优先级回归断言用（S35 变异锚）。 */
    internal val decodeThreadForTest: Thread get() = decodeThread

    /**
     * 麦块入口（采麦回调线程，100ms/3200B）：能量门控（挂起窗+gap-reset）→ Int16→float32 → 入队。
     * 队列满丢最旧（KWS 语义保最新音频，陈块无唤醒价值）。
     */
    fun feed(block: ByteArray) {
        if (!running) return
        val samples = EnergyGate.int16ToFloat(block)
        val rms = EnergyGate.rmsOfSamples(samples)
        if (rms < gateProvider()) { // gate 每次现读：校准写入即时生效（语义不变）
            if (hangoverLeft > 0) {
                // 回归守卫（挂起窗）：过门后 N 块内低于门仍喂真实块——字间气口弱音对
                // transducer 打分有意义（删此 → 时间轴压缩压分 → KwsCoreHangoverTest 红）
                hangoverLeft-- // 窗内低能块只消耗窗口不续窗（门限附近噪声不会无限续窗）
            } else {
                gated.incrementAndGet() // 超窗整块不喂不判，但计数（检出率分母之一）
                // 回归守卫（gap-reset 幂等沿）：连续静默超窗只对 KWS 流 reset 一次，冲刷
                // 冻结尾分（删此 → 部分分冻结到下一声音事件假命中 → KwsCoreHangoverTest 红）
                if (gapResetArmed) {
                    gapResetArmed = false
                    offer(Work.GapReset)
                }
                return
            }
        } else {
            hangoverLeft = hangoverBlocks
            gapResetArmed = true
        }
        fed.incrementAndGet()
        // S11-6 延迟取证探针：armed 时入队块打一行（tag 随注入 logger，生产=KwsEngine），
        // 与 AudioCapture 侧 `cap rms=…` 行时间戳同域对照即得中段延迟；未 arm 零开销。
        if (AudioProbe.armed) logger(feedRmsLine(rms))
        offer(Work.Samples(samples))
    }

    /**
     * 入队：满丢最旧（KWS 语义保最新音频，陈块无唤醒价值）。
     * ocr[13] 守卫：GapReset sentinel 是 gap-reset 唯一边载体，被 drop-oldest 静默挤出 →
     * 冻结部分分泄漏到下一声音事件成假命中，且 sentinel 丢失不可恢复（音频块可丢，
     * sentinel 不可再造）——挤到 sentinel 时回插它、改丢一块陈旧 Samples。
     * feed 单写者（采麦回调线程）+ decode 单读者，poll-then-offer 两步无中间竞态。
     * 变异锚：回退纯 drop-oldest（不回插）→ KwsCoreRobustnessTest 红。
     */
    private fun offer(work: Work) {
        while (!queue.offer(work)) {
            val oldest = queue.poll() ?: continue // 队列被并发抽干（理论不可达）：回重试
            if (oldest !is Work.GapReset) continue // 挤掉陈旧音频块：已腾一格，回重试
            // 挤到 sentinel：回插，改从队头再取一件处置
            val second = queue.poll()
            if (second is Work.GapReset) {
                queue.offer(oldest) // 双 sentinel（理论不可达）：弃新保旧沿，已腾一格
                continue
            }
            queue.offer(oldest)
            if (second == null) return // 队列仅剩 sentinel（capacity=1 病态防御）：弃新块保 sentinel
            // second = 陈旧 Samples 已弃：已腾一格，回重试 offer(work)
        }
    }

    private fun decodeLoop() {
        while (running) {
            val work = try {
                queue.take()
            } catch (ie: InterruptedException) {
                break // release 的 interrupt 落点：空转等待时立即退出
            }
            when (work) {
                is Work.GapReset -> {
                    // 间隙冲刷：sentinel 落点即时间轴空缺处（积压音频之后、新音频之前）；
                    // reset 只在消费线程执行——与 accept/decode/hit-reset 同线程，零跨线程 native
                    runCatching { native.reset() }
                        .onFailure { logger("gap reset failed: ${it.message}") } // 纪律 5：不静默吞
                    logger("kws gap reset: silence streak flushed")
                }
                is Work.Samples -> decodeBlock(work.data)
            }
            maybeLogStats()
        }
    }

    private fun decodeBlock(samples: FloatArray) {
        try {
            native.acceptWaveform(samples, sampleRate)
            var hitDroppedInRelease = false
            while (native.isReady()) {
                native.decode()
                val keyword = native.resultKeyword()
                if (keyword.isNotBlank()) {
                    // ocr[16]：running 复查+计数+派发+命中 reset 收进 dispatchLock，与
                    // release 的 running=false 写互斥——旧 check-then-act 小窗（复查过后
                    // release 整段返回、命中才派发）即被关闭。派发回调毫秒级，release 等
                    // 锁远小于 JOIN 预算。变异锚：删 dispatchLock → KwsCoreRobustnessTest 红。
                    synchronized(dispatchLock) {
                        if (!running) {
                            // 回归守卫：release 进行中命中只记日志不派发（删此 → 已毁服务
                            // 幻影唤醒 startListening → KwsCoreTest 红）
                            logger("keyword hit dropped (release in progress): $keyword")
                            runCatching { native.reset() }
                            hitDroppedInRelease = true
                        } else {
                            val n = hits.incrementAndGet()
                            logger("keyword hit: $keyword (#$n, fed=${fed.get()}, gated=${gated.get()})")
                            // ocr[14]：回调派发不是解码失败——单独隔离，坏监听器抛异常
                            // 不得误报 decode error / 触发多余 native.reset / 漏记 decoded。
                            // 变异锚：拆隔离 → KwsCoreRobustnessTest 红。
                            runCatching { onKeyword(keyword) }
                                .onFailure { logger("keyword callback failed: ${it.message}") }
                            native.reset() // 命中即 reset（对账表）
                        }
                    }
                    if (hitDroppedInRelease) break
                }
            }
            decoded.incrementAndGet()
        } catch (e: Exception) {
            // 单块解码异常不杀线程：透传真实 msg，丢块续跑
            logger("decode error, block dropped: ${e.message}")
            runCatching { native.reset() }
        } catch (t: Throwable) {
            // ocr[15]：native 桥的 fatal Error（UnsatisfiedLinkError/OOM…）旧实现只 catch
            // Exception → 线程静默死亡：running 挂真 → feed 空转 churn、KWS 全聋、release
            // 无法分辨卡死与死亡。先落停机位（feed 即刻停收）再记日志并重抛：死亡可观测。
            // 变异锚：删此分支 → KwsCoreRobustnessTest 红。
            running = false
            logger("decode fatal, thread exiting: ${t.message}")
            throw t
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
        // ocr[16]：running=false 写与派发段同锁——在飞派发进行中 release 在此等锁，
        // 绝不越过派发提前返回（幻影唤醒窗收口）。
        synchronized(dispatchLock) { running = false }
        decodeThread.interrupt()
        // 回归守卫：join（删此 → free 抢在 in-flight decode 前 → KwsCoreTest 红）
        joinDecodeThread()
        if (decodeThread.isAlive) {
            logger("release: decode thread stuck after ${JOIN_TIMEOUT_MS}ms, skip native free (leak over crash)")
            return
        }
        runCatching { native.release() }
            .onFailure { logger("native free failed: ${it.message}") } // 纪律 5：不静默吞
        logger(statsLine("released,"))
    }

    /**
     * ocr[19]：release 调用方自身被中断（如 onDestroy 期主线程带中断进入）时，裸
     * join(JOIN_TIMEOUT_MS) 会把 InterruptedException 冲出 release()——released CAS 已
     * 消费一次性放行，native.release() 永久跳过（泄漏）且调用方吃意外异常。分段 join：
     * 中断只记账（清标志续等满预算），收尾还原中断标志；预算口径与旧单次 join 一致。
     * 变异锚：回退裸 join → KwsCoreRobustnessTest 红。
     */
    private fun joinDecodeThread() {
        var interrupted = false
        val deadlineNs = System.nanoTime() + JOIN_TIMEOUT_MS * 1_000_000
        while (decodeThread.isAlive) {
            val remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) break
            try {
                decodeThread.join(minOf(JOIN_POLL_MS, remainingMs))
            } catch (ie: InterruptedException) {
                interrupted = true
                Thread.interrupted() // 清标志：下轮 join 才能真等；收尾统一还原
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    companion object {
        /** release 等 decode 线程退出的上限：单次 decode 毫秒级，2s 已极宽。 */
        internal const val JOIN_TIMEOUT_MS = 2_000L

        /** ocr[19] 分段 join 的单段等待步长。 */
        private const val JOIN_POLL_MS = 50L

        /**
         * ocr[18]：统计间隔/uptime 基准钟默认单调域（nanoTime/1e6 毫秒）——旧默认墙钟
         * currentTimeMillis 被 NTP 步进时：回拨冻统计行、前跳瞬发 tick、uptime= 实为
         * 墙钟时长而非开机时长。只取差值，域切换不影响间隔语义；测试注入缝保留。
         * 变异锚：回退 System::currentTimeMillis → KwsCoreRobustnessTest 红。
         */
        internal val MONOTONIC_NOW: () -> Long = { System.nanoTime() / 1_000_000 }

        /** 检出率统计日志间隔（有音频活动时按块检查）。 */
        internal const val STATS_INTERVAL_MS = 60_000L

        /**
         * S35 主引擎解码线程提档值（Java 6）：MIN(1)=S25b 旧档实测被饿撕裂；
         * NORM(5)=S25b 前旧态白屏风险；6 落在两者之间（ART nice ≈ -1，UI 主线程 -4），
         * 取值依据：取证 diff 曲线（空闲 diff≈0/对话期暴涨）+ 装机双验收（diff 恢复 +
         * Choreographer 不回归）。
         */
        internal const val DEFAULT_DECODE_THREAD_PRIORITY = 6
    }
}

/**
 * ocr[17]：`feed rms=` 探针行（S11-6 延迟取证，与 AudioCapture 侧 `cap rms=` 行时间戳
 * 同域对照）格式钉死 Locale.US——默认 locale 在逗号小数区（de/fr/ru/中文数字设置…）
 * 渲染 "0,0421"，破坏日志 grep/解析口径。变异锚：回退 ".format"（默认 locale）→
 * KwsCoreRobustnessTest 红。
 */
internal fun feedRmsLine(rms: Float): String = String.format(Locale.US, "feed rms=%.4f", rms)
