package com.xiaoduo.deskpet.cloud

import com.xiaoduo.deskpet.voice.TtsClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ROB-14：云客户端 Result 契约异常边界（家族）。用户可配畸形 baseUrl / 畸形响应 /
 * Base64 IAE / OkHttp ISE 等RuntimeException 必须映射为 Result.Error 返回调用方，
 * 不得击穿契约崩溃调用方（VSM 管线 / 设置页）。
 * 同型镜像（OpenAITtsClient/VolcengineTtsClient）一并锁定。
 */
class CloudClientsExceptionBoundaryTest {

    /** 恶意/畸形 baseUrl：Request.Builder.url 直接抛 IllegalArgumentException。 */
    private val badBase = "http://192.0.2.1:99999/bad base\\path" // 非法端口+空格

    private fun assertErrorResult(result: Any?, tag: String) {
        assertTrue(
            "$tag 必须返回 *Error 而非抛异常，实际=$result",
            result != null && result.javaClass.simpleName == "Error",
        )
    }

    @Test
    fun `ROB-14 OpenAIAsr malformed baseUrl returns Error instead of throwing`() {
        val client = OpenAIAsrClient(okhttp3.OkHttpClient(), badBase, "key", "model")
        val r = client.transcribe(ByteArray(64))
        assertErrorResult(r, "OpenAIAsr")
    }

    @Test
    fun `ROB-14 OpenAIChat malformed baseUrl returns Error instead of throwing`() {
        val client = OpenAIChatClient(okhttp3.OkHttpClient(), badBase, "key", "model")
        val r = client.chat(listOf(Message("user", "hi")), null) { }
        assertErrorResult(r, "OpenAIChat")
    }

    @Test
    fun `ROB-14 OpenAITts malformed baseUrl returns Error instead of throwing`() {
        val client = OpenAITtsClient(okhttp3.OkHttpClient(), badBase, "key", "model", defaultVoice = "v")
        val r = client.synthesize("hi")
        assertErrorResult(r, "OpenAITts")
    }

    @Test
    fun `ROB-14 AliyunAsr malformed wav returns Error instead of throwing`() {
        // stripWavPcm 对负 data-chunk size 会 copyOfRange 越界抛 IAE——必须在边界内转 Error。
        // （该 IAE 发生在任何网络之前，纯 JVM 可确定复现。）
        val client = AliyunAsrClient(okhttp3.OkHttpClient(), apiKey = "key")
        val evil = ByteArray(64).also {
            // 伪 RIFF：size<12 不触发前置 require；在偏移 12 放 "data" + 负 size
            val m = "data".toByteArray(Charsets.US_ASCII)
            System.arraycopy(m, 0, it, 12, 4)
            ByteBuffer.wrap(it, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(-1)
        }
        val r = client.transcribe(evil)
        assertErrorResult(r, "AliyunAsr")
    }

    @Test
    fun `ROB-14 TtsClient malformed urls keep degradation chain and return Error`() {
        // 流式主路径与非流式兜底 URL 均畸形：链内两步都不得抛——流内异常须转 Failure
        // 走兜底一次，兜底异常转 Result.Error（文档化降级链完整，不击穿）。
        val client = TtsClient(
            http = okhttp3.OkHttpClient(),
            streamUrl = badBase,
            synthesizeUrl = badBase,
            streamSupport = object : TtsClient.StreamSupport {
                override fun isUnsupported() = false
                override fun markUnsupported() {}
            },
        )
        val r = client.synthesize("你好世界")
        assertErrorResult(r, "TtsClient")
    }

    @Test
    fun `ROB-14 AliyunAsr stripWavPcm negative size throws IAE (precondition sanity)`() {
        // 反向锚：确认上一用例的恶意 WAV 确实会让 stripWavPcm 抛 IAE（否则该用例测空了）。
        val evil = ByteArray(64).also {
            val m = "data".toByteArray(Charsets.US_ASCII)
            System.arraycopy(m, 0, it, 12, 4)
            ByteBuffer.wrap(it, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(-1)
        }
        val thrown = runCatching { AliyunAsrClient.stripWavPcm(evil) }
        assertTrue("stripWavPcm 应抛 IAE", thrown.exceptionOrNull() is IllegalArgumentException)
    }
}
