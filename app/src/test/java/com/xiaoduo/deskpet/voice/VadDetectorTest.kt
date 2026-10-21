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
     *  一并失效）——语音类定 + max_utterance 兑底，幽灵轮由 S19-2 空 ASR 续听兜底。
     *  S69b 语义反转（板测 21:07-21:08 实锤）：说话期上攻禁用——泵噪 ghost 判起后不再
     *  被上攻早滤（其代价=floor 被推到判停线贴说话电平/判起线 1.0 全聋两病），改由
     *  MAX_UTTERANCE 8s 强制收句兜底（幽灵轮 S19-2 空 ASR + S56 floor 上行接管）。 */
    @Test
    fun `S58 pump noise now held to MAX_UTTERANCE (attack disabled while speaking)`() {
        val vad = VadDetector()
        vad.start()
        // 首轮泵态（amp 2000 = level 0.25）：ghost 判起后说话态不上攻 → 30 块内无早滤
        val evs = feedN(vad, block(2000), 30)
        assertTrue(
            "S69b：说话期不上攻，30 块内不再早判停（旧 S58 早滤语义废止）",
            evs.isEmpty(),
        )
        // 8s 强制收句兜底仍工作（判起3+80 → 第 84 块附近产出）
        val late = feedN(vad, block(2000), 55)
        assertTrue(
            "S69b：MAX_UTTERANCE 强制收句兜底仍在",
            late.filterIsInstance<VadDetector.Event.Utterance>().isNotEmpty(),
        )
        assertFalse(vad.isActive)
    }

    /** S52 回归锁（板端 17:43 实锤）：持续元音（VAD 0.15~0.30 稳定 2s+）曾被 plateau
     *  通道判为「AGC 钉死噪声」→ 句中判停。S58 复活上攻后此锁反转；S69b 说话期禁上攻，
     *  **S52 诉求回归**——稳态长音不再被中途切（判停可用性已由 S69 熔断以不依赖 floor
     *  的方式接管，8s 强制收句兜底）。 */
    @Test
    fun `S52 sustained stable tone NOT gated mid-way (attack disabled while speaking)`() {
        val vad = VadDetector()
        vad.start()
        feedN(vad, block(48), 5)                    // 安静建底
        val events = feedN(vad, block(1280), 30)   // 3 秒持续稳态音：无中途判停
        assertTrue("S69b：稳态长音不再被上攻中途切（S52 诉求回归）", events.isEmpty())
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

    // ---------------- S69 底噪熔断判停（2026-10-21 板端双向失败实锤） ----------------
    // 板载自噪 0.19-0.27 恒定（19:33:08 轮 diag 连续 4 行 0.19 低谷、4 秒气口不切；
    // （七）15:40 两轮永不判停）。floor 稳态收敛于底噪后慢跟随推力归零、判停线骑
    // 底噪带（0.19×1.4=0.266），底噪块级电平绕线摆动 → belowTicks（判停）与
    // sustainedAbove（上攻）双计数器互相清零、floor 钉死 → 既不判停也不上攻死锁。
    // 熔断=说话态下「低峰比+平坦」底噪证据窗 → 强制收句，不再依赖 floor 跟踪。
    // 证据：20261021_190723/192937 板端日志（（九）板测取证）。

    /** 判停线骑底噪带 + floor 预热稳态：说话期波动块（plateau 不成立 + 弱块半峰
     *  被窗口打断）不上攻，说完后底噪摆动序列 T/F 交替令双计数器清零 → 旧实现
     *  既不判停也不上攻（拖 80 块强制结束）；熔断应 ~2s 内收句。 */
    @Test
    fun `S69 stall fuse collects utterance on sustained floor plateau after speech`() {
        // 判起线 0.3（底噪预热期不 ghost 判起）、判停线 0.266（=floor 稳态 0.19×1.4 骑带）
        val vad = VadDetector(speechLevelProvider = { 0.3 }, speechEndLevelProvider = { 0.266 })
        vad.start()
        // 底噪预热 30 块：慢跟随把 floor 收敛到 ≈0.185 稳态（推力→0，板端常驻态）
        repeat(30) { assertNull(vad.feed(block(1520))) }
        assertEquals(0.0, vad.currentNoiseFloor() - 0.185, 0.01) // 预热生效锚
        // 波动说话 13 块（0.5/0.9 交替）：≥判起 0.37 → 成句态；plateau 不成立、
        // 弱块 0.5 > peak×0.5 → 半峰不成立 → 说话期不上攻，floor 保持稳态
        repeat(13) { assertNull(vad.feed(if (it % 2 == 0) block(4000) else block(7200))) }
        // 底噪摆动序列（T 稀疏死锁域）：0.19(F)×3 + 0.27(T)×1 循环——F 块 below 被零星
        // T 块清零（判停永不凑齐）、sa 被 F 清零（上攻永不触发）、floor 钉稳态（推力归零）
        // → 旧实现三通道全失效死锁（板端 19:33:08 轮 4s 气口不切同型）
        val jitter = listOf(1520, 1520, 1520, 2160)
        var ev: VadDetector.Event? = null
        var consumed = 0
        while (consumed < 45 && ev == null) {
            ev = vad.feed(block(jitter[consumed % jitter.size])); consumed++
        }
        assertTrue("底噪平台 2s 内应熔断收句（旧实现双计数器死锁拖 80 块）", ev is VadDetector.Event.Utterance)
    }

    /** 波动说话（0.3~1.0 交替）不熔断：plateau 判据不成立，40 块全程零提前截断，
     *  直到尾部静音收句且语音块全保留——S52「长元音/平稳段同型误伤」防线。 */
    @Test
    fun `S69 stall fuse spares fluctuating genuine speech`() {
        val vad = VadDetector()
        vad.start()
        repeat(3) { assertNull(vad.feed(block(8000))) } // 1.0 判起
        repeat(40) { assertNull(vad.feed(if (it % 2 == 0) block(2400) else block(8000))) } // 波动说话 4s
        // 尾部静音收句：utterance 应含全部语音块（熔断未截断）
        var ev: VadDetector.Event? = null
        var consumed = 0
        while (consumed < 8 && ev == null) { ev = vad.feed(block(0)); consumed++ }
        assertTrue("静音兜底应收句", ev is VadDetector.Event.Utterance)
        assertEquals(
            "utterance 应含全部语音块（熔断未截断）",
            (3 + 40 + consumed) * VadDetector.BLOCK_SAMPLES,
            (ev as VadDetector.Event.Utterance).pcm.size,
        )
    }

    /** 短气口（5 块）续说不被切：气口块判停窗未凑齐（5<6）且熔断证据窗被语音块
     *  打破（窗口混入 0.45/0.8 → 不平坦）→ 句中换气安全（19:33:08 轮 4s 气口的
     *  短版对照——气口 ≥证据窗才由熔断/续听合并接管）。 */
    @Test
    fun `S69 stall fuse spares short mid-sentence pause then merges on silence end`() {
        val vad = VadDetector()
        vad.start()
        repeat(3) { assertNull(vad.feed(block(3600))) } // 0.45 判起
        repeat(5) { assertNull(vad.feed(if (it % 2 == 0) block(3600) else block(6400))) } // 波动说话
        repeat(5) { assertNull(vad.feed(block(1520))) } // 0.5s 气口（底噪）：<判停6、证据窗未填满
        repeat(5) { assertNull(vad.feed(if (it % 2 == 0) block(3600) else block(6400))) } // 续说
        var ev: VadDetector.Event? = null
        var consumed = 0
        while (consumed < 8 && ev == null) { ev = vad.feed(block(0)); consumed++ }
        assertTrue(ev is VadDetector.Event.Utterance)
        assertEquals(
            "气口前后语音应在同一次收句内（18 语音块 + 尾部收句块）",
            (3 + 5 + 5 + 5 + consumed) * VadDetector.BLOCK_SAMPLES,
            (ev as VadDetector.Event.Utterance).pcm.size,
        )
    }

    // ---------------- S69b floor 污染三连修（2026-10-21 板测实锤） ----------------
    // 板测 3 轮实锤（21:07-21:08）：S58 上攻通道在说话期被弱音节（半峰判据）误触发，
    // floor 被说话电平污染——推到 0.2-0.4 域=判停线贴说话弱音节→提前判停（第 1 轮
    // 「帮我查」切尾）；推到 NOISE_FLOOR_MAX=0.5 封顶=判起线 floor×2=1.0→全聋（第 3 轮
    // 说话 diag 0.33-0.42+削波 1.0 判不起，30s 超时）；钉底噪稳态=判停线骑底噪→死锁
    // （（七））。修：①说话期禁上攻（floor 只在未判起期学习）②判停/判起线绝对上限。

    /** 说话期弱音节交替块不再触发上攻：floor 恒 0（判起后 floor 不被说话电平污染）。 */
    @Test
    fun `S69b attack channel disabled while speaking - floor stays clean`() {
        val vad = VadDetector()
        vad.start()
        repeat(3) { assertNull(vad.feed(block(6400))) } // 0.8 判起
        repeat(40) { assertNull(vad.feed(if (it % 2 == 0) block(2000) else block(6400))) } // 弱音节 0.25 交替
        assertEquals(
            "说话期 floor 不被说话电平污染（旧实现被弱音节半峰上攻爬到 0.25 域）",
            0.0,
            vad.currentNoiseFloor(),
            1e-9,
        )
    }

    /** S56 空 ASR floor 上行不得把判停线推过 0.30：响话轮（整段 RMS≈0.6）后 raise×2
     *  floor→0.5 封顶域，旧判停线 0.7 会切说话弱音节（板测第 1 轮「帮我查」同型）。 */
    @Test
    fun `S69b end threshold clamped at 0_30 after S56 floor raise`() {
        val vad = VadDetector()
        vad.start()
        repeat(3) { assertNull(vad.feed(block(6400))) } // 判起 0.8
        repeat(6) { assertNull(vad.feed(block(6400))) } // 说话
        var ev: VadDetector.Event? = null
        repeat(6) { if (ev == null) ev = vad.feed(block(0)) } // 判停成句
        assertTrue(ev is VadDetector.Event.Utterance)
        vad.raiseNoiseFloorFromLastUtterance()
        vad.raiseNoiseFloorFromLastUtterance()
        assertTrue(
            "S56 上行后判停线不得超 0.30（旧实现 0.5×1.4=0.7）",
            vad.currentEndThreshold() <= 0.30 + 1e-9,
        )
    }

    /** 判起线钳 0.32（S69c 二轮板测修正，原 0.35）：floor 被污染（0.5 封顶域）后，
     *  弱尾「的档案」域（0.32~0.35）说话仍必判起——板测 21:22 实锤 0.25-0.29 弱尾
     *  被判停线切后判起线接不住（0.266-0.35 盲区）。0.32 保持 end(0.30)<start 不变量，
     *  底噪上沿 0.27 仍有安全边。 */
    @Test
    fun `S69c start threshold clamp lowered to 0_32 - soft tail speech still triggers`() {
        val vad = VadDetector()
        vad.start()
        repeat(3) { assertNull(vad.feed(block(6400))) } // 判起 0.8
        repeat(6) { assertNull(vad.feed(block(6400))) } // 说话
        var ev: VadDetector.Event? = null
        repeat(6) { if (ev == null) ev = vad.feed(block(0)) } // 判停成句
        assertTrue(ev is VadDetector.Event.Utterance)
        vad.raiseNoiseFloorFromLastUtterance()
        vad.raiseNoiseFloorFromLastUtterance() // floor→0.5 封顶域（lastUtteranceRms≈0.62）
        vad.start() // 新聆听轮（floor 跨轮保留）
        repeat(3) { assertNull(vad.feed(block(2720))) } // 0.34 说话：旧钳 0.35 判不起（盲区）
        assertTrue("判起线钳 0.32：0.34 弱尾说话仍可判起", vad.isSpeaking)
    }
}
