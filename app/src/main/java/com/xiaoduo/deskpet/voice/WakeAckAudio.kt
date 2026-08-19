package com.xiaoduo.deskpet.voice

import kotlin.math.abs
import kotlin.math.max

/**
 * 唤醒应答音频后处理（S9-2，纯逻辑，零 Android import）。
 *
 * Python parity：`.scratch/wozai/remake_wozai.py`（S9-1 迁 tools/wozai/common.py）逐参数移植：
 * - windowMax：win=240 样本/窗（10ms@24k），末窗残缺按残缺样本计，空数组 → 空包络
 * - trimEdges：阈 th = max(peak*0.02, 60.0)（peak 为包络最大值）；头呼吸 3 窗下钳 0、
 *   尾呼吸 4 窗上钳 len；比较严格大于（v > th）；无命中窗时 first=0 / last=len(env)-1
 *   ——全静音输入 trim 原样通过（由 validate 拦），与 Python 口径一致
 * - normalizePeak：峰值归一 26000；**舍入必须 Math.rint（半偶，对齐 Python round；
 *   roundToInt 是 half-up 会漂移）**；钳 [-32768, 32767]（|x·g| ≤ target 数学上恒成立，
 *   此钳为 Python 原样保留的防御）
 * - validate：字节长偶且 ∈[9600, 144000]（0.2–3.0s@24k/16bit/mono）且峰值 ≥1000
 */
object WakeAckAudio {

    const val WIN = 240
    const val PEAK_TARGET = 26000
    const val MIN_BYTES = 9600
    const val MAX_BYTES = 144000
    const val MIN_PEAK = 1000

    /** PCM 小端 16bit 字节 → 样本（字节长为奇时丢弃尾部半个样本）。 */
    fun toSamples(bytes: ByteArray): ShortArray {
        val out = ShortArray(bytes.size / 2)
        for (i in out.indices) {
            out[i] = (((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8))).toShort()
        }
        return out
    }

    /** 样本 → PCM 小端 16bit 字节。 */
    fun toBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** 包络：每 win 样本窗内绝对值最大（对齐 Python window_max）。 */
    internal fun windowMax(samples: ShortArray, win: Int = WIN): IntArray {
        val env = IntArray((samples.size + win - 1) / win)
        for (w in env.indices) {
            val end = minOf(w * win + win, samples.size)
            var m = 0
            for (i in w * win until end) {
                val a = abs(samples[i].toInt())
                if (a > m) m = a
            }
            env[w] = m
        }
        return env
    }

    /** 去头尾静音 + 头 3 窗/尾 4 窗呼吸（对齐 Python trim_edges）。 */
    fun trimEdges(samples: ShortArray, win: Int = WIN): ShortArray {
        val env = windowMax(samples, win)
        var peak = 0
        for (v in env) if (v > peak) peak = v
        val th = max(peak * 0.02, 60.0)
        var first = 0
        for (w in env.indices) {
            if (env[w].toDouble() > th) {
                first = w
                break
            }
        }
        var last = env.size - 1
        for (w in env.size - 1 downTo 0) {
            if (env[w].toDouble() > th) {
                last = w
                break
            }
        }
        // 头呼吸 3 窗下钳 0、尾呼吸 4 窗上钳 len（对齐 Python s/e 两行）
        val s = max(0, (first - 3) * win)
        val e = minOf(samples.size, (last + 4) * win)
        return samples.copyOfRange(s, e)
    }

    /** 峰值归一（对齐 Python normalize；半偶舍入变异锚见测试）。 */
    fun normalizePeak(samples: ShortArray, target: Int = PEAK_TARGET): ShortArray {
        var peak = 0
        for (x in samples) {
            val a = abs(x.toInt())
            if (a > peak) peak = a
        }
        if (peak == 0) return samples
        val g = target.toDouble() / peak
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            // S9-2 回归守卫：Math.rint 半偶对齐 Python round（改 half-up 的 roundToInt/Math.round
            // → 半偶锚用例（2.5→2 / 12.5→12）→ 红）
            val r = Math.rint(samples[i].toDouble() * g).toInt()
            out[i] = r.coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** 端到端后处理 = normalizePeak(trimEdges(pcm))（对齐 Python normalize(trim_edges(raw))）。 */
    fun process(samples: ShortArray): ShortArray = normalizePeak(trimEdges(samples))

    /** 入缓存门槛：字节长偶且 ∈[MIN_BYTES, MAX_BYTES] 且解出的样本峰值 ≥ MIN_PEAK。 */
    fun validate(bytes: ByteArray): Boolean {
        // S9-2 回归守卫：三重门槛缺一不可（删任一 → validate 过拒矩阵对应用例 → 红）
        if (bytes.size % 2 != 0) return false
        if (bytes.size < MIN_BYTES || bytes.size > MAX_BYTES) return false
        var peak = 0
        for (s in toSamples(bytes)) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
        }
        return peak >= MIN_PEAK
    }
}
