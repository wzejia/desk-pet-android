package com.xiaoduo.deskpet.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SoftwareAgc 回归守卫（软件 AGC 票 2026-10-17，纯逻辑面 JVM 可测，款式照
 * AudioCaptureZeroBlockDetectorTest）。
 *
 * 行为验收组（工单 ①~⑦ / SPEC Testing Decisions）：
 * ① 持续远场电平块（0.02 级）输出响度收敛到目标区间 0.05~0.12；
 * ② 超大块（0.9 级）增益回落 1.0 不放大不衰减；增益恒 ≤×8；大块×高增益走 PCM16 饱和不溢出翻转；
 * ③ 增益曲线平滑（相邻块应用增益比 ≤1.5+容差；稳态与电平跳变两序列）；
 * ④ 全零块直通（字节不变/增益按 1.0/nf 不动）；
 * ⑤ enabled=false 输入=输出且不攒任何状态；
 * ⑥ 告警组：纯底噪序列不 fire（安静顶限正常）；持续弱语音序列 fire 恰一次+冷却内不再 fire；
 * ⑦ 状态行每 300 块恰一次。
 *
 * 合成口径：DC 恒幅块（每样本同值）→ RMS=幅值 → maxLevel 域响度=幅值/8000，输入响度
 * 精确可控（0.02 级=幅值 160；0.001 级=幅值 8；0.9 级=幅值 7200）。
 *
 * 变异方向（M1-M4，锚注释在 [SoftwareAgc]）：
 * M1 删增益应用 → ①红；M2 删上下限钳制 → ②两组红（衰减面+顶限面）；
 * M3 删平滑（EMA+步长） → ③两组红；M4 删全零直通 → ④红（nf 被拖走+增益≠1）。
 */
class SoftwareAgcTest {

    /** maxLevel 域响度（生产同口径：min(1, RMS_int16/8000)，经 EnergyGate float 域 ×[AGC_RMS_FLOAT_TO_MAXLEVEL]）。 */
    private fun levelOfBytes(block: ByteArray): Float =
        minOf(1f, EnergyGate.rmsOf(block) * AGC_RMS_FLOAT_TO_MAXLEVEL)

    /** 目标 maxLevel 域响度 → DC 幅值（响度×8000）。 */
    private fun ampOf(level: Float): Int = (level * 8000f).toInt()

    /** DC 恒幅产线块（3200B=100ms）：每样本同值 → maxLevel 域响度=amplitude/8000。 */
    private fun dcBlock(amplitude: Int): ByteArray {
        val b = ByteArray(VadDetector.BLOCK_BYTES)
        var i = 0
        while (i + 1 < b.size) {
            b[i] = (amplitude and 0xFF).toByte()
            b[i + 1] = ((amplitude shr 8) and 0xFF).toByte()
            i += 2
        }
        return b
    }

    private fun decodeSamples(block: ByteArray): ShortArray {
        val out = ShortArray(block.size / 2)
        for (i in out.indices) {
            val lo = block[2 * i].toInt() and 0xFF
            val hi = block[2 * i + 1].toInt() shl 8
            out[i] = (hi or lo).toShort()
        }
        return out
    }

    // ---------------- ① 远场收敛 ----------------

    @Test
    fun `far-field 0_02-level blocks converge into the target loudness band`() {
        val agc = SoftwareAgc()
        val amp = ampOf(0.02f) // 幅值 160
        repeat(10) { agc.process(dcBlock(amp)) } // 暖机：增益自 1.0 爬坡
        repeat(20) { i ->
            val b = dcBlock(amp)
            val tick = agc.process(b)
            val out = levelOfBytes(b)
            assertTrue(
                "远场块 ${11 + i} 输出响度 $out 必须收敛到 [0.05,0.12]",
                out >= 0.05f && out <= 0.12f,
            )
            assertTrue("增益 ${tick.gain} 不得超上限 ×8", tick.gain <= AGC_MAX_GAIN + 1e-3f)
        }
    }

    // ---------------- ② 超大块钳制（上限+下限+饱和） ----------------

    @Test
    fun `super-large 0_9-level blocks hold gain at 1x without boost or attenuation`() {
        val agc = SoftwareAgc()
        val amp = ampOf(0.9f) // 幅值 7200
        repeat(20) { i ->
            val b = dcBlock(amp)
            val tick = agc.process(b)
            assertEquals(
                "块 ${i + 1} 增益必须恰为 1.0（gRaw<1 钳下限，只放大不衰减）",
                1.0f,
                tick.gain,
                1e-6f,
            )
            val out = levelOfBytes(b)
            assertTrue(
                "块 ${i + 1} 输出响度 $out 必须≈输入 0.9（不放大不衰减不爆音）",
                out >= 0.85f && out <= 0.95f,
            )
        }
    }

