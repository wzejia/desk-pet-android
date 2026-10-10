package com.xiaoduo.deskpet.cloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * WAV→PCM 剥取越界同型族（P3-WAV-FAMILY cloud 侧，WavCodec ocr[41] 修复的同型回灌）：
 * `AliyunAsrClient.stripWavPcm` / `VolcengineAsrClient.stripWavPcm`（镜像双胞胎）。
 *
 * 损坏输入契约（与 WavCodec require / ROB-14 一致）：一律 IllegalArgumentException
 * （调用方 catch 后映射 Result.Error），**不得** IndexOutOfBoundsException（负下标）
 * 或 copyOfRange from>to 的语义错 IAE。溢出路径修复面：巨量 data size 回绕钳位、
 * 负 chunkSize 推进回绕、剩余空间装不下本块（无溢出比较→循环自然退出 44B 回退）。
 *
 * 变异锚：回退旧溢出代码（start+size 裸加 / 负 chunkSize 直推进）→ 对应用例红。
 */
class WavPcmStripBoundsTest {

    private fun littleInt(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    /** RIFF 头(12B) + JUNK 块(size, payload) + data 块(size, payload)。 */
    private fun wavWith(chunkSize: Int, dataHeaderSize: Int, dataPayload: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        out.addAll("RIFF".toByteArray(Charsets.US_ASCII).toList())
        out.addAll(littleInt(4).toList()) // RIFF size（本测试不校验）
        out.addAll("WAVE".toByteArray(Charsets.US_ASCII).toList())
        out.addAll("JUNK".toByteArray(Charsets.US_ASCII).toList())
        out.addAll(littleInt(chunkSize).toList())
        // 巨量声明只补少量截断字节：损坏文件本就是截断态，且不可能真分配 GiB 级 payload
        repeat(minOf(chunkSize, 8).coerceAtLeast(0)) { out.add(0) }
        out.addAll("data".toByteArray(Charsets.US_ASCII).toList())
        out.addAll(littleInt(dataHeaderSize).toList())
        out.addAll(dataPayload.toList())
        return out.toByteArray()
    }

    private fun exercising(f: (ByteArray) -> ByteArray) {
        // 1. data 头 size=0x7FFFFFFF：旧码 start+size 回绕负 → copyOfRange(from>to) 语义错；
        //    修复后无溢出钳位到实际 payload。
        val hostile = wavWith(4, 0x7FFFFFFF, byteArrayOf(1, 2, 3, 4))
        assertArrayEquals("巨量 data size 钳位到实际 payload", byteArrayOf(1, 2, 3, 4), f(hostile))

        // 2. data 头 size 负（高位 1）：契约=IAE（ROB-14：调用方映射 Result.Error）
        val negSize = wavWith(4, -1, byteArrayOf(9, 9))
        try {
            f(negSize)
            fail("负 data size 必须按族契约抛 IAE")
        } catch (expected: IllegalArgumentException) {
            // 期望路径
        }

        // 3. JUNK 块 chunkSize 负：旧码 i 回绕负 → wav[i] IOOBE（意外异常）；契约化 IAE
        val negChunk = wavWith(-16, 4, byteArrayOf(7, 8))
        try {
            f(negChunk)
            fail("负 chunkSize 必须按族契约抛 IAE（不得 IOOBE）")
        } catch (expected: IllegalArgumentException) {
            assertTrue("异常信息应指向 chunk size", expected.message!!.contains("chunk size"))
        }

        // 4. 巨量 JUNK chunkSize（装不下且推进必回绕）：循环自然退出 → 44B 回退，不抛
        val hugeChunk = wavWith(0x7FFFFFF0, 2, byteArrayOf(5, 6))
        val fallback = f(hugeChunk)
        assertEquals(
            "装不下本块走 44B 头回退（回退长度 = size-44 钳 0）",
            (hugeChunk.size - 44).coerceAtLeast(0),
            fallback.size,
        )

        // 5. 正常路径不回归：标准 data 块原样剥出
        val normal = wavWith(4, 2, byteArrayOf(5, 6))
        assertArrayEquals(byteArrayOf(5, 6), f(normal))
    }

    @Test
    fun aliyunStripWavPcm_survivesHostileChunkHeaders() {
        exercising { AliyunAsrClient.stripWavPcm(it) }
    }

    @Test
    fun volcengineStripWavPcm_survivesHostileChunkHeaders() {
        exercising { VolcengineAsrClient.stripWavPcm(it) }
    }
}
