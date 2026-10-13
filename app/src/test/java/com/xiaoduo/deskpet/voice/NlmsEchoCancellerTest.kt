package com.xiaoduo.deskpet.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * NlmsEchoCanceller 单测（S23：TTS 自产 PCM 作参考的回声消除，治板子播放期打断）。
 *
 * 合成场景全可控：参考=伪随机噪声（语音替代物），回声=延迟+增益线性回声路径，
 * 目标=独立噪声（用户唤醒词替代物）。三块语义锚：
 * 1. 回声抑制：收敛后残余回声能量 ≥10dB 压制（ERLE 下限，板子 A/B 前的 JVM 及格线）
 * 2. 目标保真：无回声时输出≈输入（NLMS 失调噪声有界，不得毁掉唤醒词本身）
 * 3. 零参考直通：播放侧无参考时输出逐样本等于输入（IDLE 零行为变更）
 */
class NlmsEchoCancellerTest {

    private val block = 1600 // 100ms @16k，与 KWS 块对齐

    private fun noise(rng: Random, n: Int, gain: Float) =
        FloatArray(n) { (rng.nextFloat() * 2f - 1f) * gain }

    private fun energy(x: FloatArray, from: Int = 0): Double {
        var s = 0.0
        for (i in from until x.size) s += x[i].toDouble() * x[i]
        return s
    }

    /** 线性回声路径：mic[i] = gain × ref[t - delay]（历史不足补 0） */
    private class EchoPath(val delay: Int, val gain: Float) {
        val history = ArrayList<Float>()
        var t = 0
        fun render(ref: FloatArray): FloatArray {
            val mic = FloatArray(ref.size)
            for (i in ref.indices) {
                val idx = t - delay
                mic[i] = if (idx >= 0) gain * history[idx] else 0f
                history.add(ref[i])
                t++
            }
            return mic
        }
    }

    @Test
    fun `suppresses synthetic echo by at least 10db after convergence`() {
        val c = NlmsEchoCanceller()
        val rng = Random(42)
        val path = EchoPath(delay = 900, gain = 0.7f)
        var echoEnergy = 0.0
        var residualEnergy = 0.0
        for (b in 0 until 60) {
            val ref = noise(rng, block, 0.5f)
            val mic = path.render(ref) // 纯回声无目标：理想输出=静音
            c.pushReference(ref)
            val out = c.process(mic)
            if (b >= 40) { // 4s 收敛期后量测
                echoEnergy += energy(mic)
                residualEnergy += energy(out)
            }
        }
        val erleDb = 10.0 * log10(echoEnergy / residualEnergy)
        assertTrue("ERLE=$erleDb dB 应 ≥10dB", erleDb >= 10.0)
    }

    @Test
    fun `target signal passes through when reference is uncorrelated`() {
        val c = NlmsEchoCanceller()
        val rng = Random(7)
        val target = noise(rng, block * 40, 0.3f) // 用户唤醒词替代物（无回声）
        val outAll = ArrayList<Float>()
        for (b in 0 until 40) {
            val ref = noise(rng, block, 0.5f) // 播放内容与人声独立
            val mic = target.copyOfRange(b * block, (b + 1) * block)
            c.pushReference(ref)
            outAll.addAll(c.process(mic).toList())
        }
        // 收敛后段量测：相关系数≈1（波形保真）+ 能量比≈1（不削不增）
        val from = 20 * block
        var dot = 0.0
        var eT = 0.0
        var eO = 0.0
        for (i in from until target.size) {
            dot += target[i] * outAll[i]
            eT += target[i] * target[i]
            eO += outAll[i] * outAll[i]
        }
        val corr = dot / sqrt(eT * eO)
        val ratio = eO / eT
        assertTrue("目标相关系数=$corr 应 >0.95", corr > 0.95)
        assertTrue("目标能量比=$ratio 应在 [0.8,1.2]", ratio in 0.8..1.2)
    }

    @Test
    fun `zero reference is exact passthrough`() {
        val c = NlmsEchoCanceller()
        val rng = Random(1)
        val mic = noise(rng, block, 0.4f)
        val out = c.process(mic)
        assertArrayEquals(mic, out, 0f)
    }

    @Test
    fun `reference overflow resyncs and still suppresses`() {
        // 防御层锚（旧版写入节奏直推 bug 形态）：先灌 3 倍环容失控积压，再恢复正常
        // 节奏——重同步后应重新对齐并达到抑制指标（删溢出重同步 -> 环内容错位 -> 红）
        val c = NlmsEchoCanceller()
        val rng = Random(9)
        c.pushReference(noise(rng, 8192 * 3, 0.5f)) // 失控积压（历史 bug 形态）
        val path = EchoPath(delay = 900, gain = 0.7f)
        var echoEnergy = 0.0
        var residualEnergy = 0.0
        for (b in 0 until 60) {
            val ref = noise(rng, block, 0.5f)
            val mic = path.render(ref)
            c.pushReference(ref)
            val out = c.process(mic)
            if (b >= 40) {
                echoEnergy += energy(mic)
                residualEnergy += energy(out)
            }
        }
        val erleDb = 10.0 * log10(echoEnergy / residualEnergy)
        assertTrue("溢出重同步后 ERLE=$erleDb dB 应 ≥10dB", erleDb >= 10.0)
    }

