package com.xiaoduo.deskpet.agui

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * AguiClient 单测（MockWebServer JVM 端对端桩）。
 * 覆盖：登录信封解析 / token 缓存不重登 / 空 run 同 id 重试 ×3 / 正常 SSE 全文 +
 * 请求契约头与体 / 401 透明重登 1 次；并断言日志不含 token/密码明文。
 */
class AguiClientTest {

    private lateinit var server: MockWebServer

    private class MemTokenCache : AguiClient.TokenCache {
        var token: String? = null
        var saves = 0
        override fun cached(): String? = token
        override fun saveFresh(token: String) {
            this.token = token
            saves++
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

    private fun sseBody(vararg events: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("\n\n") { "data: $it" } + "\n\n")

    /** 空 run 签名：RUN_STARTED → RUN_FINISHED，全程 0 字 */
    private fun emptyRunSse() = sseBody(
        """{"type":"RUN_STARTED","runId":"r"}""",
        """{"type":"RUN_FINISHED","runId":"r"}""",
    )

    private fun normalSse(text: String) = sseBody(
        """{"type":"RUN_STARTED","runId":"r"}""",
        """{"type":"STEP_STARTED","stepName":"think"}""",
        """{"type":"TEXT_MESSAGE_START","messageId":"m1","role":"assistant"}""",
        """{"type":"TEXT_MESSAGE_CONTENT","messageId":"m1","delta":"$text"}""",
        """{"type":"TEXT_MESSAGE_END","messageId":"m1"}""",
        """{"type":"MESSAGES_SNAPSHOT","messages":[{"id":"m1","role":"assistant","content":"$text"}]}""",
        """{"type":"RUN_FINISHED","runId":"r"}""",
    )

    private fun takeRequest() = server.takeRequest(2, TimeUnit.SECONDS)
        ?: error("expected a request but none arrived")

    /** 1. 登录信封 code!=200 → Error 透传平台 msg */
    @Test
    fun loginEnvelopeErrorSurfacesMsg() {
        server.enqueue(MockResponse().setBody("""{"code":500,"msg":"账号或密码错误"}"""))

        val result = client(MemTokenCache(), MemThreadStore()).chat("你好")

        assertTrue(result is AguiClient.ChatResult.Error)
        assertTrue((result as AguiClient.ChatResult.Error).message.contains("账号或密码错误"))
    }

    /** 2. token 缓存未过期：两次对话只登录 1 次、只建 1 个 thread（请求计数断言） */
    @Test
    fun cachedTokenSkipsReloginAcrossChats() {
        val cache = MemTokenCache()
        val threads = MemThreadStore()
        server.enqueue(loginOk())          // chat1: login
        server.enqueue(threadOk())         // chat1: createThreadId
        server.enqueue(normalSse("你好呀"))  // chat1: stream
        server.enqueue(normalSse("在呢"))    // chat2: stream only（token+thread 均复用）

        val c = client(cache, threads)
        assertTrue(c.chat("你好") is AguiClient.ChatResult.Success)
        assertTrue(c.chat("在吗") is AguiClient.ChatResult.Success)

        val paths = (1..4).map { takeRequest().path }
        assertEquals(
            listOf("/login", "/ai-server/agent/createThreadId", "/ai-server/agent/stream", "/ai-server/agent/stream"),
            paths,
        )
        assertEquals(1, cache.saves)
    }

    /** 3. 空 run ×3：同消息 id 自动重试共 3 次，仍空报「平台繁忙」 */
    @Test
    fun emptyRunRetriesThreeTimesWithSameMessageId() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        val threads = MemThreadStore().apply { map[AguiClient.DEFAULT_THREAD_KEY] = THREAD_ID }
        repeat(3) { server.enqueue(emptyRunSse()) }

        val result = client(cache, threads).chat("你好")

        assertTrue(result is AguiClient.ChatResult.Error)
        assertTrue((result as AguiClient.ChatResult.Error).message.contains("平台繁忙"))
        val messageIds = (1..3).map {
            val req = takeRequest()
            assertEquals("/ai-server/agent/stream", req.path)
            val messages = JSONObject(req.body.readUtf8()).getJSONArray("messages")
            assertEquals(1, messages.length()) // 增量单条，绝不重发历史
            messages.getJSONObject(0).getString("id")
        }
        assertEquals(1, messageIds.toSet().size)
        assertTrue(messageIds[0].startsWith("u_"))
    }

