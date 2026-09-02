package com.xiaoduo.deskpet.voice

import kotlin.math.min

/**
 * NLMS 参考回声消除（S23：P4 挂账提前，治板子播放期「小朵小朵」打断回声掩蔽）。
 *
 * 原理：TTS PCM 是我们自己合成的 = **完美参考信号**。播放侧把已写 PCM 推入参考环形
 * 缓冲（[pushReference]），采麦侧逐样本做归一化最小均方自适应滤波（[process]）：
 * 用参考信号的加权和逼近麦里的回声分量，从麦信号中减掉。滤波器系数即回声路径
 * （扬声器→房间→麦克风）的在线估计，板子无 AEC 的空缺由本类在应用层补上。
 *
 * 延迟对齐：参考在写入 AudioTrack 时推入，**领先**于麦中回声（音频管道延迟+声学路径），
 * 因果滤波器天然吸收该滞后——只要滞后 < 滤波窗长即可，无需显式延迟标定。
 * 板子音频管道延迟经验值 30~120ms → 默认 2048 taps=128ms 为下限，可构造调大。
 *
 * 线程模型：pushReference（TTS 消费线程）与 process（采麦收集协程）跨线程；
 * 环形缓冲单写单读 + @Volatile 写位置，音频语义容忍偶发一样本级错位（NLMS 会适应）。
 *
 * 性能：每 100ms 块 ≈ 2×filterLen×1600 ≈ 6.6M 乘加（filterLen=2048），A55 实测预算内；
 * 仅播放期有参考时工作，无参考（IDLE/聆听）逐字节直通零开销。
 *
 * 纯 Kotlin 零 Android import，JVM 全测（NlmsEchoCancellerTest：合成回声 ERLE≥10dB /
 * 目标保真 / 零参考直通 / 字节路径）。
 */
class NlmsEchoCanceller(
    private val filterLen: Int = DEFAULT_FILTER_LEN,
    private val mu: Float = DEFAULT_MU,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** 参考环形缓冲：8192 样本=512ms 头寸（覆盖滤波窗 + 播放/采集速率抖动）。 */
    private val refCap = 8192
    private val refBuf = FloatArray(refCap)

    /** 已推参考总样本数（单写线程：TTS 消费者；读侧 volatile 可见即可）。 */
    @Volatile
    private var refWrite = 0L

    /** 参考消费位置（单线程：process 调用方）。 */
    private var refRead = 0L

    /** 最近一次参考推入时刻（活动判定：停播后快速退出消回声路径）。 */
    @Volatile
    private var lastRefAt = 0L

    /** 自适应滤波器系数（回声路径估计）。 */
    private val w = FloatArray(filterLen)

    /** 当前滤波窗参考能量（增量维护：加新减旧，免每样本全窗求和）。 */
    private var winPow = 0f

    /** 遥测：实际走过消回声路径的块数（routeBlock 侧按 out!==block 口径亦可，这里自证）。 */
    @Volatile
    var processedBlocks = 0L
        private set

    /** 播放侧入口：推入已写 AudioTrack 的 PCM（24kHz 先 3:2 重采样到 16k）。 */
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

    /** 字节入口（VSM routeBlock）：无参考原样返回同一数组（调用方 .contentEquals 可判直通）。 */
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
     * w += μ·e·x/(x·x+ε)（归一化步长更新）。参考不足/不新鲜 → 原样直通。
     */
    fun process(mic: FloatArray): FloatArray {
        if (now() - lastRefAt > REF_FRESH_MS || refWrite - refRead < mic.size) {
            // 参考断供（停播/欠账）：消费位置对齐到最新，防陈旧参考污染下一轮
            if (refWrite - refRead < mic.size) refRead = maxOf(refRead, refWrite - filterLen)
            return mic.copyOf()
        }
        val out = FloatArray(mic.size)
        processedBlocks++
        for (i in mic.indices) {
            val endIdx = (refRead % refCap).toInt() // x[0]=最新参考样本的环内下标
            val taps = min(filterLen.toLong(), refRead + 1).toInt()
            // 前向：回声估计 y = Σ w[j]·x[j]（x[j]=ref[refRead-j]）
            var y = 0f
            var idx = endIdx
            for (j in 0 until taps) {
                y += w[j] * refBuf[idx]
                if (--idx < 0) idx += refCap
            }
            val e = mic[i] - y
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
                val oldIdx = ((refRead - filterLen + 1) % refCap + refCap).toInt() % refCap
                val xOld = refBuf[oldIdx]
                winPow -= xOld * xOld
            }
            if (winPow < 0f) winPow = 0f // 浮点漂移守卫
            refRead++
        }
        return out
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
    }
}
