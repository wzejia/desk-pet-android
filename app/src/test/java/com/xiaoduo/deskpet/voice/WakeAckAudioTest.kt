package com.xiaoduo.deskpet.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WakeAckAudio 单测（S9-2）：Python parity 逐参数锚（remake_wozai.py → tools/wozai/common.py）
 * + Python↔Kotlin 黄金向量逐字节对拍（S9-1 夹具 app/src/test/resources/wakeack/parity-*.wav，
 * 由 tools/wozai/common.py --emit-parity-fixture 产出）。
 *
 * 金向量与半偶单测互补：夹具输入无 .5 平局样本，rint→half-up 变异时金向量仍绿、
 * 半偶锚用例红；实现/参数漂移（win/阈/呼吸窗/归一目标）时金向量红——两道都须在。
 */
class WakeAckAudioTest {

    /** 按窗幅值铺样本：windowsOf(0, 5200, 0) = 窗0静音 + 窗1幅5200 + 窗2静音（每窗 240 样本）。 */
    private fun windowsOf(vararg amps: Int, win: Int = 240): ShortArray {
        val out = ShortArray(amps.size * win)
        for (w in amps.indices) {
            for (i in 0 until win) out[w * win + i] = amps[w].toShort()
        }
        return out
    }

    private fun peakAbs(samples: ShortArray): Int {
        var m = 0
        for (x in samples) {
            val a = kotlin.math.abs(x.toInt())
            if (a > m) m = a
        }
        return m
    }

    // ---- trimEdges：头 30ms（3 窗）呼吸 ----

    @Test
    fun `trim keeps 3-window head breath and drops earlier silence sample-wise`() {
        // 25 窗：10 静音 + 5 信号(5200) + 10 静音 → first=10 → s=(10-3)*240=1680
        val input = windowsOf(*IntArray(10) { 0 }, 5200, 5200, 5200, 5200, 5200, *IntArray(10) { 0 })
        val out = WakeAckAudio.trimEdges(input)
        assertEquals(2640, out.size)
        // 逐样本断言：输出恰为输入切片 [1680, 4320)（e=min(6000,(14+4)*240)=4320）
        assertArrayEquals(input.copyOfRange(1680, 4320), out)
        // 头 3 窗（720 样本）呼吸静音原样保留，之后进信号窗
        assertEquals(0, out[0].toInt())
        assertEquals(0, out[719].toInt())
        assertEquals(5200, out[720].toInt())
    }

    @Test
    fun `trim keeps 4-window tail breath and drops later silence sample-wise`() {
        // 同构造：last=14 → e=(14+4)*240=4320，尾静音窗 18-24 全裁
        val input = windowsOf(*IntArray(10) { 0 }, 5200, 5200, 5200, 5200, 5200, *IntArray(10) { 0 })
        val out = WakeAckAudio.trimEdges(input)
        // 末样本落在尾呼吸窗 17（静音）；信号末窗 14 样本保留（相对位移 3360-1680=1680），
        // 之后窗 15 起才是尾呼吸静音（相对 1920 = 全局 3600）
        assertEquals(5200, out[1679].toInt())
        assertEquals(5200, out[1680].toInt())
        assertEquals(0, out[1920].toInt())
        assertEquals(0, out[out.size - 1].toInt())
        // 被裁掉的窗 18 起静音不出现：总长恰到窗 17 末（4320）
        assertEquals(4320 - 1680, out.size)
    }

    @Test
    fun `trim head clamps to zero when signal starts within first 3 windows`() {
        // first=1 → (1-3)*240 < 0 → s=0（顶格下钳 0）
        val input = windowsOf(0, 9000, 9000, 9000, 0, 0, 0, 0, 0, 0)
        val out = WakeAckAudio.trimEdges(input)
        assertEquals(0, out[0].toInt()) // 窗 0（静音）因下钳保留在头部
        assertEquals(7 * 240, out.size) // last=3 → e=(3+4)*240=1680
        assertArrayEquals(input.copyOfRange(0, 1680), out)
    }

