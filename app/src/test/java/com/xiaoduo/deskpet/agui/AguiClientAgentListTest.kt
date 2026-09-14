package com.xiaoduo.deskpet.agui

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * S16 agent 选择协议层单测（MockWebServer JVM 端对端桩）。
 * 覆盖：listAgents 信封解析与请求契约 / 401 透明重登 1 次 / 信封错误透传 /
 * agentId 动态 provider（每次请求现读，切换即时生效于 header 与 createThreadId body）。
 */
class AguiClientAgentListTest {

    private lateinit var server: MockWebServer

    private class MemTokenCache : AguiClient.TokenCache {
        var token: String? = null
        override fun cached(): String? = token
        override fun saveFresh(token: String) { this.token = token }
        override fun clear() { token = null }
    }

    private class MemThreadStore : AguiClient.ThreadIdStore {
        val map = HashMap<String, String>()
        override fun load(key: String): String? = map[key]
        override fun save(key: String, threadId: String) { map[key] = threadId }
        override fun remove(key: String) { map.remove(key) }
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
        agentIdProvider: () -> String = { AguiClient.DEFAULT_AGENT_ID },
    ) = AguiClient(
        http = OkHttpClient(),
        loginUrl = server.url("/login").toString(),
        streamUrl = server.url("/ai-server/agent/stream").toString(),
        credentials = { "admin" to TEST_PASSWORD },
        tokenCache = cache,
        threadStore = threads,
        agentIdProvider = agentIdProvider,
    )

    private fun loginOk(token: String = TOKEN_A) =
        MockResponse().setBody("""{"code":200,"msg":"ok","token":"$token"}""")

    private fun threadOk(id: String = THREAD_ID) =
        MockResponse().setBody("""{"code":200,"msg":"ok","data":"$id"}""")

    private fun takeRequest() = server.takeRequest(2, TimeUnit.SECONDS)
        ?: error("expected a request but none arrived")

    private fun normalSse(text: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            "data: {\"type\":\"RUN_STARTED\",\"runId\":\"r\"}\n\n" +
                "data: {\"type\":\"STEP_STARTED\",\"stepName\":\"think\"}\n\n" +
                "data: {\"type\":\"TEXT_MESSAGE_START\",\"messageId\":\"m1\",\"role\":\"assistant\"}\n\n" +
                "data: {\"type\":\"TEXT_MESSAGE_CONTENT\",\"messageId\":\"m1\",\"delta\":\"$text\"}\n\n" +
                "data: {\"type\":\"TEXT_MESSAGE_END\",\"messageId\":\"m1\"}\n\n" +
                "data: {\"type\":\"MESSAGES_SNAPSHOT\",\"messages\":[{\"id\":\"m1\",\"role\":\"assistant\",\"content\":\"$text\"}]}\n\n" +
                "data: {\"type\":\"RUN_FINISHED\",\"runId\":\"r\"}\n\n",
        )

