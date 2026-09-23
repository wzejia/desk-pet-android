package com.xiaoduo.deskpet.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VadDetector 单测：合成 PCM 块（恒定值块 = 精确 RMS）拼场景。
 * 量纲：level = RMS/8000；loud(1000) → 0.125 ≥ 0.05；quiet(100) → 0.0125 < 0.05。
 * S50：判停线独立 0.03（判起线仍 0.05），判停窗 600ms（S52 晚回调）。
 */
class VadDetectorTest {

    private fun block(value: Int) = ShortArray(VadDetector.BLOCK_SAMPLES) { value.toShort() }

    private val loud = block(1000)
    private val quiet = block(100)
    private val zero = block(0)

    private fun feedN(vad: VadDetector, b: ShortArray, n: Int): List<VadDetector.Event> =
        (1..n).mapNotNull { vad.feed(b) }

    /** S18：阈值可注入（校准联动）--provider 抬高后原 loud(0.125) 沦为静音，更响块才算语音。
     *  S50：判停线拆分后双 provider 同抬，保「双线均可注入」语义。 */
    @Test
    fun `injected speech level provider raises the bar`() {
        val vad = VadDetector(speechLevelProvider = { 0.2 }, speechEndLevelProvider = { 0.2 })
        vad.start()
        // loud=0.125 < 0.2 -> 静音；讲话中阈值抬高后旧 loud 块计为静音
        repeat(3) { assertNull(vad.feed(block(2000))) } // 0.25 >= 0.2 判起
        assertTrue(vad.isSpeaking)
        assertNull(vad.feed(loud))    // 0.125 < 0.2 -> 说话中静音块（below=1）
        assertNull(vad.feed(zero))    // below=2
        assertNull(vad.feed(zero))    // below=3
        assertNull(vad.feed(zero))    // below=4
        assertNull(vad.feed(zero))    // below=5
        assertTrue(vad.feed(zero) is VadDetector.Event.Utterance) // below=6 判停（600ms，S52 晚回调）
    }

    /** S18：默认 provider 与硬编码 0.05 行为一致（回归保障） */
    @Test
    fun `default provider keeps 0_05 semantics`() {
        val vad = VadDetector()
        vad.start()
        assertNull(vad.feed(block(360)))  // 0.045 < 0.05 静音
        repeat(3) { assertNull(vad.feed(block(420))) } // 0.0525 >= 0.05 判起
        assertTrue(vad.isSpeaking)
    }

    @Test
    fun `two loud blocks do not trigger speech start`() {
        val vad = VadDetector()
        vad.start()
        assertNull(vad.feed(loud))
        assertNull(vad.feed(loud))
        assertFalse(vad.isSpeaking)
        assertTrue(vad.isActive)
    }

    @Test
    fun `three loud blocks trigger and six silence blocks end utterance`() {
        val vad = VadDetector()
        vad.start()
        assertTrue(feedN(vad, loud, 3).isEmpty())
        assertTrue(vad.isSpeaking)

        assertTrue(feedN(vad, zero, 5).isEmpty()) // 5 块静音不判停（句中短停顿保护，600ms）
        val ev = vad.feed(zero) // 第 6 块判停（600ms，S52 晚回调）
        assertTrue(ev is VadDetector.Event.Utterance)
        // S60：3 触发块 + 6 判停倒计时尾垫 = 9 块（倒计时块进 PCM，句尾弱音节不再丢）
        assertEquals(9 * VadDetector.BLOCK_SAMPLES, (ev as VadDetector.Event.Utterance).pcm.size)
        assertFalse(vad.isActive) // 单次触发后停
    }