    /** 4. 正常 SSE 流到产出全文 + 请求头/请求体逐字段契约 + 日志不含 token/密码 */
    @Test
    fun normalSseYieldsFullTextAndContractShape() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        val threads = MemThreadStore().apply { map[AguiClient.DEFAULT_THREAD_KEY] = THREAD_ID }
        val logs = mutableListOf<String>()
        server.enqueue(normalSse("你好，我是小朵"))

        val deltas = mutableListOf<String>()
        val result = client(cache, threads, logs).chat("你好", onDelta = { deltas.add(it) })

        assertTrue(result is AguiClient.ChatResult.Success)
        result as AguiClient.ChatResult.Success
        assertEquals("你好，我是小朵", result.text)
        assertEquals(1, result.attempts)
        assertNotNull(result.firstTokenLatencyMs)
        assertEquals(listOf("你好，我是小朵"), deltas)

        val req = takeRequest()
        assertEquals("text/event-stream", req.getHeader("Accept"))
        assertEquals("application/json; charset=utf-8", req.getHeader("Content-Type"))
        assertEquals("chatAgent", req.getHeader("agentId"))
        assertEquals("Bearer $TOKEN_A", req.getHeader("Authorization"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals(THREAD_ID, body.getString("threadId"))
        assertTrue(body.getString("runId").startsWith("deskpet_run_"))
        assertEquals(0, body.getJSONObject("state").length())
        assertEquals(0, body.getJSONArray("tools").length())
        assertEquals(0, body.getJSONArray("context").length())
        val msg = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", msg.getString("role"))
        assertEquals("你好", msg.getString("content"))
        val fp = body.getJSONObject("forwardedProps")
        assertEquals("0.1.0", fp.getString("protocolVersion"))
        assertEquals("desk_pet", fp.getString("source"))

        // 日志纪律：全程不含 token/密码明文（含片段）
        assertFalse(logs.any { it.contains(TOKEN_A) || it.contains(TEST_PASSWORD) })
    }

    /** 5. HTTP 401 → 透明重登 1 次后原消息重发成功（重登不消耗空 run 次数） */
    @Test
    fun http401TriggersOneTransparentRelogin() {
        val cache = MemTokenCache().apply { token = TOKEN_A } // 陈旧 token
        val threads = MemThreadStore().apply { map[AguiClient.DEFAULT_THREAD_KEY] = THREAD_ID }
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":401,"msg":"token 已过期"}"""))
        server.enqueue(loginOk(TOKEN_B))
        server.enqueue(normalSse("重登后的回复"))

        val result = client(cache, threads).chat("你好")

        assertTrue(result is AguiClient.ChatResult.Success)
        result as AguiClient.ChatResult.Success
        assertEquals("重登后的回复", result.text)
        assertEquals(1, result.attempts) // 重登不占空 run 重试次数
        assertEquals("Bearer $TOKEN_A", takeRequest().getHeader("Authorization")) // 旧 token 撞 401
        assertEquals("/login", takeRequest().path)                               // 透明重登
        assertEquals("Bearer $TOKEN_B", takeRequest().getHeader("Authorization")) // 新 token 重发
        assertEquals(1, cache.saves)
    }