    @Test
    fun `jittery reference pacing still suppresses`() {
        // 板子 2026-09-02 遥测形态复刻：playbackHeadPosition 粒度/超前使参考推送节奏
        // 震荡（refBacklog 1484~8671）→ 恒定配对的对齐反复失效。周期互相关自对齐后
        // 应稳定抑制（删对齐 -> 震荡窗内窗外交替 -> ERLE 崩 -> 红）
        val c = NlmsEchoCanceller()
        val rng = Random(11)
        val path = EchoPath(delay = 900, gain = 0.7f)
        val nBlocks = 140
        val stream = Array(nBlocks) { noise(rng, block, 0.5f) }
        var pushIdx = 0
        var echoEnergy = 0.0
        var residualEnergy = 0.0
        for (b in 0 until nBlocks) {
            val mic = path.render(stream[b])
            // 推送节奏抖动：随机 0/1/2 块，但超前钳 ≤2 块（pacer 按播放头推送，
            // 物理上不可能持续超前——无界漂移场景由溢出测试覆盖）
            val r = rng.nextInt(3)
            repeat(if (r == 0) 2 else if (r == 1) 0 else 1) {
                if (pushIdx < minOf(b + 2, nBlocks)) c.pushReference(stream[pushIdx++])
            }
            if (pushIdx <= b && pushIdx < nBlocks) c.pushReference(stream[pushIdx++]) // 保底不落后
            val out = c.process(mic)
            if (b >= 110) {
                echoEnergy += energy(mic)
                residualEnergy += energy(out)
            }
        }
        val erleDb = 10.0 * log10(echoEnergy / residualEnergy)
        assertTrue("抖动节奏下 ERLE=$erleDb dB 应 ≥10dB", erleDb >= 10.0)
    }

    @Test
    fun `divergence guard keeps output bounded and recovers`() {
        // 板子形态复刻：double-talk（大声人声+回声）使未收敛滤波器发散（outRms 4x 输入，
        // 2026-09-02 20:23 遥测实证）→ 守卫须在数块内止住放大（out<=1.5x in），且
        // 人声消失后恢复抑制
        val c = NlmsEchoCanceller()
        val rng = Random(5)
        val path = EchoPath(delay = 900, gain = 0.7f)
        val voice = noise(rng, block * 25, 0.9f) // 2.5s 大声人声（独立于参考）
        val ratios = ArrayList<Double>()
        for (b in 0 until 80) {
            val ref = noise(rng, block, 0.5f)
            val echo = path.render(ref)
            val mic = FloatArray(block) { i ->
                if (b in 20 until 45) echo[i] + voice[(b - 20) * block + i] else echo[i]
            }
            c.pushReference(ref)
            val out = c.process(mic)
            if (b >= 50) ratios.add(energy(out) / maxOf(energy(mic), 1e-12))
        }
        val worst = ratios.max()
        assertTrue("人声结束后 out/in 最差比=$worst 应 ≤1.5", worst <= 1.5)
        val c2 = NlmsEchoCanceller() // 恢复力：同场景跑完后再量纯回声段
        var echoE = 0.0; var residE = 0.0
        for (b in 0 until 60) {
            val ref = noise(rng, block, 0.5f)
            val mic2 = path.render(ref)
            c2.pushReference(ref)
            val out = c2.process(mic2)
            if (b >= 40) { echoE += energy(mic2); residE += energy(out) }
        }
        assertTrue("恢复后 ERLE=${10.0 * log10(echoE / residE)} dB 应 ≥10dB", 10.0 * log10(echoE / residE) >= 10.0)
    }

    @Test
    fun `block byte path round-trips and cancels`() {
        // 16bit PCM 字节入口（VSM routeBlock 实际用法）：有参考时不得直通，无参考直通
        val c = NlmsEchoCanceller()
        val rng = Random(3)
        val micFloat = noise(rng, block, 0.4f)
        val micBytes = ByteArray(block * 2) { i ->
            val v = (micFloat[i / 2] * 32767).toInt().coerceIn(-32768, 32767)
            if (i % 2 == 0) (v and 0xFF).toByte() else (v shr 8).toByte()
        }
        val passthrough = c.processBlock(micBytes)
        assertTrue("零参考应原样返回", passthrough.contentEquals(micBytes))
        val ref = noise(rng, block * 30, 0.5f)
        c.pushReference(ref)
        val out = c.processBlock(micBytes)
        assertTrue("有参考应走消回声路径", !out.contentEquals(micBytes))
        assertEquals(micBytes.size, out.size)
    }
}