    @Test
    fun `pre-roll preserves three pre-trigger low blocks and S60 tail padding included`() {
        val vad = VadDetector()
        vad.start()
        feedN(vad, quiet, 3) // 触发前低电平句首段
        feedN(vad, loud, 3) // 判起
        assertTrue(vad.isSpeaking)
        feedN(vad, loud, 2) // 继续说话
        val events = feedN(vad, zero, 6) // 判停（600ms，S52 晚回调）

        assertEquals(1, events.size)
        val pcm = (events[0] as VadDetector.Event.Utterance).pcm
        // 3 pre-roll + 3 触发块 + 2 说话块 + 6 尾垫（S60）= 14 块
        assertEquals(14 * VadDetector.BLOCK_SAMPLES, pcm.size)
        assertEquals(100, pcm[0].toInt()) // 首块 = 低电平 pre-roll（防丢首字）
        assertEquals(100, pcm[3 * VadDetector.BLOCK_SAMPLES - 1].toInt())
        assertEquals(1000, pcm[3 * VadDetector.BLOCK_SAMPLES].toInt()) // 第 4 块起为触发块
    }

    @Test
    fun `short noise is discarded and detector keeps listening`() {
        val vad = VadDetector()
        vad.start()
        assertTrue(feedN(vad, loud, 2).isEmpty()) // 2 块噪音不判起
        assertTrue(feedN(vad, zero, 3).isEmpty()) // 连续计数被静音清零
        assertFalse(vad.isSpeaking)
        assertTrue(vad.isActive)

        // 噪音不累积，随后正常说话仍可触发
        feedN(vad, loud, 3)
        assertTrue(vad.isSpeaking)
        val events = feedN(vad, zero, 8)
        assertEquals(1, events.size)
        assertTrue(events[0] is VadDetector.Event.Utterance)
    }

    @Test
    fun `listen timeout fires after 30s and carries max level`() {
        val vad = VadDetector()
        vad.start()
        val blip = block(240) // level = 240/8000 = 0.03，低于阈值
        assertTrue(feedN(vad, blip, 2).isEmpty()) // 不达判起
        assertTrue(feedN(vad, zero, 298).isEmpty()) // 累计消费 300 块 = 30s
        assertTrue(vad.isActive)

        val ev = vad.feed(zero) // 第 301 块触发超时
        assertTrue(ev is VadDetector.Event.Timeout)
        assertEquals(0.03, (ev as VadDetector.Event.Timeout).maxLevel, 1e-9)
        assertFalse(vad.isActive)
    }

    @Test
    fun `max utterance 8s forces end with accumulated speech only`() {
        val vad = VadDetector()
        vad.start()
        // S19-1 起恒定幅度会触发平台通道判停（稳定非语音特征），真实语音波动：
        // 交替 800/1400（level 0.1/0.175，均值波动 >25%），8s 语义保持
        fun speechBlock(i: Int) = block(if (i % 2 == 0) 800 else 1400)
        (1..3).forEach { vad.feed(speechBlock(it)) } // 判起（buffer = 3 触发块）
        assertTrue((1..80).mapNotNull { vad.feed(speechBlock(it + 3)) }.isEmpty()) // 80 块全消费
        val ev = vad.feed(speechBlock(84)) // 第 81 块：speechTicks=80 触发强制结束
        assertTrue(ev is VadDetector.Event.Utterance)
        assertEquals(
            (3 + 80) * VadDetector.BLOCK_SAMPLES,
            (ev as VadDetector.Event.Utterance).pcm.size,
        )
        assertFalse(vad.isActive)
    }

    @Test
    fun `single shot stops after utterance until caller restarts`() {
        val vad = VadDetector()
        vad.start()
        feedN(vad, loud, 3)
        feedN(vad, zero, 8)
        assertFalse(vad.isActive)
        assertNull(vad.feed(loud)) // 停止后喂入无效

        vad.start() // 调用方重新 start 进下一轮
        assertTrue(vad.isActive)
        feedN(vad, loud, 3)
        assertTrue(vad.isSpeaking)
    }

    @Test
    fun `sub-threshold blocks never trigger speech start (threshold guard)`() {
        // 负样本守护：level=0.03 < 0.05 亚阈值块，连续喂 10 块也不得判起。
        // 阈值若被改成 0.01，本条必须变红。
        val vad = VadDetector()
        vad.start()
        val subThreshold = block(240) // level = 240/8000 = 0.03
        val events = feedN(vad, subThreshold, 10)
        assertTrue(events.isEmpty())
        assertFalse(vad.isSpeaking)
        assertTrue(vad.isActive) // 未判起也未超时，仍在听
    }