    @Test
    fun `applied gain never exceeds the x8 clamp even on sustained weak speech`() {
        val agc = SoftwareAgc()
        val amp = ampOf(0.004f) // 幅值 32：gRaw=0.08/0.004=20 远超上限，必须被钳
        repeat(30) { i ->
            val tick = agc.process(dcBlock(amp))
            assertTrue("块 ${i + 1} 增益 ${tick.gain} 不得超 ×8", tick.gain <= AGC_MAX_GAIN + 1e-3f)
        }
    }

    @Test
    fun `pcm16 saturation never flips sign on high-gain loud block`() {
        val agc = SoftwareAgc()
        repeat(10) { agc.process(dcBlock(ampOf(0.02f))) } // 抬到 ~×3.9 应用增益
        val loud = dcBlock(ampOf(1.5f)) // 幅值 12000：×3 增益必越 32767，必须饱和而非翻转
        val tick = agc.process(loud)
        val samples = decodeSamples(loud)
        assertTrue("高增益大块必须走 PCM16 饱和而非溢出翻转（min=${samples.min()}）", samples.all { it >= 1 })
        assertTrue(samples.all { it <= 32767 })
        assertTrue("该块确实被增益（gain=${tick.gain}）", tick.gain >= 1f)
    }

    // ---------------- ③ 增益平滑 ----------------

    @Test
    fun `adjacent applied gain ratio stays within step bound on steady far-field`() {
        val agc = SoftwareAgc()
        var prev = 1.0f
        repeat(30) { i ->
            val tick = agc.process(dcBlock(ampOf(0.02f)))
            val hi = prev * AGC_GAIN_MAX_STEP_RATIO * 1.01f
            val lo = prev / AGC_GAIN_MAX_STEP_RATIO / 1.01f
            assertTrue(
                "块 ${i + 1} 增益 ${tick.gain} 越相邻步长界 [$lo,$hi]",
                tick.gain >= lo && tick.gain <= hi,
            )
            prev = tick.gain
        }
    }

    @Test
    fun `adjacent applied gain ratio stays within step bound across level jumps`() {
        val agc = SoftwareAgc()
        var prev = 1.0f
        repeat(20) { i ->
            val amp = if (i % 2 == 0) ampOf(0.01f) else ampOf(0.04f) // gRaw 目标在 8/2 间跳
            val tick = agc.process(dcBlock(amp))
            val hi = prev * AGC_GAIN_MAX_STEP_RATIO * 1.01f
            val lo = prev / AGC_GAIN_MAX_STEP_RATIO / 1.01f
            assertTrue(
                "跳变序列块 ${i + 1} 增益 ${tick.gain} 越相邻步长界 [$lo,$hi]",
                tick.gain >= lo && tick.gain <= hi,
            )
            prev = tick.gain
        }
    }

    // ---------------- ④ 全零块直通 ----------------

    @Test
    fun `all-zero block passes through with byte identity gain 1 and untouched noise floor`() {
        val agc = SoftwareAgc()
        var lastNf = 0f
        repeat(5) { lastNf = agc.process(dcBlock(ampOf(0.02f))).noiseFloor }
        val zero = ByteArray(VadDetector.BLOCK_BYTES)
        val snapshot = zero.copyOf()
        val tick = agc.process(zero)
        assertArrayEquals("全零块字节必须原样直通", snapshot, zero)
        assertEquals("全零块增益按 1.0 计", 1.0f, tick.gain, 0f)
        assertEquals("全零块不得更新噪声底（全零块不更新任何状态）", lastNf, tick.noiseFloor, 0f)
        assertFalse(tick.reportStatus)
        assertFalse(tick.weakChain)
        val after = agc.process(dcBlock(ampOf(0.02f)))
        assertTrue("后续非全零块必须照常增益（gain=${after.gain}）", after.gain > 1f)
    }