    /** 1. listAgents：登录 -> POST /agent/list 空 body + Bearer，信封 data.list 解析为 AgentBrief 列表 */
    @Test
    fun listAgentsParsesEnvelopeList() {
        server.enqueue(loginOk())
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"msg":"ok","data":{"list":[""" +
                    """{"agentId":"chatAgent","agentName":"智能问答","agentDescription":"默认的智能体"},""" +
                    """{"agentId":"hkChatAgent","agentName":"环控","agentDescription":null},""" +
                    """{"agentId":"yearChatAgent","agentName":"年报助手"}""" +
                    """]}}""",
            ),
        )

        val result = client(MemTokenCache(), MemThreadStore()).listAgents()

        assertTrue(result is AguiClient.AgentListResult.Ok)
        result as AguiClient.AgentListResult.Ok
        assertEquals(
            listOf(
                AguiClient.AgentBrief("chatAgent", "智能问答", "默认的智能体"),
                AguiClient.AgentBrief("hkChatAgent", "环控", null),
                AguiClient.AgentBrief("yearChatAgent", "年报助手", null),
            ),
            result.agents,
        )

        assertEquals("/login", takeRequest().path)
        val req = takeRequest()
        assertEquals("/ai-server/agent/list", req.path)
        assertEquals("Bearer $TOKEN_A", req.getHeader("Authorization"))
        assertEquals("{}", req.body.readUtf8())
    }

    /** 2. token 过期 401 -> 透明重登 1 次后重试成功（与 deleteThread 同模式） */
    @Test
    fun listAgents401TriggersOneTransparentRelogin() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":401,"msg":"token 已过期"}"""))
        server.enqueue(loginOk(TOKEN_B))
        server.enqueue(
            MockResponse().setBody(
                """{"code":200,"msg":"ok","data":{"list":[{"agentId":"chatAgent","agentName":"智能问答"}]}}""",
            ),
        )

        val result = client(cache, MemThreadStore()).listAgents()

        assertTrue(result is AguiClient.AgentListResult.Ok)
        assertEquals(1, (result as AguiClient.AgentListResult.Ok).agents.size)

        assertEquals("/ai-server/agent/list", takeRequest().path)          // 旧 token 撞 401
        assertEquals("/login", takeRequest().path)                          // 透明重登
        assertEquals("/ai-server/agent/list", takeRequest().path)          // 新 token 重试
    }

    /** 3. 信封 code!=200 -> Error 透传平台 msg */
    @Test
    fun listAgentsEnvelopeErrorYieldsError() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        server.enqueue(MockResponse().setBody("""{"code":500,"msg":"内部错误"}"""))

        val result = client(cache, MemThreadStore()).listAgents()

        assertTrue(result is AguiClient.AgentListResult.Error)
        assertTrue((result as AguiClient.AgentListResult.Error).message.contains("内部错误"))
    }

    /** 4. agentId 动态 provider：两次请求间切换，header 与 createThreadId body 均即时取新值 */
    @Test
    fun agentIdProviderReadsDynamicallyPerRequest() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        val threads = MemThreadStore()
        var current = "chatAgent"
        val c = client(cache, threads) { current }

        // 第一轮：chat（stream 头）+ createThreadId（body）
        server.enqueue(threadOk())
        server.enqueue(normalSse("你好呀"))
        assertTrue(c.chat("你好") is AguiClient.ChatResult.Success)

        current = "hkChatAgent"
        threads.map.clear() // 换 agent 后新 thread（createThreadId 再走一次）
        server.enqueue(threadOk())
        server.enqueue(normalSse("在呢"))
        assertTrue(c.chat("在吗") is AguiClient.ChatResult.Success)

        // 每轮请求序：createThreadId（header+body）先、stream（header）后
        val create1 = takeRequest()
        assertEquals("/ai-server/agent/createThreadId", create1.path)
        assertEquals("chatAgent", create1.getHeader("agentId"))
        assertEquals("chatAgent", JSONObject(create1.body.readUtf8()).getString("agentId"))
        assertEquals("chatAgent", takeRequest().getHeader("agentId")) // stream1

        val create2 = takeRequest()
        assertEquals("/ai-server/agent/createThreadId", create2.path)
        assertEquals("hkChatAgent", create2.getHeader("agentId"))
        assertEquals("hkChatAgent", JSONObject(create2.body.readUtf8()).getString("agentId"))
        assertEquals("hkChatAgent", takeRequest().getHeader("agentId")) // stream2
    }

    /** S20 二分定位：chat 流含 TOOL_CALL_START 时 onToolCallEvent 必须触发 */
    @Test
    fun `chat fires onToolCallEvent when stream has tool call`() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        val threads = MemThreadStore().apply { map[AguiClient.DEFAULT_THREAD_KEY] = THREAD_ID }
        var fired = 0
        val sse = listOf(
            """{"type":"RUN_STARTED","runId":"r"}""",
            """{"type":"TOOL_CALL_START","toolCallId":"t1"}""",
            """{"type":"TEXT_MESSAGE_CONTENT","messageId":"m1","delta":"好"}""",
            """{"type":"RUN_FINISHED","runId":"r"}""",
        ).joinToString("\n\n") { "data: " + it } + "\n\n"
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse),
        )
        val result = client(cache, threads).chat(
            "你好",
            onToolCallEvent = { fired++ },
        )
        println("result=$result fired=$fired")
        org.junit.Assert.assertEquals(1, fired)
    }

    /** S41：chat 把 TOOL_CALL_START 的 toolCallName 透传到 onToolCallEvent
     *  （S20 排障留痕「runOnce 漏传参被默认参数静默吞」的锚升级——名字丢失必须变红） */
    @Test
    fun `chat passes tool call name to callback`() {
        val cache = MemTokenCache().apply { token = TOKEN_A }
        val threads = MemThreadStore().apply { map[AguiClient.DEFAULT_THREAD_KEY] = THREAD_ID }
        val names = mutableListOf<String>()
        val sse = listOf(
            """{"type":"RUN_STARTED","runId":"r"}""",
            """{"type":"TOOL_CALL_START","toolCallId":"t1","toolCallName":"mjj_move"}""",
            """{"type":"TEXT_MESSAGE_CONTENT","messageId":"m1","delta":"好"}""",
            """{"type":"RUN_FINISHED","runId":"r"}""",
        ).joinToString("\n\n") { "data: " + it } + "\n\n"
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse),
        )
        val result = client(cache, threads).chat(
            "打开1号密集架",
            onToolCallEvent = { names.add(it) },
        )
        org.junit.Assert.assertTrue(result is AguiClient.ChatResult.Success)
        org.junit.Assert.assertEquals(listOf("mjj_move"), names)
    }

    companion object {
        private const val TOKEN_A = "11111111-1111-1111-1111-111111111111"
        private const val TOKEN_B = "22222222-2222-2222-2222-222222222222"
        private const val THREAD_ID = "0123456789abcdef0123456789abcdef"
        private const val TEST_PASSWORD = "test-password-not-real"
    }
}