    @Test
    fun `clipping signal level is clamped to 1`() {
        // 削波信号 rms/8000 = 3.75 → 钳位 1.0（voice_listen.py:48）；maxLevel 不应超 1.0
        val vad = VadDetector()
        vad.start()
        val clipping = block(30000)
        assertTrue(feedN(vad, clipping, 2).isEmpty()) // 2 块不判起
        assertTrue(feedN(vad, zero, 298).isEmpty()) // 凑满 300 块
        val ev = vad.feed(zero)
        assertTrue(ev is VadDetector.Event.Timeout)
        assertEquals(1.0, (ev as VadDetector.Event.Timeout).maxLevel, 1e-9)
    }

    /** S52：噪声上攻停用后，泵态噪声不再被中途判停——语音类定，由 max_utterance 8s 兜底；
     *  幽灵轮由管线层 S19-2 空 ASR 续听兜底 */
    @Test
    fun `S58 sustained pump noise IS gated mid-way (attack re-enabled)`() {
        val vad = VadDetector()
        vad.start()
        // 安静期（amp 48 -> level 0.006）建噪声底
        feedN(vad, block(48), 5)
        // 说话：amp 6000 -> level 0.75
        feedN(vad, block(6000), 5)
        assertTrue(vad.isSpeaking)
        // 泵态噪声（amp 2000 -> level 0.25）持续送：S58 上攻复活 → floor 快速抬升 →
        // end 越过 0.25 → 中途判停滤除噪声（不再拖 8s；原「不应判停」锁随拍板反转，
        // S52 误伤知识见 git 历史与本仓 VadDetector KDoc）
        val evs = feedN(vad, block(2000), 40)
        assertTrue(
            "S58：稳态泵噪应被上攻滤除（8s 上限前中判停）",
            evs.filterIsInstance<VadDetector.Event.Utterance>().isNotEmpty(),
        )
        assertFalse(vad.isActive)
    }

    /** S52：上攻停用后，衰减泵态噪声不再被中途判停（有界拖尾由 max_utterance 兑底） */
    @Test
    fun `S58 agc decay curve tail IS cut mid-way (accepted trade-off)`() {
        val vad = VadDetector()
        vad.start()
        feedN(vad, block(48), 5)                       // 安静建底
        feedN(vad, block(6000), 5)                     // 说话
        assertTrue(vad.isSpeaking)
        // 噪声从 0.30 指数衰减（rk809 AGC 慢释放曲线）：S58 上攻复活后 half-peak 通道
        // 会切衰减尾——接受为代价（9e82c03 实测长句档案名完整；S52 教训留档 git 历史）
        var level = 0.30
        val fired = mutableListOf<Int>()
        for (i in 1..40) {
            val amp = (level * 8000).toInt().coerceAtLeast(1)
            val ev = vad.feed(block(amp))
            if (ev is VadDetector.Event.Utterance) fired += i
            level *= 0.94
        }
        assertTrue("S58：衰减尾应被上攻切除（fired 非空）", fired.isNotEmpty())
        assertFalse(vad.isActive)
    }

    /** S50：判停/判起线拆分--安静域（噪声底 0.006）判起线保持校准 0.05 语义不变；
     *  判停线独立 0.03：连续弱音节 0.045（轻声句尾）不再计静音切尾（原 0.05 线 6 块即切，
     *  实测病灶「盘点当前有哪」四连切），真静音 6 块（600ms）仍正常判停 */
    @Test
    fun `quiet domain start keeps 0_05 while end splits to 0_03`() {
        val vad = VadDetector()
        vad.start()
        feedN(vad, block(48), 5)          // 建噪声底 0.006
        assertNull(vad.feed(block(360)))  // 0.045 < 0.05 判起侧静音（判起线不变）
        feedN(vad, block(420), 3)         // 0.0525 >= 0.05 判起
        assertTrue(vad.isSpeaking)
        // S50 修复核心：连续弱音节 0.045 >= 判停线 0.03 → 计说话不切尾
        // （只喂 8 块：恒定值块 ≥10 块会触发平台通道判停，属合成信号病理，真语声有波动）
        feedN(vad, block(360), 8)
        assertTrue(vad.isSpeaking)
        // 真静音（噪声底 0.006）6 块（600ms）判停
        val evs = feedN(vad, block(48), 8)
        assertEquals(1, evs.size)
        assertTrue(evs[0] is VadDetector.Event.Utterance)
        assertFalse(vad.isActive)
    }