    /** 6. 消息 id 全局唯一：固定时钟同毫秒两次对话，消息 id 因单调序号不同（§2.2） */
    @Test
    fun messageIdsAreUniqueEvenWithinSameMillisecond() {
        val cache = MemTokenCache()
        val threads = MemThreadStore()
        server.enqueue(loginOk())         // chat1: login
        server.enqueue(threadOk())        // chat1: createThreadId
        server.enqueue(normalSse("一"))    // chat1: stream
        server.enqueue(normalSse("二"))    // chat2: stream（token/thread 复用）

        val c = AguiClient(
            http = OkHttpClient(),
            loginUrl = server.url("/login").toString(),
            streamUrl = server.url("/ai-server/agent/stream").toString(),
            credentials = { "admin" to TEST_PASSWORD },
            tokenCache = cache,
            threadStore = threads,
            clock = { 1_750_000_000_000L }, // 固定时钟 → 两次 chat 同毫秒
        )
        assertTrue(c.chat("第一条") is AguiClient.ChatResult.Success)
        assertTrue(c.chat("第二条") is AguiClient.ChatResult.Success)

        takeRequest() // login
        takeRequest() // createThreadId
        val id1 = JSONObject(takeRequest().body.readUtf8())
            .getJSONArray("messages").getJSONObject(0).getString("id")
        val id2 = JSONObject(takeRequest().body.readUtf8())
            .getJSONArray("messages").getJSONObject(0).getString("id")
        assertTrue(id1 != id2)
        assertTrue(id1.startsWith("u_1750000000000_"))
        assertTrue(id2.startsWith("u_1750000000000_"))
    }