    @Test
    fun `empty and single-byte blocks pass through without touching state`() {
        val agc = SoftwareAgc()
        val empty = ByteArray(0)
        val t1 = agc.process(empty)
        assertEquals(1.0f, t1.gain, 0f)
        assertEquals("空块不初始化噪声底", 0f, t1.noiseFloor, 0f)
        val one = byteArrayOf(0x01)
        val t2 = agc.process(one)
        assertEquals(1.0f, t2.gain, 0f)
        assertArrayEquals(byteArrayOf(0x01), one)
        // 状态仍未初始化：下一真实块以自身响度初始化噪声底（0.001 级）
        val t3 = agc.process(dcBlock(ampOf(0.001f)))
        assertEquals(0.001f, t3.noiseFloor, 1e-6f)
    }

    // ---------------- ⑤ 开关关闭（装配级回退） ----------------

    @Test
    fun `disabled agc returns input unchanged with zero state accumulation`() {
        val agc = SoftwareAgc(enabled = false)
        repeat(400) { i ->
            val b = dcBlock(ampOf(0.02f))
            val snapshot = b.copyOf()
            val tick = agc.process(b)
            assertArrayEquals("禁用块 ${i + 1} 字节必须原样（输入=输出）", snapshot, b)
            assertEquals(1.0f, tick.gain, 0f)
            assertEquals(0f, tick.noiseFloor, 0f)
            assertFalse("禁用期不得攒状态行（400 块一响不响）", tick.reportStatus)
            assertFalse(tick.weakChain)
        }
    }

    // ---------------- ⑥ 弱链路告警 ----------------

    @Test
    fun `sustained weak speech fires weak-chain exactly once and stays silent in cooldown`() {
        val agc = SoftwareAgc()
        repeat(10) { agc.process(dcBlock(ampOf(0.001f))) } // 底噪定标 nf≈0.001
        repeat(49) { i ->
            val tick = agc.process(dcBlock(ampOf(0.004f)))
            assertFalse("弱语音第 ${i + 1} 块不得提前告警", tick.weakChain)
        }
        assertTrue("连续第 50 块弱语音必须 fire 恰一次", agc.process(dcBlock(ampOf(0.004f))).weakChain)
        repeat(250) { i ->
            val tick = agc.process(dcBlock(ampOf(0.004f)))
            assertFalse("冷却内第 ${i + 1} 块不得再 fire（5min 冷却，内存态）", tick.weakChain)
        }
    }

    @Test
    fun `pure noise-floor sequence never fires weak chain even with clamped raw gain`() {
        val agc = SoftwareAgc()
        repeat(400) { i ->
            val tick = agc.process(dcBlock(ampOf(0.001f)))
            // gRaw=80 顶限但非语音级（level < nf×3）：安静顶限正常，不得告警
            assertFalse("安静块 ${i + 1} 不得告警", tick.weakChain)
        }
    }

    // ---------------- ⑦ 状态行 ----------------

    @Test
    fun `status line fires exactly on every 300th processed block`() {
        val agc = SoftwareAgc()
        repeat(299) { i ->
            val tick = agc.process(dcBlock(ampOf(0.001f)))
            assertFalse("第 ${i + 1} 块不得报状态", tick.reportStatus)
        }
        assertTrue("第 300 块必须报状态恰一次", agc.process(dcBlock(ampOf(0.001f))).reportStatus)
        repeat(299) { i ->
            val tick = agc.process(dcBlock(ampOf(0.001f)))
            assertFalse("重数期第 ${i + 1} 块不得报状态", tick.reportStatus)
        }
        assertTrue("第 600 块必须再次报状态", agc.process(dcBlock(ampOf(0.001f))).reportStatus)
    }

    // ---------------- 常数锚（工单算法定案，改值即红） ----------------

    @Test
    fun `agc constants pin the ticket values`() {
        assertTrue("装配级总开关默认开", SOFTWARE_AGC_ENABLED)
        assertEquals(0.08f, AGC_TARGET_LEVEL, 0f)
        assertEquals(8.0f, AGC_MAX_GAIN, 0f)
        assertEquals(1.0f, AGC_MIN_GAIN, 0f)
        assertEquals(0.05f, AGC_NOISE_FLOOR_ALPHA, 0f)
        assertEquals(3.0f, AGC_NOISE_GUARD_FACTOR, 0f)
        assertEquals(0.3f, AGC_GAIN_SMOOTH_ALPHA, 0f)
        assertEquals(1.5f, AGC_GAIN_MAX_STEP_RATIO, 0f)
        assertEquals(50, AGC_WEAK_CHAIN_BLOCKS)
        assertEquals(300, AGC_STATUS_INTERVAL_BLOCKS)
        assertEquals(4.096f, AGC_RMS_FLOAT_TO_MAXLEVEL, 1e-4f)
    }
}