    /** S52：上攻停用后，首轮泵态不再产生短幽灵 utterance（原靠上攻抬 start 阈值的「免疫」
     *  一并失效）——语音类定 + max_utterance 兑底，幽灵轮由 S19-2 空 ASR 续听兜底 */
    @Test
    fun `S58 sustained pump noise from start yields early utterance (attack filters)`() {
        val vad = VadDetector()
        vad.start()
        // 首轮泵态（amp 2000 = level 0.25）：判起后上攻抬 end → ~2s 内早判停（噪声滤除），
        // 原「无幽灵判停」锁随 S58 拍板反转（幻觉轮由 S19-2 空 ASR 兜底 + S56 floor 上行）
        val evs = feedN(vad, block(2000), 30)
        assertTrue(
            "S58：稳态泵噪应产出早判停 utterance",
            evs.filterIsInstance<VadDetector.Event.Utterance>().isNotEmpty(),
        )
        assertFalse(vad.isActive)
    }

    /** S52 回归锁（板端 17:43 实锤）：持续元音（VAD 0.15~0.30 稳定 2s+）被 plateau 通道
     *  判为「AGC 钉死噪声」→ 阈值上攻越过说话者电平 → 句中判停。上攻停用后不得中途判停。 */
    @Test
    fun `S58 sustained stable tone IS gated mid-way (attack re-enabled)`() {
        val vad = VadDetector()
        vad.start()
        feedN(vad, block(48), 5)                    // 安静建底
        // 持续稳定音（amp 1280 -> level 0.16，波动 <25% = plateau Signature）
        // S58 拍板：上攻通道复活——稳态噪声必须被滤（0.19 环境实测 9e82c03 判停正常）；
        // 代价=长元音同型有被切风险（S52 教训留档：真语声有波动、恒定值合成块必中）
        val events = feedN(vad, block(1280), 30)   // 3 秒持续
        assertTrue("S58：稳态音应被上攻滤除（中途中判停）", events.isNotEmpty())
        assertFalse(vad.isActive)
    }

    @Test
    fun `byte array feed matches short array path`() {
        val bytes = ByteArray(VadDetector.BLOCK_BYTES) { i ->
            if (i % 2 == 0) (1000 and 0xFF).toByte() else (1000 shr 8).toByte()
        }
        val vad = VadDetector()
        vad.start()
        repeat(3) { assertNull(vad.feed(bytes)) }
        assertTrue(vad.isSpeaking)
        var ev: VadDetector.Event? = null
        repeat(6) { ev = vad.feed(ByteArray(VadDetector.BLOCK_BYTES)) } // 600ms 判停（S52 晚回调）
        assertTrue(ev is VadDetector.Event.Utterance)
        assertEquals(9 * VadDetector.BLOCK_SAMPLES, (ev as VadDetector.Event.Utterance).pcm.size) // S60：+6 尾垫
    }

    @Test
    fun `S60 soft tail below end line is included via countdown padding`() {
        // 板端实证（2026-09-21 20:51）：「帮我查询2019年的所有档案」尾二字「档案」音量
        // 跌破自适应 end 线（噪声域 ≈0.27）被判静音且音频丢弃 → ASR 缺尾。S60 尾垫锁：
        // 判停倒计时 6 块（含弱音节）必须进 PCM。
        val vad = VadDetector()
        vad.start()
        feedN(vad, loud, 5) // 判起 + 说话（0.125）
        val events = feedN(vad, quiet, 6) // 句尾弱音节 0.012 < end 0.03 → 判停倒计时
        assertEquals(1, events.size)
        val pcm = (events[0] as VadDetector.Event.Utterance).pcm
        // 3 判起 + 2 说话 + 6 尾垫 = 11 块；末样本 = 弱音节 100（旧行为末样本会是 1000）
        assertEquals(11 * VadDetector.BLOCK_SAMPLES, pcm.size)
        assertEquals(100, pcm[pcm.size - 1].toInt())
    }


