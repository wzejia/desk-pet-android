package com.xiaoduo.deskpet.agui

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * AguiClient 文件上传 + fileId 接入对话（content 前缀注入）单测（MockWebServer JVM 桩）。
 * 契约（CONSTRUCTION S7-f 已关闭取证）：
 * - POST {平台base}/common/file/upload（443 反代 /ai-server/common/file/upload），multipart
 *   字段 file + fileClass（值=agent----{agentId}----{threadId}），Bearer + agentId 头复用 AG-UI；
 *   响应信封 {code:200, data:{fileId,fileName,filePath,fileSize,fileSuffix}}。
 * - 发送消息时 content 前缀 "@file:{fileId}?{fileName}"，多条 "|" 连接 + "\n" + 用户文本；
 *   挂载点在 AguiClient.chat，语音路径不传 fileRefs 不受影响。
 */
class AguiClientUploadTest {

    private lateinit var server: MockWebServer

    private class MemTokenCache : AguiClient.TokenCache {
        var token: String? = null
        override fun cached(): String? = token
        override fun saveFresh(token: String) {
            this.token = token
        }
        override fun clear() {
            token = null
        }
    }

    private class MemThreadStore : AguiClient.ThreadIdStore {
        val map = HashMap<String, String>()
        override fun load(key: String): String? = map[key]
        override fun save(key: String, threadId: String) {
            map[key] = threadId
        }
        override fun remove(key: String) {
            map.remove(key)
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(
        cache: AguiClient.TokenCache,
        threads: AguiClient.ThreadIdStore,
        logs: MutableList<String> = mutableListOf(),
    ) = AguiClient(
        http = OkHttpClient(),
        loginUrl = server.url("/login").toString(),
        streamUrl = server.url("/ai-server/agent/stream").toString(),
        credentials = { "admin" to TEST_PASSWORD },
        tokenCache = cache,
        threadStore = threads,
        logger = { logs.add(it) },
    )

    private fun loginOk(token: String = TOKEN_A) =
        MockResponse().setBody("""{"code":200,"msg":"ok","token":"$token"}""")

    private fun threadOk(id: String = THREAD_ID) =
        MockResponse().setBody("""{"code":200,"msg":"ok","data":"$id"}""")

    private fun uploadOk(fileId: String = FILE_ID) = MockResponse().setBody(
        """{"code":200,"msg":"操作成功","data":{"fileId":"$fileId","fileName":"报告.pdf",
        "filePath":"/upload/$fileId.pdf","fileSize":2048,"fileSuffix":"pdf"}}""",
    )

    private fun takeRequest() = server.takeRequest(2, TimeUnit.SECONDS)
        ?: error("expected a request but none arrived")

    /** 1. 上传成功：解析信封 data，multipart 含 fileClass=agent----{agentId}----{threadId} 与 file 字段。 */
    @Test
    fun uploadSuccessParsesMetaAndSendsContractBody() {
        val logs = mutableListOf<String>()
        val cache = MemTokenCache()
        val threads = MemThreadStore()
        server.enqueue(loginOk())       // login
        server.enqueue(threadOk())      // createThreadId（新会话无缓存 thread）
        server.enqueue(uploadOk())      // upload

        val result = client(cache, threads, logs)
            .uploadFile("报告.pdf", byteArrayOf(1, 2, 3), threadKey = "s1")

        assertTrue(result is AguiClient.UploadResult.Success)
        val meta = (result as AguiClient.UploadResult.Success).meta
        assertEquals(FILE_ID, meta.fileId)
        assertEquals("报告.pdf", meta.fileName)
        assertEquals(2048L, meta.fileSize)
        assertEquals("pdf", meta.fileSuffix)
        assertEquals(UploadStatus.DONE, meta.status)

        takeRequest() // login
        takeRequest() // createThreadId
        val req = takeRequest() // upload
        assertEquals("/ai-server/common/file/upload", req.path)
        assertEquals("Bearer $TOKEN_A", req.getHeader("Authorization"))
        assertEquals("chatAgent", req.getHeader("agentId"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("name=\"fileClass\""))
        assertTrue(body.contains("agent----chatAgent----$THREAD_ID"))
        assertTrue(body.contains("name=\"file\""))
        assertTrue(body.contains("报告.pdf"))
        assertFalse(logs.any { it.contains(TOKEN_A) || it.contains(TEST_PASSWORD) })
    }

    /** 2. 已有缓存 token+thread：上传不再登录/建 thread（请求计数断言），fileClass 用缓存 threadId。 */
    @Test
    fun uploadWithCachedThreadSkipsLoginAndThreadCreation() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        val threads = MemThreadStore().apply { map["s1"] = THREAD_ID }
        server.enqueue(uploadOk())

        val result = client(cache, threads).uploadFile("a.txt", byteArrayOf(9), threadKey = "s1")

        assertTrue(result is AguiClient.UploadResult.Success)
        val req = takeRequest()
        assertEquals("/ai-server/common/file/upload", req.path)
        assertTrue(req.body.readUtf8().contains("agent----chatAgent----$THREAD_ID"))
        assertEquals(0, server.requestCount - 1) // 仅 upload 一个请求
    }

    /** 3. 上传信封 code!=200 → Error 透传平台 msg。 */
    @Test
    fun uploadEnvelopeErrorSurfacesMsg() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        val threads = MemThreadStore().apply { map["s1"] = THREAD_ID }
        server.enqueue(MockResponse().setBody("""{"code":500,"msg":"文件过大"}"""))

        val result = client(cache, threads).uploadFile("big.zip", byteArrayOf(1), threadKey = "s1")

        assertTrue(result is AguiClient.UploadResult.Error)
        assertTrue((result as AguiClient.UploadResult.Error).message.contains("文件过大"))
    }

