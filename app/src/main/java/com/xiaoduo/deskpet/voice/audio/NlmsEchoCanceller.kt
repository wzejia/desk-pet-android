package com.xiaoduo.deskpet.voice.audio

import com.xiaoduo.deskpet.voice.kws.EnergyGate
import kotlin.math.abs
import kotlin.math.min

/**
 * NLMS 参考回声消除（S23：P4 挂账提前，治板子播放期「小朵小朵」打断回声掩蔽）。
 *
 * 原理：TTS PCM 是我们自己合成的 = **完美参考信号**。播放侧把已播 PCM 经配速器
 * （VoicePlayer pacer，按播放头位置）推入参考环形缓冲，采麦侧逐样本做归一化最小
 * 均方自适应滤波：用参考信号的加权和逼近麦里的回声分量并减掉。
 *
 * **配对架构迭代**（2026-09-02 板子两轮遥测教训）：
 * - v1 FIFO 消费 + 欠账回退/溢出快进两处重同步：板子 refBacklog 1484~8671 震荡
 *   （采集掉块+推送突发累积漂移），重同步跳变反复打断收敛 → 全程 out≈in 零抑制；
 * - v2 锁 live 边：被推送突发劫持（burst 推进即配对平移），且对齐步进符号错——废弃；
 * - v3 **FIFO 消费为体 + 互相关配对平移校正为用**：FIFO 本身就是声学正确配对
 *   （麦样本与参考流同步推进），漂移（采集掉块/推送突发）由周期互相关测出真实
 *   源滞后 L，把配对平移 (filterLen/2 - L)（±640 限步防人声峰误跳）——不依赖
 *   任何一侧节奏精度。
 *
 * **发散守卫**（板子 20:23 遥测实证 double-talk 发散 outRms=4×inRms）：
 * 漏泄因子/块防权重无界增长 + 输出能量超输入 1.5× 连续 3 块滤波器清零重来
 * ——宁可不消，绝不放大。
 *
 * 线程模型：pushReference（配速线程）与 process（采麦收集协程）跨线程；
 * 单写单读 + @Volatile 写位置，音频语义容忍偶发一样本级错位（NLMS 会适应）。
 *
 * 纯 Kotlin 零 Android import，JVM 全测（NlmsEchoCancellerTest：合成回声 ERLE≥10dB /
 * 目标保真 / 零参考直通 / 字节路径 / 抖动节奏 / 发散恢复 / 溢出容忍）。
 */
