package com.xiaoduo.deskpet.voice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * WavCodec 解析越界批（spec §5.3 WAV 解析族 audio 侧 / ocr[41]，2026-10-10 P3-T1）：
 *
 * [41] 越界检查 `off + 8 + size <= wav.size` 可 Int 溢出：恶意/损坏 chunk size=0x7FFFFFFF
 * 时 off+8+size 回绕成负值照过 require——非 data chunk 的 `off += 8+size+(size and 1)`
 * 再回绕成负 → 下一轮 `asciiAt(负下标)` 抛 IndexOutOfBoundsException，破坏「损坏输入一律
 * IllegalArgumentException（调用方转用户可读 msg）」的文档契约；data chunk 同尺寸则
 * copyOfRange 收到 from>to 抛误导性英文 IAE。修法：换无溢出形式 `size <= wav.size-off-8`
 * （循环守卫 `off+8<=wav.size` 已保证右式 ≥0）。
 *
 * 变异锚：检查回退 `off + 8 + size <= wav.size`（溢出形式）→ 本测试红。
 * 注：同型族 cloud 侧（AliyunAsrClient/VolcengineAsrClient）不在本票地界，上报清单另列。
 */
class WavCodecBoundsTest {

    private fun putAscii(b: ByteArray, off: Int, s: String) {
        for (i in s.indices) b[off + i] = s[i].code.toByte()
    }

    private fun putLeInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = (v shr 8 and 0xFF).toByte()
        b[off + 2] = (v shr 16 and 0xFF).toByte()
        b[off + 3] = (v shr 24 and 0xFF).toByte()
    }

    @Test
    fun `hostile chunk size near int max is rejected as illegal argument not index out of bounds`() {
        // 变异锚：require 回退 off+8+size 溢出形式 → require 误放行 → off 回绕负值 →
        // asciiAt 抛 IndexOutOfBoundsException → fail 路径红。
        val wav = ByteArray(64)
        putAscii(wav, 0, "RIFF")
        putLeInt(wav, 4, 52)
        putAscii(wav, 8, "WAVE")
        putAscii(wav, 12, "JUNK")
        putLeInt(wav, 16, Int.MAX_VALUE) // 0x7FFFFFFF：off+8+size 溢出回绕成负
        try {
            WavCodec.stripWavHeader(wav)
            fail("越界尺寸必须被拒（IllegalArgumentException），放行即溢出漏洞")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "须是文档契约的越界 msg，实际：${e.message}",
                e.message!!.contains("越界"),
            )
        }
    }

    @Test
    fun `data chunk with int max size is rejected before copy`() {
        // 同型：data chunk 带越界尺寸——修复后在 copyOfRange 前拒绝（旧行为会尝试 ~2GB
        // 分配/OOM 或抛误导英文 IAE）。
        val wav = ByteArray(64)
        putAscii(wav, 0, "RIFF")
        putLeInt(wav, 4, 52)
        putAscii(wav, 8, "WAVE")
        putAscii(wav, 12, "data")
        putLeInt(wav, 16, Int.MAX_VALUE)
        try {
            WavCodec.stripWavHeader(wav)
            fail("data 越界尺寸必须在 copy 前被拒")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("越界"))
        }
    }

    @Test
    fun `negative chunk size stays rejected`() {
        // 行为保持锚：负值检查不受重构影响。
        val wav = ByteArray(64)
        putAscii(wav, 0, "RIFF")
        putLeInt(wav, 4, 52)
        putAscii(wav, 8, "WAVE")
        putAscii(wav, 12, "JUNK")
        putLeInt(wav, 16, -1)
        try {
            WavCodec.stripWavHeader(wav)
            fail("负尺寸必须被拒")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("越界"))
        }
    }

    @Test
    fun `roundtrip and extra chunk walking unaffected by bounds refactor`() {
        // 行为保持锚：正常路径（含 fmt 扩展 chunk 容忍 + data 剥头）与重构前逐字节一致。
        val pcm = ByteArray(100) { (it and 0xFF).toByte() }
        val wav = WavCodec.pcmToWav(pcm, 16_000)
        assertEquals(pcm.toList(), WavCodec.stripWavHeader(wav).toList())

        // 44 字节标准头 + 附加 JUNK chunk（10B 含 pad）+ data
        val extended = ByteArray(44 + 12 + pcm.size)
        putAscii(extended, 0, "RIFF")
        putLeInt(extended, 4, extended.size - 8)
        putAscii(extended, 8, "WAVE")
        putAscii(extended, 12, "fmt ")
        putLeInt(extended, 16, 16)
        putAscii(extended, 36, "JUNK")
        putLeInt(extended, 40, 3) // 奇数尺寸：走 (size and 1) 偶对齐跳过
        putAscii(extended, 48, "data")
        putLeInt(extended, 52, pcm.size)
        pcm.copyInto(extended, 56)
        assertEquals(pcm.toList(), WavCodec.stripWavHeader(extended).toList())
    }
}
