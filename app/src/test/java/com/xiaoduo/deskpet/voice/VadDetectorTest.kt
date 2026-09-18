package com.xiaoduo.deskpet.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VadDetector 单测：合成 PCM 块（恒定值块 = 精确 RMS）拼场景。
 * 量纲：level = RMS/8000；loud(1000) → 0.125 ≥ 0.05；quiet(100) → 0.0125 < 0.05。
 * S50：判停线独立 0.03（判起线仍 0.05），判停窗 800ms。
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
        assertNull(vad.feed(zero))    // below=6
        assertNull(vad.feed(zero))    // below=7（800ms 窗内不判停）
        assertTrue(vad.feed(zero) is VadDetector.Event.Utterance) // below=8 判停（S50 800ms）
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
    fun `three loud blocks trigger and eight silence blocks end utterance`() {
        val vad = VadDetector()
        vad.start()
        assertTrue(feedN(vad, loud, 3).isEmpty())
        assertTrue(vad.isSpeaking)

        assertTrue(feedN(vad, zero, 7).isEmpty()) // 7 块静音不判停（句中短停顿保护，S50 800ms）
        val ev = vad.feed(zero) // 第 8 块判停（800ms，S50 2026-09-18 拍板回桌面端原值）
        assertTrue(ev is VadDetector.Event.Utterance)
        // deque 里只有 3 触发块 → utterance = 3 块 = 9600 字节，恰为 min_utterance 边界（>= 通过）
        assertEquals(3 * VadDetector.BLOCK_SAMPLES, (ev as VadDetector.Event.Utterance).pcm.size)
        assertFalse(vad.isActive) // 单次触发后停
    }

    @Test
    fun `pre-roll preserves three pre-trigger low blocks and excludes trailing silence`() {
        val vad = VadDetector()
        vad.start()
        feedN(vad, quiet, 3) // 触发前低电平句首段
        feedN(vad, loud, 3) // 判起
        assertTrue(vad.isSpeaking)
        feedN(vad, loud, 2) // 继续说话
        val events = feedN(vad, zero, 8) // 判停（S50 800ms）

        assertEquals(1, events.size)
        val pcm = (events[0] as VadDetector.Event.Utterance).pcm
        // 3 pre-roll + 3 触发块 + 2 说话块 = 8 块；尾部 8 块静音不进 buffer
        assertEquals(8 * VadDetector.BLOCK_SAMPLES, pcm.size)
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

    /** S19-1：AGC 泵态--语音后噪声被抬到 0.25（amp 2000），判停阈值应自适应跟高，
     *  utterance 正常结束而不是拖满 8s 强制上限（rk3568 实测病灶 132800 samples 修复目标） */
    @Test
    fun `agc pumped noise floor adapts and ends utterance promptly`() {
        val vad = VadDetector()
        vad.start()
        // 安静期（amp 48 -> level 0.006）建噪声底
        feedN(vad, block(48), 5)
        // 说话：amp 6000 -> level 0.75
        feedN(vad, block(6000), 5)
        assertTrue(vad.isSpeaking)
        // AGC 泵态：噪声被抬到 amp 2000（level 0.25）持续送 -- 若不自适应则 0.25 > 0.05 永不判停
        val evs = feedN(vad, block(2000), 40) // 4s 泵态噪声
        val utterance = evs.filterIsInstance<VadDetector.Event.Utterance>().firstOrNull()
        assertTrue("泵态噪声下 4s 内应判停（实测应远低于 80 块上限）", utterance != null)
        // 判停发生在早期（阈值跟高后），而非撞 max utterance
        val idx = evs.indexOfFirst { it is VadDetector.Event.Utterance }
        assertTrue("判停应发生在泵态噪声开始后 20 块内，实际第 ${idx + 1} 块", idx < 20)
    }

    /** S19-1：AGC 指数衰减仿真--泵态噪声 0.30 衰减，判停不依赖衰减完成 */
    @Test
    fun `agc decay curve ends utterance before full decay`() {
        val vad = VadDetector()
        vad.start()
        feedN(vad, block(48), 5)                       // 安静建底
        feedN(vad, block(6000), 5)                     // 说话
        assertTrue(vad.isSpeaking)
        // 噪声从 0.30 指数衰减（rk809 AGC 慢释放曲线），全程高于校准阈值 0.05
        var level = 0.30
        var fired = -1
        for (i in 1..40) {
            val amp = (level * 8000).toInt().coerceAtLeast(1)
            val ev = vad.feed(block(amp))
            if (ev is VadDetector.Event.Utterance) { fired = i; break }
            level *= 0.94 // 慢释放：每块衰减 6%（8s 后仍 0.30×0.94^80≈0.002）
        }
        assertTrue("衰减曲线下应判停（40 块内）", fired > 0)
        assertTrue("判停应发生在衰减早期（<25 块），实际第 $fired 块", fired < 25)
    }

    /** S50：判停/判起线拆分--安静域（噪声底 0.006）判起线保持校准 0.05 语义不变；
     *  判停线独立 0.03：连续弱音节 0.045（轻声句尾）不再计静音切尾（原 0.05 线 6 块即切，
     *  实测病灶「盘点当前有哪」四连切），真静音 8 块（800ms）仍正常判停 */
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
        // 真静音（噪声底 0.006）8 块（800ms）判停
        val evs = feedN(vad, block(48), 8)
        assertEquals(1, evs.size)
        assertTrue(evs[0] is VadDetector.Event.Utterance)
        assertFalse(vad.isActive)
    }

    /** S19-1 泵态幽灵轮：首轮（EMA 未学到泵态）允许短幽灵 utterance（<2.5s，非 8s），
     *  二轮（EMA 已学到）泵态噪声不再误判起 */
    @Test
    fun `pumped noise ghosts once briefly then becomes immune`() {
        val vad = VadDetector()
        vad.start()
        // 首轮泵态（amp 2000 = level 0.25）：前 3 块 provider 判起->幽灵轮，
        // 但平台/半峰通道应让 utterance 远早于 8s 结束
        val evs = feedN(vad, block(2000), 30)
        val ghost = evs.filterIsInstance<VadDetector.Event.Utterance>().firstOrNull()
        assertTrue("首轮允许短幽灵轮", ghost != null)
        assertTrue(
            "幽灵轮应 <2.5s（25 块），实际 ${ghost!!.pcm.size / VadDetector.BLOCK_SAMPLES} 块",
            ghost.pcm.size / VadDetector.BLOCK_SAMPLES < 25,
        )
        // 二轮（调用方重启，EMA 保持）：泵态噪声不再误判起
        vad.start()
        val evs2 = feedN(vad, block(2000), 20)
        assertTrue(evs2.isEmpty())
        assertFalse(vad.isSpeaking)
        // 真说话 0.75 仍判起
        feedN(vad, block(6000), 3)
        assertTrue(vad.isSpeaking)
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
        repeat(8) { ev = vad.feed(ByteArray(VadDetector.BLOCK_BYTES)) } // 800ms 判停（S50）
        assertTrue(ev is VadDetector.Event.Utterance)
        assertEquals(3 * VadDetector.BLOCK_SAMPLES, (ev as VadDetector.Event.Utterance).pcm.size)
    }
}
