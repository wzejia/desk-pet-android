package com.xiaoduo.deskpet.voice

import kotlin.math.sqrt

/**
 * 绝对阈值 VAD + PCM 累积（纯逻辑、零 Android 依赖，100ms PCM 块逐块喂入）。
 *
 * 参数对齐 docs/voice-param-reconciliation-2026-07-28.md VAD 节（单一事实源），
 * 行为对齐桌面端 voice_listen.py AsrListener（HEAD 实测）--例外：silence_end_ticks
 * 2026-08-29 用户拍板 8->4（端点提速，有意偏离桌面端，对账表补注 3）；
 * S50（2026-09-18）回落 8/800ms 并新增独立判停线 SPEECH_END_LEVEL=0.03（见下）。
 * 与桌面端另一差异：
 * 30s 超时 / 8s 最长句用块数（100ms/块）替代 time.monotonic()，使 JVM 单测可确定性验证。
 */
/**
 * S50 判停/判起阈值拆分（轻声切尾实锤修复：同设备大声说话完整、轻声时「盘点当前有哪」
 * 四连切——句尾弱音节 RMS 跌破 0.05 被计静音，600ms 计满即切）：
 * - 判停线独立为 max([SPEECH_END_LEVEL], floor×[ADAPTIVE_END_FACTOR])，不再跟
 *   speechLevelProvider（校准公式 coerceIn(0.05,0.15) 在安静域钳在 0.05，数学上无法
 *   低于 0.05，救不了轻声场景）；0.03 让弱音节 0.03~0.05 域计回说话；
 * - 判起线保持 max(speechLevelProvider, floor×[ADAPTIVE_START_FACTOR])（环境校准联动不变）；
 * - 判停窗回落 6 块（600ms，S52 晚回调）：S52 停用上攻 + end 线 0.03 后原切尾根因已除，
 *   800ms 的额外延迟不再必要；
 * - 恒成立 end < start（0.03<0.05 且 1.4f<2.0f）。
 *
 * S19-1 自适应噪声底 v5（rk3568 无 AEC + 底层 AGC 慢释放致非平稳噪声下固定阈值
 * 数学不可收敛）。EMA 双轨（WebRTC 式 attack/release）：
 * - 低于阈值块：noiseFloor 慢跟踪（0.1）--环境噪声上/下都跟随；
 * - 持续 ≥[NOISE_ATTACK_SUSTAIN_TICKS] 块高电平 <b>且</b>（远低于语音峰值
 *   level < speechPeak×[NOISE_ATTACK_PEAK_HALF]，或电平平台稳定 (max-min)/mean
 *   < [PLATEAU_STABLE_RATIO]）-> 判定「抬升噪声」快速上攻（0.35）；
 *   连续语音=波动大且贴峰值，两通道天然区分；
 * - 判起阈值 = max(provider, floor×2.0)、判停阈值 = max(provider, floor×1.4)；
 * - start() 不清 EMA（设备声学属性跨轮保持--泵态多轮重启免幽灵轮）。
 * 手机安静域 floor≈0.001 -> 阈值=provider 零回归（单测锁定）。
 *
 * S56（2026-09-21）ASR 裁判式噪声底上行（生产板 0.19 稳态环境噪声实证修复）：
 * v5 的上行通道（噪声上攻）被 S52 停用后，floor 只能向下/低位跟随——环境噪声阶跃
 * 到阈值之上（实测 0.19 vs 判停线 0.03 的 7~10 倍）时 floor 永远学不到，判停失效
 * 每轮拖满 8s 强制截断。上攻通道复活会重蹈 S52 误伤（持续元音/衰减尾音同型），
 * 故改用<b>语义裁判</b>：空 ASR 轮 = ASR 认证「收集到的不是话」（[raiseNoiseFloorFromLastUtterance]，
 * VoiceSessionManager S19-2 空 ASR 分支调用），floor 以 0.6 速率向该轮 RMS 收敛——
 * 语音轮永不推高 floor（真语音轮有文本，不触发），误伤免疫。约 2 个空轮收敛后
 * end=max(0.03,floor×1.4) 越过噪底 → 判停复活（600ms）、判起=max(0.05,floor×2) 过滤
 * 幻觉轮，系统自洽。物理极限：底噪 0.19 内正常音量（峰值 0.1~0.3）SNR 不足仍需
 * 大声——根治靠消除噪声源（板旁风扇/空调排查）。
 */