    /** 4. 上传 401 → 透明重登 1 次重试成功（与 delete/rename 同模式，重登额度独立）。 */
    @Test
    fun upload401TriggersSingleTransparentRelogin() {
        val cache = MemTokenCache().apply { token = TOKEN_A } // 陈旧 token
        val threads = MemThreadStore().apply { map["s1"] = THREAD_ID }
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(loginOk(TOKEN_B))
        server.enqueue(uploadOk())

        val result = client(cache, threads).uploadFile("a.txt", byteArrayOf(1), threadKey = "s1")

        assertTrue(result is AguiClient.UploadResult.Success)
        assertEquals(TOKEN_B, cache.token)
        assertEquals("Bearer $TOKEN_A", takeRequest().getHeader("Authorization")) // 旧 token 撞 401
        takeRequest() // 重登
        assertEquals("Bearer $TOKEN_B", takeRequest().getHeader("Authorization")) // 新 token 重试
    }

    /** 5. 透明重登后仍 401 → Error（不无限重试）。 */
    @Test
    fun upload401AfterReloginFails() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        val threads = MemThreadStore().apply { map["s1"] = THREAD_ID }
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(loginOk(TOKEN_B))
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client(cache, threads).uploadFile("a.txt", byteArrayOf(1), threadKey = "s1")

        assertTrue(result is AguiClient.UploadResult.Error)
        assertEquals(3, server.requestCount)
    }

    /** 6. fileId 接入对话：chat 带 fileRefs 时 SSE 请求 content = 前缀 + \n + 用户文本。 */
    @Test
    fun chatInjectsFilePrefixIntoContent() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        val threads = MemThreadStore().apply { map["s1"] = THREAD_ID }
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                listOf(
                    """{"type":"RUN_STARTED","runId":"r"}""",
                    """{"type":"TEXT_MESSAGE_START","messageId":"m1","role":"assistant"}""",
                    """{"type":"TEXT_MESSAGE_CONTENT","messageId":"m1","delta":"好"}""",
                    """{"type":"TEXT_MESSAGE_END","messageId":"m1"}""",
                    """{"type":"RUN_FINISHED","runId":"r"}""",
                ).joinToString("\n\n") { "data: $it" } + "\n\n",
            ),
        )

        val result = client(cache, threads).chat(
            "对比这两份文件",
            threadKey = "s1",
            fileRefs = listOf("f1" to "报告.pdf", "f2" to "数据.xlsx"),
        )

        assertTrue(result is AguiClient.ChatResult.Success)
        val body = JSONObject(takeRequest().body.readUtf8())
        val content = body.getJSONArray("messages").getJSONObject(0).getString("content")
        assertEquals("@file:f1?报告.pdf|@file:f2?数据.xlsx\n对比这两份文件", content)
    }

    /** 7. 无文件（语音路径/纯文字）：content 原样等于用户文本，零前缀。 */
    @Test
    fun chatWithoutFilesLeavesContentUntouched() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        val threads = MemThreadStore().apply { map["s1"] = THREAD_ID }
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                listOf(
                    """{"type":"RUN_STARTED","runId":"r"}""",
                    """{"type":"TEXT_MESSAGE_START","messageId":"m1","role":"assistant"}""",
                    """{"type":"TEXT_MESSAGE_CONTENT","messageId":"m1","delta":"好"}""",
                    """{"type":"TEXT_MESSAGE_END","messageId":"m1"}""",
                    """{"type":"RUN_FINISHED","runId":"r"}""",
                ).joinToString("\n\n") { "data: $it" } + "\n\n",
            ),
        )

        val result = client(cache, threads).chat("你好", threadKey = "s1")

        assertTrue(result is AguiClient.ChatResult.Success)
        val body = JSONObject(takeRequest().body.readUtf8())
        assertEquals(
            "你好",
            body.getJSONArray("messages").getJSONObject(0).getString("content"),
        )
    }

    companion object {
        const val TOKEN_A = "11111111-1111-1111-1111-111111111111"
        const val TOKEN_B = "22222222-2222-2222-2222-222222222222"
        const val THREAD_ID = "0123456789abcdef0123456789abcdef"
        const val FILE_ID = "file-abc-123"
        const val TEST_PASSWORD = "test-password-not-real"
    }
}
