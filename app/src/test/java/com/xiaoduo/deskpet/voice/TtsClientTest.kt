package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.voice.audio.WavCodec
import com.xiaoduo.deskpet.voice.client.TtsClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import com.xiaoduo.deskpet.api.TtsContract
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * TtsClient 单测（MockWebServer JVM 端对端桩）。
 * 覆盖：NDJSON 多帧 WAV 剥头拼 PCM（坑 #7）/ 请求体契约（text 数组、cosyvoice3、default、1.0）/
 * 预校验 JSON 信封错误路径 / 流式 404 永久切非流式 / 流式中途错非流式兜底一次 / 无 done 帧显式报错。
 */
class TtsClientTest {

    private lateinit var server: MockWebServer
    private lateinit var logs: MutableList<String>

    private class MemStreamSupport : TtsClient.StreamSupport {
        var unsupported = false
        override fun isUnsupported(): Boolean = unsupported
        override fun markUnsupported() {
            unsupported = true
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        logs = mutableListOf()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(flag: TtsClient.StreamSupport) = TtsClient(
        http = OkHttpClient(),
        streamUrl = server.url("/audio/tts/stream").toString(),
        synthesizeUrl = server.url("/audio/tts/synthesize").toString(),
        streamSupport = flag,
        logger = { logs.add(it) },
    )

    private fun wavFrame(pcm: ByteArray): String =
        Base64.getEncoder().encodeToString(WavCodec.pcmToWav(pcm, RevealScheduler.TTS_SAMPLE_RATE))

    private fun ndjson(vararg lines: String) = MockResponse()
        .setHeader("Content-Type", "application/x-ndjson")
        .setBody(lines.joinToString("\n") + "\n")

    private fun synthOk(pcm: ByteArray) = MockResponse().setBody(
        """{"code":200,"msg":"ok","data":{"audioData":"${wavFrame(pcm)}"}}""",
    )

    @Test
    fun `NDJSON 两帧 WAV 剥头拼 PCM 无夹头`() {
        val pcmA = ByteArray(480) { it.toByte() }
        val pcmB = ByteArray(240) { (it + 7).toByte() }
        server.enqueue(
            ndjson(
                """{"audioData":"${wavFrame(pcmA)}","format":"wav"}""",
                """{"audioData":"${wavFrame(pcmB)}","format":"wav"}""",
                """{"type":"done"}""",
            ),
        )

        val r = client(MemStreamSupport()).synthesize("你好世界。")

        assertTrue(r is TtsContract.Result.Audio)
        assertArrayEquals(pcmA + pcmB, (r as TtsContract.Result.Audio).pcm)
        // 请求体契约：text 数组 / cosyvoice3 / default / 1.0
        val recorded = server.takeRequest()
        assertEquals("/audio/tts/stream", recorded.path)
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("你好世界。", body.getJSONArray("text").getString(0))
        assertEquals(1, body.getJSONArray("text").length())
        assertEquals("cosyvoice3", body.getString("serviceName"))
        assertEquals("default", body.getString("speaker"))
        assertEquals(1.0, body.getDouble("speed"), 0.0)
    }

    @Test
    fun `预校验错误走普通 JSON 信封且 msg 透传`() {
        // 流式端点预校验错误 = 普通 JSON 信封（不进 NDJSON）；非流式兜底同样失败
        server.enqueue(
            MockResponse().setBody("""{"code":500,"msg":"no pronounceable characters"}"""),
        )
        server.enqueue(
            MockResponse().setBody("""{"code":500,"msg":"no pronounceable characters"}"""),
        )

        val r = client(MemStreamSupport()).synthesize("---")

        assertTrue(r is TtsContract.Result.Error)
        assertEquals(
            "TTS 失败: no pronounceable characters",
            (r as TtsContract.Result.Error).message,
        )
        assertTrue(logs.any { it.contains("TTS 预校验失败: no pronounceable characters") })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `流式 404 永久记住走非流式且 text 传字符串`() {
        val flag = MemStreamSupport()
        val pcm = ByteArray(480) { (it * 3).toByte() }
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(synthOk(pcm))

        val r = client(flag).synthesize("第一句。")

        assertTrue(r is TtsContract.Result.Audio)
        assertArrayEquals(pcm, (r as TtsContract.Result.Audio).pcm)
        assertTrue(flag.unsupported)
        assertEquals(2, server.requestCount)
        assertEquals("/audio/tts/stream", server.takeRequest().path)
        val fallback = server.takeRequest()
        assertEquals("/audio/tts/synthesize", fallback.path)
        val body = JSONObject(fallback.body.readUtf8())
        assertEquals("第一句。", body.getString("text")) // 非流式 text 传字符串

        // 永久记忆：第二次直接走非流式，不再碰 stream
        server.enqueue(synthOk(pcm))
        val r2 = client(flag).synthesize("第二句。")
        assertTrue(r2 is TtsContract.Result.Audio)
        assertEquals(3, server.requestCount)
        assertEquals("/audio/tts/synthesize", server.takeRequest().path)
    }

    @Test
    fun `流式中途 error 帧非流式兜底一次`() {
        val pcm = ByteArray(960) { (it + 1).toByte() }
        server.enqueue(ndjson("""{"type":"error","code":500,"msg":"gpu busy"}"""))
        server.enqueue(synthOk(pcm))

        val r = client(MemStreamSupport()).synthesize("兜底验证。")

        assertTrue(r is TtsContract.Result.Audio)
        assertArrayEquals(pcm, (r as TtsContract.Result.Audio).pcm)
        assertEquals(2, server.requestCount)
        assertTrue(logs.any { it.contains("gpu busy") })
    }

    @Test
    fun `无 done 帧干净断流显式报错不伪装成功`() {
        val pcmA = ByteArray(480) { it.toByte() }
        server.enqueue(ndjson("""{"audioData":"${wavFrame(pcmA)}","format":"wav"}"""))
        // 非流式兜底也失败 → 最终 Error，且断流错误进日志
        server.enqueue(MockResponse().setBody("""{"code":500,"msg":"synthesize disabled"}"""))

        val r = client(MemStreamSupport()).synthesize("断流句。")

        assertTrue(r is TtsContract.Result.Error)
        assertTrue(logs.any { it.contains("未见 done 帧") })
        assertTrue((r as TtsContract.Result.Error).message.contains("synthesize disabled"))
    }
}