class VadDetector(
    /**
     * S18：说话电平阈值动态 provider（默认 0.05，与桌面端对账一致）。
     * 环境音校准联动（rk3568 板子噪声底 ~0.049 骑在 0.05 上致判停永不触发、
     * 每轮拖满 8s 强制上限）：校准写入 Prefs -> 此处每块现读，即时生效。
     * S19-1 起作自适应阈值的<b>下限</b>（max(provider, p20×k)）。
     */
    private val speechLevelProvider: () -> Double = { SPEECH_LEVEL },
    private val speechEndLevelProvider: () -> Double = { SPEECH_END_LEVEL },
) {

    companion object {
        const val SPEECH_LEVEL = 0.05          // 归一化音量阈值 = RMS/8000（判起线，环境校准联动）
        const val SPEECH_END_LEVEL = 0.03      // S50 判停线独立常量：弱音节 0.03~0.05 域计回说话；
                                              // 刻意不走 speechLevelProvider（校准钳位见类注释）
        const val RMS_REF = 8000.0
        const val SPEECH_START_TICKS = 3       // 连续超阈值判说话开始（300ms）
        const val SILENCE_END_TICKS = 6        // 连续低于阈值判说话结束（600ms；2026-09-18 晚
                                              // 用户拍板回 600：S52 停用上攻 + end 线 0.03 后原切尾
                                              // 根因已除，800ms 的额外延迟不再必要。演进史：
                                              // 8→4(08-29 提速)→6(09-11 折中)→8(S50)→6(S52 晚)）
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

        /** S19-1 「持续高电平疑似抬升噪声」的持续块数（1s）。 */
        const val NOISE_ATTACK_SUSTAIN_TICKS = 10

        /** S58（2026-09-21 用户拍板）：上攻通道复活。回退实验定案：ee80b27（上攻停用态）在
         *  当前环境（底噪 0.19 稳态，来源未明）下判停恒失效拖 8s；9e82c03（上攻开启态）
         *  实测四轮全自然判停（1~3s 成句），长句「借阅2025年的合同档案」档案名完整——
         *  S52 误伤（长元音同型被切）今晚未复现，判停可用性优先。S52 的历史误伤知识保留
         *  于 git 历史与本注释：持续元音/衰减尾音与稳态噪声同型，上攻对二者同权——若长句
         *  切尾再现，重审判据（建议方向：空 ASR 裁判门控 + 上攻限速）。 */
        const val NOISE_ATTACK_ENABLED = true

        /** S19-1 抬升噪声上攻速率（快）。 */
        const val NOISE_ATTACK_RATE = 0.35

        /** S19-1 低于阈值块噪声底跟踪速率（慢，上下双向）。 */
        const val NOISE_TRACK_RATE = 0.10

        /** S19-1 上攻通道①：level 低于语音峰值 EMA 的比例（噪声 < 语音一半）。 */
        const val NOISE_ATTACK_PEAK_HALF = 0.5

        /** S19-1 上攻通道②：平台判据--最近 10 块 (max-min)/mean 低于此值=稳定非语音。 */
        const val PLATEAU_STABLE_RATIO = 0.25

        /** S19-1 噪声底上限（防病理态阈值吞语音）。 */
        const val NOISE_FLOOR_MAX = 0.5

        /** S19-1 语音峰值 EMA 逐块衰减（0.5%/块，防上轮响度压制下轮轻声）。 */
        const val SPEECH_PEAK_DECAY = 0.995

        /** S19-1 判起/判停对噪声底的倍率。 */
        const val ADAPTIVE_START_FACTOR = 2.0
        const val ADAPTIVE_END_FACTOR = 1.4

        /** S56 空 ASR 轮噪声底上行速率（floor += (rms-floor)×0.6，约 2 个空轮收敛）。 */
        const val EMPTY_TURN_FLOOR_RATE = 0.6
        const val LISTEN_TIMEOUT_TICKS = LISTEN_TIMEOUT_S * 1000 / BLOCK_MS // 300
        const val MAX_UTTERANCE_TICKS = MAX_UTTERANCE_S * 1000 / BLOCK_MS   // 80
    }

    /** S51：续听阶段判起灵敏度覆写（null=正常校准线 0.05）。先验是“用户刚说完大概率续说”，
     *  轻声续说（0.03~0.05 频段）必须能判起成片段，否则整段丢弃（板端 17:00 实锤：
     *  档案名零残留、dispatch 恰在 hold+1.0s 整=10 块全被当静音）。噪声误判起由
     *  min_utterance + S19-2 空 ASR 续听兜底；高噪声域 floor×2 照常抬高保护。 */
    @Volatile
    var startLevelOverride: (() -> Double)? = null

    /** S51 续听窗冻结口径：当前判停阈值（与 feed 内同一“静音”定义，全链单标）。 */
    fun currentEndThreshold(): Double = endThreshold()

    /** S56 当前噪声底（诊断日志用）。 */
    fun currentNoiseFloor(): Double = noiseFloor

    /** S56 ASR 裁判式噪声底上行：空 ASR 轮（ASR 认证「收集到的不是话」）调用一次，
     *  floor 向最近成句 RMS 收敛（速率 [EMPTY_TURN_FLOOR_RATE]，封顶 [NOISE_FLOOR_MAX]）。
     *  有文本轮绝不调用——真语音永不推高 floor，S52 式误伤免疫。无成句历史时 no-op。 */
    fun raiseNoiseFloorFromLastUtterance() {
        val rms = lastUtteranceRms ?: return
        noiseFloor = (noiseFloor + (rms - noiseFloor) * EMPTY_TURN_FLOOR_RATE)
            .coerceIn(0.0, NOISE_FLOOR_MAX)
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

    /** S19-1 噪声底 EMA（设备声学属性，跨轮保持；见类注释双轨更新规则）。 */
    private var noiseFloor = 0.0

    /** S56 最近一次成句的整段 RMS（ASR 裁判式 floor 上行的电平依据；跨轮保持）。 */
    private var lastUtteranceRms: Double? = null

    /** S19-1 语音峰值 EMA（说话块推升、逐块衰减；上攻通道①的基准）。 */
    private var speechPeak = 0.0

    /** S19-1 连续超阈值块数（达 [NOISE_ATTACK_SUSTAIN_TICKS] 触发疑似抬升噪声判定）。 */
    private var sustainedAbove = 0

    /** S19-1 平台判据用小窗（最近 10 块电平）。 */
    private val plateauWindow = ArrayDeque<Double>()

    /** 开始监听（重置全部状态 + 启动超时计数）。单次触发停止后由调用方重新 start。 */
    fun start() {
        buffer.clear()
        preRoll.clear()
        // S19-1：noiseFloor/speechPeak/sustainedAbove/plateauWindow 刻意不清--
        // 设备声学属性跨轮保持（泵态多轮重启若清零会无限幽灵轮）
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
        val isSpeech = level >= if (isSpeaking) endThreshold() else startThreshold()
        // S19-1 双轨噪声底更新（判完再更新，本块不受影响）
        speechPeak = maxOf(speechPeak * SPEECH_PEAK_DECAY, level)
        plateauWindow.addLast(level)
        while (plateauWindow.size > NOISE_ATTACK_SUSTAIN_TICKS) plateauWindow.removeFirst()
        if (isSpeech) {
            sustainedAbove++
            if (NOISE_ATTACK_ENABLED && sustainedAbove >= NOISE_ATTACK_SUSTAIN_TICKS &&
                (level < speechPeak * NOISE_ATTACK_PEAK_HALF || isPlateau())
            ) {
                // 抬升噪声（AGC 泵态/播放后增益慢释）：噪声底快速上攻
                noiseFloor = (noiseFloor + (level - noiseFloor) * NOISE_ATTACK_RATE)
                    .coerceAtMost(NOISE_FLOOR_MAX)
            }
        } else {
            sustainedAbove = 0
            // 低于阈值：噪声底慢速上下跟随
            noiseFloor += (level - noiseFloor) * NOISE_TRACK_RATE
        }

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
            buffer.add(block.copyOf())
            null
        } else {
            belowTicks++
            aboveTicks = 0
            // S60：判停倒计时块也进 PCM（尾垫 ≤600ms）——句尾弱音节跌破 end 线后仍被
            //  采集。旧行为「尾部静音不进 buffer」在安静域无害（end=0.03，弱音节在线
            //  上）；S56/S58 起 end 线随噪声底上浮（0.19 环境 ≈0.27），句尾 0.1~0.25
            //  的弱音节被判"静音"且音频丢弃 → 「档案」类尾巴物理丢失（S50 病从自适应
            //  门回归）。判停逻辑不变（仍 600ms 判停），噪声免疫全部保留；代价=每句
            //  多带 ≤600ms 尾部环境音，ASR 对尾静音天然容忍。与桌面端分叉点，有意为之。
            buffer.add(block.copyOf())
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
            lastUtteranceRms = levelOf(pcm) // S56：空 ASR 反馈时 floor 上行的电平依据
            return Event.Utterance(pcm, maxLevel)
        }
        buffer.clear()
        return null
    }

    /** Int16 PCM 归一化音量：min(1.0, RMS/8000)（削波信号钳位到 1.0，同 voice_listen.py:48）。 */
    /** S19-1 判起阈值 = max(生效判起线, 噪声底×2.0)。生效判起线 = startLevelOverride
     *  （S51 续听阶段降敏到 end 线 0.03）否则 speechLevelProvider（校准联动）。 */
    private fun startThreshold(): Double =
        maxOf((startLevelOverride ?: speechLevelProvider)(), noiseFloor * ADAPTIVE_START_FACTOR)

    /** S50 判停阈值 = max(end provider, 噪声底×1.4)，独立于判起线（恒 < 判起阈值）。 */
    private fun endThreshold(): Double =
        maxOf(speechEndLevelProvider(), noiseFloor * ADAPTIVE_END_FACTOR)

    /** S19-1 平台判据：最近块电平 (max-min)/mean < [PLATEAU_STABLE_RATIO]（AGC 钉死特征）。 */
    private fun isPlateau(): Boolean {
        if (plateauWindow.size < NOISE_ATTACK_SUSTAIN_TICKS) return false
        val max = plateauWindow.max()
        val min = plateauWindow.min()
        val mean = plateauWindow.average()
        return mean > 0.0 && (max - min) / mean < PLATEAU_STABLE_RATIO
    }

    private fun levelOf(block: ShortArray): Double {
        var sum = 0.0
        for (s in block) sum += s.toDouble() * s
        return minOf(1.0, sqrt(sum / block.size) / RMS_REF)
    }
}
