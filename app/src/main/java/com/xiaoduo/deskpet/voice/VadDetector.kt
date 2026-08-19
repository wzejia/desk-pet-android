package com.xiaoduo.deskpet.voice

import kotlin.math.sqrt

/**
 * 绝对阈值 VAD + PCM 累积（纯逻辑、零 Android 依赖，100ms PCM 块逐块喂入）。
 *
 * 参数逐项照抄 docs/voice-param-reconciliation-2026-07-28.md VAD 节（单一事实源），
 * 行为对齐桌面端 voice_listen.py AsrListener（HEAD 实测）。与桌面端唯一差异：
 * 30s 超时 / 8s 最长句用块数（100ms/块）替代 time.monotonic()，使 JVM 单测可确定性验证。
 */
class VadDetector {

    companion object {
        const val SPEECH_LEVEL = 0.05          // 归一化音量阈值 = RMS/8000
        const val RMS_REF = 8000.0
        const val SPEECH_START_TICKS = 3       // 连续超阈值判说话开始（300ms）
        const val SILENCE_END_TICKS = 8        // 连续低于阈值判说话结束（800ms）
        const val MIN_UTTERANCE_TICKS = 3      // 更短当噪音丢弃
        const val LISTEN_TIMEOUT_S = 30        // 生效值 30s（sidecar 显式传参；勿用类默认值 8）
        const val MAX_UTTERANCE_S = 8          // 说话超时强制结束（防增益爬升噪声永不结束）
        const val PRE_ROLL_TICKS = 3           // 判起成立时补回触发前低电平句首段
        const val BLOCK_MS = 100
        const val SAMPLE_RATE = 16000
        const val BLOCK_SAMPLES = SAMPLE_RATE * BLOCK_MS / 1000        // 1600
        const val BLOCK_BYTES = BLOCK_SAMPLES * 2                      // 3200
        const val MIN_UTTERANCE_BYTES = MIN_UTTERANCE_TICKS * BLOCK_BYTES // 9600

        /** deque 容量 = 判起 3 + pre_roll 3 = 6（与判起同长则触发前保留实为 0，双栈踩过两次） */
        const val PRE_ROLL_CAPACITY = SPEECH_START_TICKS + PRE_ROLL_TICKS // 6
        const val LISTEN_TIMEOUT_TICKS = LISTEN_TIMEOUT_S * 1000 / BLOCK_MS // 300
        const val MAX_UTTERANCE_TICKS = MAX_UTTERANCE_S * 1000 / BLOCK_MS   // 80
    }

    /** 触发事件。maxLevel = 期间最大归一化 level（「一值定层」诊断日志用）。 */
    sealed interface Event {
        val maxLevel: Double

        /** 一句干净语音：pre-roll 句首 + 说话块，不含尾部静音（防 ASR 幻觉）。 */
        data class Utterance(val pcm: ShortArray, override val maxLevel: Double) : Event

        /** LISTENING 30s 未说话超时。 */
        data class Timeout(override val maxLevel: Double) : Event
    }

    var isActive = false
        private set
    var isSpeaking = false
        private set

    /** 本次监听窗口内观测到的最大归一化电平（「一值定层」诊断：手动释放未检出语音时打日志）。 */
    fun currentMaxLevel(): Double = maxLevel

    private val buffer = ArrayList<ShortArray>()
    private val preRoll = ArrayDeque<ShortArray>()
    private var aboveTicks = 0
    private var belowTicks = 0
    private var maxLevel = 0.0
    private var listenTicks = 0 // 未判起期间已消费块数（30s = 300 块）
    private var speechTicks = 0 // 判起后已消费块数（8s = 80 块强制结束）

    /** 开始监听（重置全部状态 + 启动超时计数）。单次触发停止后由调用方重新 start。 */
    fun start() {
        buffer.clear()
        preRoll.clear()
        aboveTicks = 0
        belowTicks = 0
        maxLevel = 0.0
        listenTicks = 0
        speechTicks = 0
        isSpeaking = false
        isActive = true
    }

    fun stop() {
        isActive = false
    }

    /** 喂一块 Int16 PCM 小端字节流（100ms = 3200 字节）。返回触发事件或 null。 */
    fun feed(pcmBytes: ByteArray): Event? {
        require(pcmBytes.size % 2 == 0) { "Int16 PCM 字节数必须为偶数" }
        val samples = ShortArray(pcmBytes.size / 2) { i ->
            ((pcmBytes[2 * i].toInt() and 0xFF) or (pcmBytes[2 * i + 1].toInt() shl 8)).toShort()
        }
        return feed(samples)
    }

    /** 喂一块 Int16 PCM（100ms = 1600 样本）。返回触发事件或 null。 */
    fun feed(block: ShortArray): Event? {
        if (!isActive || block.isEmpty()) return null
        val level = levelOf(block)
        if (level > maxLevel) maxLevel = level
        val isSpeech = level >= SPEECH_LEVEL

        if (!isSpeaking) {
            // 未说话超 listen_timeout 回 IDLE——先查超时再消费本块（同桌面端顺序）
            if (listenTicks >= LISTEN_TIMEOUT_TICKS) {
                isActive = false
                return Event.Timeout(maxLevel)
            }
            listenTicks++
            // 未判起时不累积 buffer，只滚动 pre_roll deque
            preRoll.addLast(block.copyOf())
            while (preRoll.size > PRE_ROLL_CAPACITY) preRoll.removeFirst()
            if (isSpeech) {
                aboveTicks++
                if (aboveTicks >= SPEECH_START_TICKS) {
                    isSpeaking = true
                    belowTicks = 0
                    speechTicks = 0
                    // pre-roll 补回句首：触发前 ~3 块低电平起始段 + 3 触发块（deque 全量 6 块）
                    buffer.addAll(preRoll)
                }
            } else {
                aboveTicks = 0
            }
            return null
        }

        // 说话中：超 max_utterance 强制结束先查（本块不消费，同桌面端）
        if (speechTicks >= MAX_UTTERANCE_TICKS) return finishUtterance()
        speechTicks++
        return if (isSpeech) {
            belowTicks = 0
            buffer.add(block.copyOf()) // 只累积说话块（尾部静音不进 buffer）
            null
        } else {
            belowTicks++
            aboveTicks = 0
            if (belowTicks >= SILENCE_END_TICKS) finishUtterance() else null
        }
    }

    /** 说话结束：min_utterance 过滤后产出 Utterance。不达 min 丢弃但不停机（同桌面端）。 */
    private fun finishUtterance(): Event? {
        isSpeaking = false
        belowTicks = 0
        val totalSamples = buffer.sumOf { it.size }
        if (totalSamples * 2 >= MIN_UTTERANCE_BYTES) {
            isActive = false // 单次触发：utterance 后停，调用方重新 start()
            val pcm = ShortArray(totalSamples)
            var off = 0
            for (b in buffer) {
                b.copyInto(pcm, off)
                off += b.size
            }
            buffer.clear()
            return Event.Utterance(pcm, maxLevel)
        }
        buffer.clear()
        return null
    }

    /** Int16 PCM 归一化音量：min(1.0, RMS/8000)（削波信号钳位到 1.0，同 voice_listen.py:48）。 */
    private fun levelOf(block: ShortArray): Double {
        var sum = 0.0
        for (s in block) sum += s.toDouble() * s
        return minOf(1.0, sqrt(sum / block.size) / RMS_REF)
    }
}