    @Test
    fun `trim tail clamps to len when signal sits in the last window`() {
        // 40 窗静音 + 信号在末窗 39 → last=39 → e=min(9600,(39+4)*240=10320)=9600（上钳 len）
        val amps = IntArray(40) { 0 }
        amps[39] = 8000
        val input = windowsOf(*amps)
        val out = WakeAckAudio.trimEdges(input)
        assertEquals(9600 - 8640, out.size) // s=(39-3)*240=8640
        assertArrayEquals(input.copyOfRange(8640, 9600), out)
        // 末样本 = 输入末样本（e 上钳证据；删 min 则 copyOfRange 越界抛异常 → 红）
        assertEquals(8000, out[out.size - 1].toInt())
    }

    @Test
    fun `threshold floor 60 applies when 2 percent of peak is lower`() {
        // S9-2 回归守卫：阈底 60（peak*0.02=40 < 60 → 幅 50 的低响窗按静音裁）
        //（删 max(peak*0.02, 60.0) 的 60 底 → th=40 → first=0 → 头裁断言 → 红）
        // 窗 0-5 幅 50（低响），窗 6-8 幅 2000（信号），窗 9-11 幅 50
        val input = windowsOf(50, 50, 50, 50, 50, 50, 2000, 2000, 2000, 50, 50, 50)
        val out = WakeAckAudio.trimEdges(input)
        // th=60 → first=6 → s=(6-3)*240=720（低响窗 0-2 被裁）；last=8 → e=(8+4)*240=2880=全长
        assertEquals(2880 - 720, out.size)
        assertArrayEquals(input.copyOfRange(720, 2880), out)
    }

    @Test
    fun `all-silence input passes trim unchanged`() {
        // Python 口径：无窗过阈 → first=0 / last=len(env)-1 → 原样通过（由 validate 拦）
        val input = windowsOf(*IntArray(25) { 0 })
        val out = WakeAckAudio.trimEdges(input)
        assertEquals(input.size, out.size)
        assertArrayEquals(input, out)
    }

    @Test
    fun `last partial window participates in envelope`() {
        // len=1000 = 4 整窗 + 40 样本残窗；信号只放残窗 → env[4]>th → last=4 → e=min(1000,1920)=1000
        val input = ShortArray(1000)
        for (i in 960 until 1000) input[i] = 3000
        val out = WakeAckAudio.trimEdges(input)
        // first=4 → s=(4-3)*240=240；输出含残窗到末尾
        assertEquals(760, out.size)
        assertArrayEquals(input.copyOfRange(240, 1000), out)
    }

    // ---- normalizePeak ----

    @Test
    fun `normalize maps peak to exactly 26000 with integer gain`() {
        val out = WakeAckAudio.normalizePeak(shortArrayOf(13000, 6500, -6500, 0))
        assertArrayEquals(shortArrayOf(26000, 13000, -13000, 0), out)
        assertEquals(26000, peakAbs(out))
    }

    @Test
    fun `normalize rounds half to even matching python round`() {
        // S9-2 回归守卫：半偶舍入（peak=10400 → g=2.5 精确；x*g=2.5/7.5/12.5 恰落 .5）
        //（Math.rint 改 half-up 的 roundToInt/Math.round → 2.5→3、12.5→13 → 断言 → 红）
        val out = WakeAckAudio.normalizePeak(shortArrayOf(10400, 1, 3, 5))
        assertEquals(26000, out[0].toInt()) // 10400*2.5=26000
        assertEquals(2, out[1].toInt())     // 1*2.5=2.5 → 半偶向偶 2（half-up 会给 3）
        assertEquals(8, out[2].toInt())     // 3*2.5=7.5 → 8（两侧同为偶）
        assertEquals(12, out[3].toInt())    // 5*2.5=12.5 → 半偶向偶 12（half-up 会给 13）
    }

    @Test
    fun `normalize with huge gain stays within int16 range`() {
        // peak=1 → g=26000：极端大增益下输出域仍界内（26000 ≤ 32767）
        //（|x·g|≤target 恒成立使 clamp 分支数学不可达，此处锁大增益行为与输出域）
        assertArrayEquals(shortArrayOf(26000), WakeAckAudio.normalizePeak(shortArrayOf(1)))
        assertArrayEquals(
            shortArrayOf(26000, 13000, -26000, -13000),
            WakeAckAudio.normalizePeak(shortArrayOf(2, 1, -2, -1)),
        )
    }

    @Test
    fun `normalize zero-peak input passes through`() {
        val input = ShortArray(6000)
        val out = WakeAckAudio.normalizePeak(input)
        assertEquals(input.size, out.size)
        assertEquals(0, peakAbs(out))
    }

    // ---- validate ----

