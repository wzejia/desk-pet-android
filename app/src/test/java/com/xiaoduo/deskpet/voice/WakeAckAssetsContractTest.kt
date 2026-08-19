package com.xiaoduo.deskpet.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 唤醒应答音资产契约测试（S9-1，范式同 PetFrameTableContractTest：JVM 测试 cwd = app 模块，
 * 直接读 src/main/assets 真实出厂文件；纯 java.io，零 Android 依赖）。
 *
 * 后处理口径单一真理源 = tools/wozai/common.py（Python 侧）与 wozai.wav 现行管线：
 * 去头尾静音（头 3 窗呼吸）+ 峰值归一 26000 + 44B RIFF 24000Hz/mono/16bit。
 * 任一资产漂移（重生成时漏 trim / 漏归一 / 容器参数错）即红。
 *
 * 新增资产（key/端点到位补跑）= REQUIRED_ASSETS 加一行；生成命令见 tools/wozai/README.md。
 */
class WakeAckAssetsContractTest {

    /** 必备资产集：主平台资产 + 已落盘的云厂商默认音色资产。暂缺项与原因见 README「历史与偏差」。 */
    private val requiredAssets = listOf(
        "wozai.wav",
        "wozai-aliyun.wav",
        // "wozai-volcengine.wav", // VOLC_SPEECH_KEY 为空暂缺（R2），key 到位补跑后取消注释
        // "wozai-openai.wav",     // dashscope /audio/speech 404 暂缺（R3），端点可用后取消注释
    )

    private val assetsDir = File("src/main/assets")

    private fun fileOf(name: String): File {
        val f = File(assetsDir, name)
        assertTrue("资产未进 assets：$f", f.exists())
        return f
    }

    private fun bytesOf(name: String): ByteArray = fileOf(name).readBytes()

    private fun pcmOf(name: String): ShortArray {
        val bytes = bytesOf(name)
        val bb = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray((bytes.size - 44) / 2).also { bb.asShortBuffer().get(it) }
    }

    private fun peakOf(pcm: ShortArray): Int = pcm.maxOf { kotlin.math.abs(it.toInt()) }

    @Test
    fun `wake ack asset set matches required list`() {
        val actual = assetsDir.listFiles { f -> f.name.matches(Regex("wozai.*\\.wav")) }
            ?.map { it.name }?.sorted()
            ?: emptyList()
        assertEquals(
            "wake 应答资产集漂移（新增资产需同步 REQUIRED_ASSETS）",
            requiredAssets.sorted(), actual
        )
    }

    @Test
    fun `every asset is standard 44 byte RIFF pcm mono 16bit 24000hz`() {
        for (name in requiredAssets) {
            val b = bytesOf(name)
            assertTrue("$name 非 RIFF", String(b, 0, 4, Charsets.US_ASCII) == "RIFF")
            assertTrue("$name 非 WAVE", String(b, 8, 4, Charsets.US_ASCII) == "WAVE")
            assertTrue("$name fmt chunk 异常", String(b, 12, 4, Charsets.US_ASCII) == "fmt ")
            val fmt = ByteBuffer.wrap(b, 16, 20).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("$name fmt 段长非 16", 16, fmt.int)
            assertEquals("$name 非 PCM(1)", 1, fmt.short.toInt())
            assertEquals("$name 非 mono", 1, fmt.short.toInt())
            assertEquals("$name 采样率非 24000", 24000, fmt.int)
            assertEquals("$name 字节率非 48000", 48000, fmt.int)
            assertEquals("$name 块对齐非 2", 2, fmt.short.toInt())
            assertEquals("$name 位深非 16", 16, fmt.short.toInt())
            assertEquals("$name data chunk 异常", "data", String(b, 36, 4, Charsets.US_ASCII))
            val riff = ByteBuffer.wrap(b, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val dataSize = ByteBuffer.wrap(b, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
            assertEquals("$name RIFF size 与文件长不符", b.size - 8, riff)
            assertEquals("$name data size 与文件长不符（须恰 44B 头）", b.size - 44, dataSize)
            assertEquals("$name PCM 字节长为奇数", 0, dataSize % 2)
        }
    }

    @Test
    fun `every asset duration within 0_2 to 3_0 seconds`() {
        for (name in requiredAssets) {
            val b = bytesOf(name)
            val dataSize = ByteBuffer.wrap(b, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val seconds = dataSize / 48000.0
            assertTrue(
                "$name 时长 ${"%.2f".format(seconds)}s 超出 [0.2s,3.0s]（data=${dataSize}B）",
                dataSize in 9600..144000
            )
        }
    }

    @Test
    fun `every asset peak normalized to 26000 plus minus 2`() {
        for (name in requiredAssets) {
            val peak = peakOf(pcmOf(name))
            assertTrue("$name 峰值 $peak 偏离 26000±2（漏归一/重合成未过管线）", peak in 25998..26002)
        }
    }

    @Test
    fun `every asset head has signal within 50ms`() {
        for (name in requiredAssets) {
            val pcm = pcmOf(name)
            val headMax = pcm.take(1200).maxOf { kotlin.math.abs(it.toInt()) } // 50ms = 1200 采样
            assertTrue(
                "$name 头部 50ms 内无信号（最大绝对值 $headMax ≤500，疑漏去头静音）",
                headMax > 500
            )
        }
    }
}