class NlmsEchoCanceller(
    private val filterLen: Int = DEFAULT_FILTER_LEN,
    private val mu: Float = DEFAULT_MU,
    private val now: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = {},
) {

    /** 参考环形缓冲：16384 样本=1s（覆盖滤波窗 + 推送突发/欠账时间推进/校正余量——
     *  8192 时正常推送超前（±2 块）即触发溢出快进跳变打断收敛，2026-09-02 抖动单测实证）。 */
    private val refCap = 16384
    private val refBuf = FloatArray(refCap)

    /** 已推参考总样本数（单写线程：配速器；读侧 volatile 可见即可）。 */
    @Volatile
    private var refWrite = 0L

    /** 消费位置（单线程：process 调用方；FIFO 推进 + 周期互相关平移校正）。 */
    private var refRead = 0L

    /** 最近一次参考推入时刻（活动判定：停播后快速退出消回声路径）。 */
    @Volatile
    private var lastRefAt = 0L

    /** 自适应滤波器系数（回声路径估计）。 */
    private val w = FloatArray(filterLen)

    /** 当前滤波窗参考能量（增量维护：加新减旧，免每样本全窗求和）。 */
    private var winPow = 0f

    /** 遥测：实际走过消回声路径的块数。 */
    @Volatile
    var processedBlocks = 0L
        private set

    private val micHist = FloatArray(ALIGN_MIC_HIST)
    private var micHistCount = 0L

    /** 发散守卫连击数。 */
    private var divergenceStrikes = 0

    /** 播放侧入口：推入已播 PCM（24kHz 先 3:2 重采样到 16k）。 */
    fun pushReferencePcm(pcm: ByteArray, sampleRate: Int) {
        val floats = EnergyGate.int16ToFloat(pcm)
        if (sampleRate == 16_000) {
            pushReference(floats)
        } else {
            // 24k→16k：每 3 取 2（相邻对平均，抗混叠近似；参考用途足够）
            val out = FloatArray(floats.size * 2 / 3)
            var oi = 0
            var i = 0
            while (i + 2 < floats.size && oi + 1 < out.size) {
                out[oi++] = (floats[i] + floats[i + 1]) * 0.5f
                out[oi++] = floats[i + 2]
                i += 3
            }
            pushReference(out.copyOf(oi))
        }
    }

    /** 参考推入（16k float 域；测试与重采样后的生产共用）。 */
    fun pushReference(samples: FloatArray) {
        for (s in samples) {
            refBuf[(refWrite % refCap).toInt()] = s
            refWrite++
        }
        lastRefAt = now()
    }

    /** 是否有可用参考（播放活动中）：routeBlock 快路径判定。 */
    fun hasActiveReference(): Boolean =
        now() - lastRefAt < REF_FRESH_MS && refWrite - refRead >= 0

    /** 字节入口（VSM routeBlock）：无参考原样返回同一数组（调用方可判直通）。 */
    fun processBlock(block: ByteArray): ByteArray {
        if (!hasActiveReference()) return block
        val mic = EnergyGate.int16ToFloat(block)
        val out = process(mic)
        val bytes = ByteArray(out.size * 2)
        for (i in out.indices) {
            val v = (out[i].coerceIn(-1f, 1f) * 32767f).toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * 逐样本 NLMS：y=w·x（参考窗加权和=回声估计），e=d-y（残余=输出），
     * w += μ·e·x/(x·x+ε)。**FIFO 消费**=声学正确配对（麦样本与参考流同步推进），
     * 漂移由块尾周期互相关平移校正。参考欠账/不新鲜 → 原样直通（不消费不错位）。
     */
    fun process(mic: FloatArray): FloatArray {
        if (now() - lastRefAt > REF_FRESH_MS) return mic.copyOf()
        // 欠账（配速瞬时滞后）：时间轴推进但**钳在 live 边**（越过 refRead>refWrite 会
        // 让配对读到未推送的「未来」样本、负积压 runaway——2026-09-02 抖动单测实证
        // -10062 崩溃）；推送停顿时配对停桨在 live 边，恢复后的时间不连续由互相关校正
        if (refWrite - refRead < mic.size) {
            refRead = minOf(refRead + mic.size, refWrite)
            return mic.copyOf()
        }
        // 溢出保底（罕见：采集掉块致积压逼近环容）：快进到滤波窗+块，防环覆盖
        if (refWrite - refRead > refCap - filterLen - 2 * mic.size) {
            // live 边重置 + 宽域重对齐：跳变后真滞后可达 ±8000（局部搜索域外，
            // 常规对齐只能在噪声里乱抓——2026-09-02 抖动单测实证 b=60 后永不收敛）
            refRead = refWrite - filterLen - mic.size
            recomputeWinPow()
            forceAlignNext = true
            forceAlignWide = true
            logger("echo cancel overflow fast-forward: backlog=${refWrite - refRead}")
        }
        val out = FloatArray(mic.size)
        processedBlocks++
        var inE = 0.0
        var outE = 0.0
        for (i in mic.indices) {
            val d = mic[i]
            inE += d.toDouble() * d
            micHist[(micHistCount % ALIGN_MIC_HIST).toInt()] = d
            micHistCount++
            val endIdx = (refRead % refCap).toInt() // x[0]=最新参考样本的环内下标
            val taps = min(filterLen.toLong(), refRead + 1).toInt()
            // 前向：回声估计 y = Σ w[j]·x[j]（x[j]=ref[refRead-j]）
            var y = 0f
            var idx = endIdx
            for (j in 0 until taps) {
                y += w[j] * refBuf[idx]
                if (--idx < 0) idx += refCap
            }
            val e = d - y
            out[i] = e
            // 更新：w += μ·e·x/(winPow+ε)
            val norm = mu * e / (winPow + EPS)
            idx = endIdx
            for (j in 0 until taps) {
                w[j] += norm * refBuf[idx]
                if (--idx < 0) idx += refCap
            }
            // 窗能量增量维护：加新进样本，减滑出样本
            val xNew = refBuf[endIdx]
            winPow += xNew * xNew
            if (taps == filterLen) {
                val oldIdx = (((refRead - filterLen + 1) % refCap + refCap).toInt()) % refCap
                val xOld = refBuf[oldIdx]
                winPow -= xOld * xOld
            }
            if (winPow < 0f) winPow = 0f // 浮点漂移守卫
            refRead++
            outE += e.toDouble() * e
        }
        // 块尾三件套：漏泄 + 互相关配对平移校正 + 发散守卫
        for (j in w.indices) w[j] *= LEAK_PER_BLOCK
        maybeAlign()
        divergenceCheck(inE, outE)
        return out
    }

    /**
     * 周期互相关配对校正：C(Δ)=Σ micHist[i]·ref[p_i-Δ]，p_i=FIFO 配对绝对位。
     * 峰值 Δ*≈当前回声源滞后 L；把配对平移 (filterLen/2 - L) 使源居窗心（±640 限步）。
     * 粗扫步 8 + ±8 细扫；峰值须 ≥4×均值才动作（防人声峰误对齐）。
     */
    private var lastAlignL = 1024 // 上一校正测得的源滞后（搜索范围锚点）

    /** 溢出快进/异常跳变后置位：下一块立即重对齐。 */
    private var forceAlignNext = false

    /** 跳变后首对齐用宽搜索域（局部域外才是跳变场景）。 */
    private var forceAlignWide = false

    private fun maybeAlign() {
        if (!forceAlignNext && processedBlocks % ALIGN_EVERY_BLOCKS != 0L) return
        val wide = forceAlignWide
        forceAlignNext = false
        forceAlignWide = false
        if (micHistCount < ALIGN_MIC_HIST) return
        runCatching {
            // 步长 1 局部全扫：直达声相关峰窄至 1-2 样本，粗网格会整峰跨过
            // （2026-09-02 探针实证：C(900)=94.8 而网格点 896/904≈0）
            val lo = if (wide) ALIGN_WIDE_MIN else maxOf(ALIGN_DELTA_MIN, lastAlignL - ALIGN_LOCAL_RANGE)
            val hi = if (wide) ALIGN_DELTA_MAX else minOf(ALIGN_DELTA_MAX, lastAlignL + ALIGN_LOCAL_RANGE)
            var bestDelta = lo
            var bestScore = 0f
            var scoreSum = 0f
            var scored = 0
            var delta = lo
            while (delta <= hi) {
                val s = xcorr(delta)
                scoreSum += s
                scored++
                if (s > bestScore) {
                    bestScore = s
                    bestDelta = delta
                }
                delta++
            }
            if (scored == 0 || bestScore <= 0f) return
            if (bestScore < 4f * maxOf(scoreSum / scored, 1e-9f)) return
            lastAlignL = bestDelta
            // 死区：源滞后在窗心带 [窗/4, 3窗/4] 内不动（免校正震荡）；出带才向窗心拉
            if (bestDelta in filterLen / 4..filterLen * 3 / 4) return
            val shift = (filterLen / 2 - bestDelta).coerceIn(-ALIGN_MAX_SHIFT, ALIGN_MAX_SHIFT)
            if (abs(shift) >= ALIGN_MIN_SHIFT) {
                refRead += shift
                recomputeWinPow()
                logger("echo cancel align: L=$bestDelta shift=$shift")
            }
        }
    }

    private fun xcorr(delta: Int): Float {
        // micHist[i]（旧->新）配对绝对位 p_i = refRead - ALIGN_MIC_HIST + i（块尾口径）
        var s = 0f
        for (i in 0 until ALIGN_MIC_HIST) {
            val absPos = refRead - ALIGN_MIC_HIST + i - delta
            if (absPos < 0 || absPos < refWrite - refCap) continue // 环外/未生样本不参与
            s += micHist[i] * refBuf[(absPos % refCap).toInt()]
        }
        return s
    }

    /** 发散守卫：输出能量超输入 1.5× 连续 3 块 -> 滤波器清零（宁可不消，绝不放大）。 */
    private fun divergenceCheck(inE: Double, outE: Double) {
        if (inE < 1e-4) return
        if (outE > inE * DIVERGENCE_RATIO) {
            if (++divergenceStrikes >= DIVERGENCE_STRIKES) {
                w.fill(0f)
                divergenceStrikes = 0
                logger("echo cancel divergence reset: out=%.2f in=%.2f".format(outE, inE))
            }
        } else {
            divergenceStrikes = 0
        }
    }

    /** refRead 跳转后按新窗全量重算窗能量（增量维护只在连续滑动时有效）。 */
    private fun recomputeWinPow() {
        winPow = 0f
        var idx = (refRead % refCap).toInt()
        val taps = min(filterLen.toLong(), refRead + 1).toInt()
        for (j in 0 until taps) {
            val x = refBuf[idx]
            winPow += x * x
            if (--idx < 0) idx += refCap
        }
    }

    /** 遥测快照：参考积压（样本）+ 滤波窗能量 + 累计处理块（诊断 NLMS 是否真在跑）。 */
    fun statsSnapshot(): String =
        "refBacklog=${refWrite - refRead} winPow=%.1f blocks=$processedBlocks".format(winPow)

    companion object {
        /** 滤波窗 2048 taps=128ms@16k：覆盖板子音频管道延迟经验上限（30~120ms）。 */
        const val DEFAULT_FILTER_LEN = 2048

        /** NLMS 归一化步长：0.1 = 收敛速度（~1-2s）与失调噪声（~5%）的平衡点。 */
        const val DEFAULT_MU = 0.1f

        /** 归一化防除零（参考窗能量量级 ~O(100)，ε 取 1 不影响归一化语义）。 */
        private const val EPS = 1f

        /** 参考新鲜度：停播超过 400ms 视为无回声场景，直通。 */
        internal const val REF_FRESH_MS = 400L

        /** 互相关校正周期（块）：10 块=1s（漂移跟踪性与开销平衡）。 */
        private const val ALIGN_EVERY_BLOCKS = 10L

        /** 互相关麦历史长度（样本）：50ms——直达峰信噪比足够，成本减半。 */
        private const val ALIGN_MIC_HIST = 800

        /** 局部搜索半径（样本）：上一校正值 ±1024（真实声学路径秒级缓变）。 */
        private const val ALIGN_LOCAL_RANGE = 1024

        /** 源滞后搜索范围（样本）：-1024~+4096（管道延迟余量）。 */
        private const val ALIGN_DELTA_MIN = -1024
        private const val ALIGN_DELTA_MAX = 4096

        /** 跳变恢复宽搜索域下限（样本）：覆盖溢出重置后可达偏移。 */
        private const val ALIGN_WIDE_MIN = -8000

        /** 单次配对平移限幅（样本）：1536=96ms（死区外远失配快拉，区内不动）；低于 64 不动。 */
        private const val ALIGN_MAX_SHIFT = 1536
        private const val ALIGN_MIN_SHIFT = 64

        /** 漏泄因子/块：0.999 ≈ 每秒 0.6%，防双讲期权重无界增长。 */
        private const val LEAK_PER_BLOCK = 0.999f

        /** 发散判定：out 能量 > 1.5×in。 */
        private const val DIVERGENCE_RATIO = 1.5

        /** 发散清零连击阈值。 */
        private const val DIVERGENCE_STRIKES = 3
    }
}