    // ---- S56 ASR 裁判式噪声底上行（2026-09-21 生产板 0.19 稳态噪声实证）----

    /** 喂一个纯噪声假语句：噪声块 0.19 判起 → 说话中拖满 8s → MAX_UTTERANCE 强制截断成句。 */
    private fun feedPhantomNoiseTurn(vad: VadDetector): VadDetector.Event {
        vad.start()
        repeat(3) { assertNull(vad.feed(block(1520))) } // 0.19 ≥ 判起 0.05 → 3 块判起
        var ev: VadDetector.Event? = null
        repeat(85) { if (ev == null) ev = vad.feed(block(1520)) } // 80 块强制截断
        return ev!!
    }

    @Test
    fun `S56 empty-asr feedback raises floor past noise level`() {
        val vad = VadDetector()
        val baseline = vad.currentNoiseFloor()
        val ev = feedPhantomNoiseTurn(vad)
        assertTrue(ev is VadDetector.Event.Utterance)
        vad.raiseNoiseFloorFromLastUtterance()
        assertTrue("floor 应上行（单轮 0.6 速率）", vad.currentNoiseFloor() > baseline)
        // 判起线 = floor×2 = 0.232 > 噪声 0.19 → 幻觉轮从此不再成型（噪声过滤生效）
        assertTrue("判起线应高过噪声（幻觉轮被滤）",
            maxOf(VadDetector.SPEECH_LEVEL, vad.currentNoiseFloor() * VadDetector.ADAPTIVE_START_FACTOR) > 0.19)
        assertTrue("floor 封顶 0.5 防病理", vad.currentNoiseFloor() <= VadDetector.NOISE_FLOOR_MAX)
    }

    @Test
    fun `S56 after adaptation endpointing fires in noise before 8s cap`() {
        // 真实动力学：①幻觉轮空反馈一次（floor 0.005→0.116）→ ②判起线 0.232>噪声 0.19，
        // 幻觉轮绝迹，慢速跟踪在聆听期接管（噪声块终于落在阈值下方可更新 floor）→
        // ③end=max(0.03,floor×1.4) 越过噪底 → 判停复活（600ms 噪声尾即成句，不再拖 8s）。
        val vad = VadDetector()
        feedPhantomNoiseTurn(vad)
        vad.raiseNoiseFloorFromLastUtterance()
        vad.start()
        repeat(20) { assertNull(vad.feed(block(1520))) } // 2s 纯噪声聆听：floor 慢轨升至 ≈0.18
        assertTrue("慢速跟踪后 end 线应越过噪声 0.19", vad.currentEndThreshold() > 0.19)
        // 真说话 0.5 ≥ 判起 0.232 → 成句；停止后噪声尾 6 块（600ms）判停，远早于 8s 上限
        repeat(3) { assertNull(vad.feed(block(4000))) }
        repeat(5) { assertNull(vad.feed(block(4000))) }
        var ev: VadDetector.Event? = null
        repeat(12) { if (ev == null) ev = vad.feed(block(1520)) }
        assertTrue("判停应在 8s 上限前触发", ev is VadDetector.Event.Utterance)
    }

    @Test
    fun `S56 raise is no-op without utterance history and real-speech turns never raise`() {
        val vad = VadDetector()
        val baseline = vad.currentNoiseFloor()
        vad.raiseNoiseFloorFromLastUtterance() // 无成句历史 no-op
        assertEquals(baseline, vad.currentNoiseFloor(), 1e-12)
        // 真语音轮（有文本，不调用 raise）后 floor 不变——语音永不推高 floor
        vad.start()
        repeat(3) { assertNull(vad.feed(block(4000))) } // 判起
        repeat(6) { assertNull(vad.feed(block(4000))) } // 说话
        var ev: VadDetector.Event? = null
        repeat(6) { if (ev == null) ev = vad.feed(block(0)) } // 静音 6 块（600ms）判停成句
        assertTrue(ev is VadDetector.Event.Utterance)
        assertEquals(baseline, vad.currentNoiseFloor(), 1e-9)
    }
}