    /** 7. createThreadId 撞 401 → 同样透明重登 1 次后重建 thread 成功（spec 边界表字面覆盖） */
    @Test
    fun createThread401TriggersOneTransparentRelogin() {
        val cache = MemTokenCache() // 空缓存：先登录拿 TOKEN_A
        val threads = MemThreadStore()
        server.enqueue(loginOk(TOKEN_A))                                   // login
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":401,"msg":"token 已过期"}""")) // createThreadId 撞 401
        server.enqueue(loginOk(TOKEN_B))                                   // 透明重登
        server.enqueue(threadOk())                                         // 用新 token 重建 thread
        server.enqueue(normalSse("重登建会话后的回复"))                        // stream

        val result = client(cache, threads).chat("你好")

        assertTrue(result is AguiClient.ChatResult.Success)
        assertEquals("重登建会话后的回复", (result as AguiClient.ChatResult.Success).text)
        assertEquals("/login", takeRequest().path)
        assertEquals("Bearer $TOKEN_A", takeRequest().getHeader("Authorization")) // createThreadId 旧 token 401
        assertEquals("/login", takeRequest().path)                               // 透明重登
        assertEquals("Bearer $TOKEN_B", takeRequest().getHeader("Authorization")) // createThreadId 新 token
        takeRequest() // stream
        assertEquals(2, cache.saves)
    }

    /** 8. 外部 messageId（手动重发复用，踩坑 #25）：空 run 重试全程同 id，请求体原样上 wire */
    @Test
    fun externalMessageIdReusedAcrossAttempts() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        val threads = MemThreadStore().apply { map[AguiClient.DEFAULT_THREAD_KEY] = THREAD_ID }
        server.enqueue(emptyRunSse())
        server.enqueue(normalSse("重发成功"))

        val result = client(cache, threads).chat("你好", messageId = "u_1750000000000_42")

        assertTrue(result is AguiClient.ChatResult.Success)
        result as AguiClient.ChatResult.Success
        assertEquals("重发成功", result.text)
        assertEquals(2, result.attempts)
        val ids = (1..2).map {
            val req = takeRequest()
            assertEquals("/ai-server/agent/stream", req.path)
            JSONObject(req.body.readUtf8())
                .getJSONArray("messages").getJSONObject(0).getString("id")
        }
        assertEquals(listOf("u_1750000000000_42", "u_1750000000000_42"), ids)
    }

    /** 9. 外部 messageId 传空白 = 未传：回落到自动生成 `u_{毫秒}_{seq}` */
    @Test
    fun blankExternalMessageIdFallsBackToGenerated() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        val threads = MemThreadStore().apply { map[AguiClient.DEFAULT_THREAD_KEY] = THREAD_ID }
        server.enqueue(normalSse("好"))

        val result = client(cache, threads).chat("你好", messageId = "  ")

        assertTrue(result is AguiClient.ChatResult.Success)
        val id = JSONObject(takeRequest().body.readUtf8())
            .getJSONArray("messages").getJSONObject(0).getString("id")
        assertTrue(id.startsWith("u_"))
        assertTrue(id != "u_")
    }

    /** 10. threadId 按会话路由（多会话）：不同 threadKey 各自建 thread 并缓存，同 key 复用 */
    @Test
    fun threadKeyRoutesPerSession() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        val threads = MemThreadStore()
        server.enqueue(threadOk(THREAD_A))   // chat1: createThreadId for key s1
        server.enqueue(normalSse("回复一"))   // chat1: stream
        server.enqueue(threadOk(THREAD_B))   // chat2: createThreadId for key s2
        server.enqueue(normalSse("回复二"))   // chat2: stream
        server.enqueue(normalSse("回复三"))   // chat3: key s1 复用缓存，不再建 thread

        val c = client(cache, threads)
        assertTrue(c.chat("你好", threadKey = "s1") is AguiClient.ChatResult.Success)
        assertTrue(c.chat("你好", threadKey = "s2") is AguiClient.ChatResult.Success)
        assertTrue(c.chat("在吗", threadKey = "s1") is AguiClient.ChatResult.Success)

        assertEquals("/ai-server/agent/createThreadId", takeRequest().path)
        assertEquals(THREAD_A, JSONObject(takeRequest().body.readUtf8()).getString("threadId"))
        assertEquals("/ai-server/agent/createThreadId", takeRequest().path)
        assertEquals(THREAD_B, JSONObject(takeRequest().body.readUtf8()).getString("threadId"))
        assertEquals(THREAD_A, JSONObject(takeRequest().body.readUtf8()).getString("threadId"))
        // key→threadId 映射缓存：恰好 2 条，各自命中
        assertEquals(mapOf("s1" to THREAD_A, "s2" to THREAD_B), threads.map)
    }

    /** 11. threadName 传入时作为 createThreadId 的 threadName（多会话：平台侧可见会话标题） */
    @Test
    fun threadNameSentOnThreadCreation() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        val threads = MemThreadStore()
        server.enqueue(threadOk())
        server.enqueue(normalSse("好"))

        val result = client(cache, threads).chat("你好", threadKey = "s1", threadName = "天气咨询")

        assertTrue(result is AguiClient.ChatResult.Success)
        val body = JSONObject(takeRequest().body.readUtf8())
        assertEquals("天气咨询", body.getString("threadName"))
        assertEquals("chatAgent", body.getString("agentId"))
        takeRequest() // stream
    }

    private companion object {
        const val TOKEN_A = "11111111-1111-1111-1111-111111111111"
        const val TOKEN_B = "22222222-2222-2222-2222-222222222222"
        const val THREAD_ID = "0123456789abcdef0123456789abcdef"
        const val THREAD_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val THREAD_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val TEST_PASSWORD = "test-password-not-real"
    }

    private fun deleteOk() = MockResponse().setBody("""{"code":200,"msg":"ok","data":null}""")

    /** 12. deleteThread 契约：POST /deleteThread + Bearer/agentId 头 + body {threadId}，信封 200 → Deleted；日志无 token/密码 */
    @Test
    fun deleteThreadSendsContractShape() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        val logs = mutableListOf<String>()
        server.enqueue(deleteOk())

        val result = client(cache, MemThreadStore(), logs).deleteThread(THREAD_ID)

        assertEquals(AguiClient.DeleteResult.Deleted, result)
        val req = takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/ai-server/agent/deleteThread", req.path)
        assertEquals("Bearer $TOKEN_A", req.getHeader("Authorization"))
        assertEquals("chatAgent", req.getHeader("agentId"))
        assertEquals(THREAD_ID, JSONObject(req.body.readUtf8()).getString("threadId"))
        assertFalse(logs.any { it.contains(TOKEN_A) || it.contains(TEST_PASSWORD) })
    }

    /** 13. deleteThread 撞 401 → 透明重登 1 次后重试成功（与 createThreadId 同模式） */
    @Test
    fun deleteThread401TriggersOneTransparentRelogin() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":401,"msg":"token 已过期"}"""))
        server.enqueue(loginOk(TOKEN_B))
        server.enqueue(deleteOk())

        val result = client(cache, MemThreadStore()).deleteThread(THREAD_ID)

        assertEquals(AguiClient.DeleteResult.Deleted, result)
        assertEquals("Bearer $TOKEN_A", takeRequest().getHeader("Authorization")) // 旧 token 撞 401
        assertEquals("/login", takeRequest().path)                               // 透明重登
        assertEquals("Bearer $TOKEN_B", takeRequest().getHeader("Authorization")) // 新 token 重试
        assertEquals(1, cache.saves)
    }

    /** 14. deleteThread 透明重登后仍 401 → Error（重登额度仅 1 次） */
    @Test
    fun deleteThreadStill401AfterReloginYieldsError() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(loginOk(TOKEN_B))
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client(cache, MemThreadStore()).deleteThread(THREAD_ID)

        assertTrue(result is AguiClient.DeleteResult.Error)
        assertTrue((result as AguiClient.DeleteResult.Error).message.contains("鉴权失败"))
        takeRequest(); takeRequest(); takeRequest()
    }

    /** 15. 幂等语义：多端并发重复删（信封 code!=200，thread 不存在）→ AlreadyGone，不视为失败 */
    @Test
    fun deleteThreadDuplicateDeleteEnvelopeTreatedAsAlreadyGone() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        server.enqueue(MockResponse().setBody("""{"code":500,"msg":"thread 不存在"}"""))

        val result = client(cache, MemThreadStore()).deleteThread(THREAD_ID)

        assertEquals(AguiClient.DeleteResult.AlreadyGone, result)
        takeRequest()
    }

    /** 16. 幂等语义：HTTP 非 2xx（非鉴权）同样视为已删 */
    @Test
    fun deleteThreadHttp500TreatedAsAlreadyGone() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":500,"msg":"server error"}"""))

        val result = client(cache, MemThreadStore()).deleteThread(THREAD_ID)

        assertEquals(AguiClient.DeleteResult.AlreadyGone, result)
        takeRequest()
    }

    /** 17. deleteThread 网络断连 → Error 返回而非抛出（失败不阻调用方本地删除的前提） */
    @Test
    fun deleteThreadNetworkFailureYieldsErrorNotThrow() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val result = client(cache, MemThreadStore()).deleteThread(THREAD_ID)

        assertTrue(result is AguiClient.DeleteResult.Error)
        assertTrue((result as AguiClient.DeleteResult.Error).message.contains("网络错误"))
    }

    /** 18. 空白 threadId → NoThread，零网络请求 */
    @Test
    fun deleteThreadBlankIdSkipsPlatformCall() {
        val result = client(MemTokenCache(), MemThreadStore()).deleteThread("  ")

        assertEquals(AguiClient.DeleteResult.NoThread, result)
        assertEquals(0, server.requestCount)
    }

    /** 19. deleteThreadForSession 无 threadId（从未发过消息的新会话）→ NoThread，零网络请求 */
    @Test
    fun deleteThreadForSessionWithoutThreadIdSkipsPlatformCall() {
        val result = client(MemTokenCache(), MemThreadStore()).deleteThreadForSession("s-no-thread")

        assertEquals(AguiClient.DeleteResult.NoThread, result)
        assertEquals(0, server.requestCount)
    }

    /** 20. deleteThreadForSession 删成功后清本地映射：再调直接跳过 */
    @Test
    fun deleteThreadForSessionDeletesAndClearsMapping() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        val threads = MemThreadStore().apply { map["s1"] = THREAD_ID }
        server.enqueue(deleteOk())

        val c = client(cache, threads)
        assertEquals(AguiClient.DeleteResult.Deleted, c.deleteThreadForSession("s1"))

        assertEquals(THREAD_ID, JSONObject(takeRequest().body.readUtf8()).getString("threadId"))
        assertTrue(threads.map.isEmpty())
        assertEquals(AguiClient.DeleteResult.NoThread, c.deleteThreadForSession("s1"))
    }

    /** 21. 放弃语义：deleteThreadForSession 网络失败仍清本地映射（会话已删，映射是死状态） */
    @Test
    fun deleteThreadForSessionFailureStillClearsMapping() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        val threads = MemThreadStore().apply { map["s1"] = THREAD_ID }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val result = client(cache, threads).deleteThreadForSession("s1")

        assertTrue(result is AguiClient.DeleteResult.Error)
        assertTrue(threads.map.isEmpty())
    }

    private fun renameOk() = MockResponse().setBody("""{"code":200,"msg":"ok","data":null}""")

    /** 22. renameThread 契约：POST /renameThread + Bearer/agentId 头 + body {threadId,threadName}，信封 200 → Renamed；日志无 token/密码 */
    @Test
    fun renameThreadSendsContractShape() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        val logs = mutableListOf<String>()
        server.enqueue(renameOk())

        val result = client(cache, MemThreadStore(), logs).renameThread(THREAD_ID, "我的法律咨询")

        assertEquals(AguiClient.RenameResult.Renamed, result)
        val req = takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/ai-server/agent/renameThread", req.path)
        assertEquals("Bearer $TOKEN_A", req.getHeader("Authorization"))
        assertEquals("chatAgent", req.getHeader("agentId"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals(THREAD_ID, body.getString("threadId"))
        assertEquals("我的法律咨询", body.getString("threadName"))
        assertFalse(logs.any { it.contains(TOKEN_A) || it.contains(TEST_PASSWORD) })
    }

    /** 23. renameThread 撞 401 → 透明重登 1 次后重试成功（与 deleteThread 同模式） */
    @Test
    fun renameThread401TriggersOneTransparentRelogin() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":401,"msg":"token 已过期"}"""))
        server.enqueue(loginOk(TOKEN_B))
        server.enqueue(renameOk())

        val result = client(cache, MemThreadStore()).renameThread(THREAD_ID, "新名")

        assertEquals(AguiClient.RenameResult.Renamed, result)
        assertEquals("Bearer $TOKEN_A", takeRequest().getHeader("Authorization")) // 旧 token 撞 401
        assertEquals("/login", takeRequest().path)                               // 透明重登
        assertEquals("Bearer $TOKEN_B", takeRequest().getHeader("Authorization")) // 新 token 重试
        assertEquals(1, cache.saves)
    }

    /** 24. renameThread 服务端拒绝（信封非 200）→ Error（非幂等：与 delete 的 AlreadyGone 语义不同） */
    @Test
    fun renameThreadEnvelopeErrorYieldsError() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        server.enqueue(MockResponse().setBody("""{"code":500,"msg":"thread 不存在"}"""))

        val result = client(cache, MemThreadStore()).renameThread(THREAD_ID, "新名")

        assertTrue(result is AguiClient.RenameResult.Error)
        takeRequest()
    }

    /** 25. renameThread 网络断连 → Error 返回而非抛出（fire-and-forget 前提：失败不阻本地改名） */
    @Test
    fun renameThreadNetworkFailureYieldsErrorNotThrow() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val result = client(cache, MemThreadStore()).renameThread(THREAD_ID, "新名")

        assertTrue(result is AguiClient.RenameResult.Error)
        assertTrue((result as AguiClient.RenameResult.Error).message.contains("网络错误"))
    }

    /** 26. renameThreadForSession 无 threadId（从未发过消息的新会话）→ NoThread，零网络请求 */
    @Test
    fun renameThreadForSessionWithoutThreadIdSkipsPlatformCall() {
        val result = client(MemTokenCache(), MemThreadStore()).renameThreadForSession("s-no-thread", "新名")

        assertEquals(AguiClient.RenameResult.NoThread, result)
        assertEquals(0, server.requestCount)
    }

    /** 27. renameThreadForSession 按会话 key 路由：用缓存的 threadId 发请求；失败后映射保留（会话还在） */
    @Test
    fun renameThreadForSessionRoutesByKeyAndKeepsMapping() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        val threads = MemThreadStore().apply { map["s1"] = THREAD_ID }
        server.enqueue(renameOk())

        val c = client(cache, threads)
        assertEquals(AguiClient.RenameResult.Renamed, c.renameThreadForSession("s1", "新名"))

        assertEquals(THREAD_ID, JSONObject(takeRequest().body.readUtf8()).getString("threadId"))
        // 与 delete 不同：rename 不清本地映射——会话仍存在，映射继续复用
        assertEquals(THREAD_ID, threads.map["s1"])
    }
}