    @Test
    fun `validate accepts length bounds and minimum peak`() {
        // 下界 9600B=4800 样本 + 峰恰 1000（含等值）
        assertTrue(WakeAckAudio.validate(WakeAckAudio.toBytes(ShortArray(4800) { 1000 })))
        // 上界 144000B=72000 样本
        assertTrue(WakeAckAudio.validate(WakeAckAudio.toBytes(ShortArray(72000) { 26000 })))
        // 典型：0.5s 峰 26000
        assertTrue(WakeAckAudio.validate(WakeAckAudio.toBytes(ShortArray(12000) { 26000 })))
    }

    @Test
    fun `validate rejects odd byte length`() {
        val bytes = WakeAckAudio.toBytes(ShortArray(4800) { 26000 })
        val odd = bytes + byteArrayOf(0)
        assertEquals(9601, odd.size)
        assertFalse(WakeAckAudio.validate(odd))
    }

    @Test
    fun `validate rejects lengths outside window`() {
        // 9598B（0.5 样本短于下界）
        assertFalse(WakeAckAudio.validate(WakeAckAudio.toBytes(ShortArray(4799) { 26000 })))
        // 144002B（恰越上界）
        val over = WakeAckAudio.toBytes(ShortArray(72000) { 26000 }) + byteArrayOf(0, 0)
        assertEquals(144002, over.size)
        assertFalse(WakeAckAudio.validate(over))
    }

    @Test
    fun `validate rejects low peak and all-zero pcm`() {
        // 峰 999（恰低于门槛）长度合法
        assertFalse(WakeAckAudio.validate(WakeAckAudio.toBytes(ShortArray(4800) { 999 })))
        // 全零（trim 放行的全静音由此拦）
        assertFalse(WakeAckAudio.validate(WakeAckAudio.toBytes(ShortArray(4800))))
    }

    // ---- process / 字节转换 ----

    @Test
    fun `process end to end trims then normalizes`() {
        // 25 窗：10 静 + 5×5200 + 10 静 → trim [1680,4320) 长 2640 → g=26000/5200=5
        val input = windowsOf(*IntArray(10) { 0 }, 5200, 5200, 5200, 5200, 5200, *IntArray(10) { 0 })
        val out = WakeAckAudio.process(input)
        assertEquals(2640, out.size)
        assertEquals(26000, peakAbs(out))
        assertEquals(0, out[0].toInt())          // 头呼吸窗静音 ×5 仍为 0
        assertEquals(26000, out[720].toInt())    // 首个信号样本 5200×5
        assertEquals(0, out[out.size - 1].toInt()) // 尾呼吸窗静音
    }

    @Test
    fun `little endian byte roundtrip preserves signed samples`() {
        val samples = shortArrayOf(0, 1, -1, 32767, -32768, 26000, -26000)
        assertArrayEquals(samples, WakeAckAudio.toSamples(WakeAckAudio.toBytes(samples)))
        // 0xFF80 = -128 小端
        assertArrayEquals(shortArrayOf(-128), WakeAckAudio.toSamples(byteArrayOf(0x80.toByte(), 0xFF.toByte())))
    }

    // ---- Python↔Kotlin 黄金向量对拍（总变异锚）----

    /** 剥 44B RIFF 头读小端样本（习气同 WakeAckAssetsContractTest.pcmOf）。 */
    private fun littleEndianSamplesOf(wav: ByteArray): ShortArray {
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        val bb = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray((wav.size - 44) / 2).also { bb.asShortBuffer().get(it) }
    }

    @Test
    fun `process matches python golden vector byte for byte`() {
        // S9-2 回归守卫（总变异锚）：Kotlin process ≡ Python normalize(trim_edges()) 逐字节
        //（任一后处理参数漂移（win/阈底/呼吸窗/归一目标/舍入/钳位）→ 本用例 → 红）
        val dir = File("src/test/resources/wakeack")
        val input = littleEndianSamplesOf(File(dir, "parity-input.wav").readBytes())
        val expected = littleEndianSamplesOf(File(dir, "parity-expected.wav").readBytes())
        val out = WakeAckAudio.process(input)
        assertEquals("输出样本数与 Python 不符（trim 参数漂移）", expected.size, out.size)
        for (i in expected.indices) {
            assertEquals("样本 #$i 漂移（expected=${expected[i]} actual=${out[i]}）", expected[i], out[i])
        }
    }
}
