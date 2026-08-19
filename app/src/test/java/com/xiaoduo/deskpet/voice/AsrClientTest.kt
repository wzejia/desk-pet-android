package com.xiaoduo.deskpet.voice

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AsrClient 单测（MockWebServer JVM 端对端桩）。
 * 覆盖：multipart 逐字段契约（file/utterance.wav/audio/wav、language=zh、serviceName=sensevoice）/
 * 信封 code==200 取 data.text strip / 空文本=失败 / code=500 msg 透传。
 */
class AsrClientTest {

    private lateinit var server: MockWebServer
    private lateinit var logs: MutableList<String>

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

    private fun client() = AsrClient(
        http = OkHttpClient(),
        endpoint = server.url("/audio/asr/transcribe").toString(),
        logger = { logs.add(it) },
    )

    @Test
    fun `multipart 字段契约逐字段断言`() {
        server.enqueue(MockResponse().setBody("""{"code":200,"msg":"ok","data":{"text":" 你好世界 "}}"""))

        val r = client().transcribe(ByteArray(VadDetector.MIN_UTTERANCE_BYTES))

        assertTrue(r is AsrClient.Result.Success)
        assertEquals("你好世界", (r as AsrClient.Result.Success).text)
        val recorded = server.takeRequest()
        assertEquals("/audio/asr/transcribe", recorded.path)
        assertEquals("POST", recorded.method)
        val contentType = recorded.getHeader("Content-Type") ?: ""
        assertTrue(contentType.startsWith("multipart/form-data"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("""name="file"; filename="utterance.wav""""))
        assertTrue(body.contains("audio/wav"))
        // language 必须显式 zh（踩坑 #13：auto 把中文误判韩文/英文）
        // （OkHttp 会在字段头后补 Content-Length，故用非贪婪跨段匹配到值）
        assertTrue(body.contains("""name="language""""))
        assertTrue(Regex("""name="language"[\s\S]*?\r\n\r\nzh\r\n""").containsMatchIn(body))
        // serviceName = sensevoice（踩坑 #15：Apipost 旧示例 SenseVoiceASR 是错的）
        assertTrue(Regex("""name="serviceName"[\s\S]*?\r\n\r\nsensevoice\r\n""").containsMatchIn(body))
        // WAV 头进 multipart（16k PCM 包 WAV 头）
        assertTrue(body.contains("RIFF"))
    }

    @Test
    fun `空文本视为失败`() {
        server.enqueue(MockResponse().setBody("""{"code":200,"msg":"ok","data":{"text":"   "}}"""))

        val r = client().transcribe(ByteArray(VadDetector.MIN_UTTERANCE_BYTES))

        assertTrue(r is AsrClient.Result.Error)
        assertTrue((r as AsrClient.Result.Error).message.contains("空文本"))
    }

    @Test
    fun `code 500 透传真实 msg`() {
        server.enqueue(
            MockResponse().setBody(
                """{"code":500,"msg":"serviceName must be sensevoice or FunASR","data":null}""",
            ),
        )

        val r = client().transcribe(ByteArray(VadDetector.MIN_UTTERANCE_BYTES))

        assertTrue(r is AsrClient.Result.Error)
        assertEquals(
            "ASR 失败: serviceName must be sensevoice or FunASR",
            (r as AsrClient.Result.Error).message,
        )
    }

    @Test
    fun `HTTP 错误透传信封 msg`() {
        server.enqueue(
            MockResponse().setResponseCode(500)
                .setBody("""{"code":500,"msg":"engine busy","data":null}"""),
        )

        val r = client().transcribe(ByteArray(VadDetector.MIN_UTTERANCE_BYTES))

        assertTrue(r is AsrClient.Result.Error)
        assertTrue((r as AsrClient.Result.Error).message.contains("engine busy"))
    }
}
