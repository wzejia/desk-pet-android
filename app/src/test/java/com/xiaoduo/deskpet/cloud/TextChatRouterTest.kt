package com.xiaoduo.deskpet.cloud

import com.xiaoduo.deskpet.agui.AguiClient
import com.xiaoduo.deskpet.api.ChatMessage
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * TextChatRouter 单测（模式 A：双 MockWebServer，被测对象零 mock，只桩 HTTP 层）。
 *
 * 覆盖 08-14 云端文字轮分支（事故丢失后复原，本测试即防再丢锚）：
 * - llmIsCloud=true → 打云端 /chat/completions，平台零请求（不碰 login）；
 *   历史 = _messages.dropLast(1)（去 assistant 占位）过滤 SYSTEM/空文本，映射 Message(role.wire, text)；
 * - llmIsCloud=false → 打平台 login/createThreadId/stream，云端零请求；
 * - 云端 HTTP 500 → ChatResult.Error 透传。
 *
 * 变异锚：删 TextChatRouter.chat 的云端分支（恒走平台）→ 用例 1/3 红（云端 0 请求、平台被命中）；
 * 恒走云端 → 用例 2 红。
 */
class TextChatRouterTest {

    private lateinit var platformServer: MockWebServer
    private lateinit var cloudServer: MockWebServer

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
        platformServer = MockWebServer()
        platformServer.start()
        cloudServer = MockWebServer()
        cloudServer.start()
    }

    @After
    fun tearDown() {
        platformServer.shutdown()
        cloudServer.shutdown()
    }

    private fun platformClient() = AguiClient(
        http = OkHttpClient(),
        loginUrl = platformServer.url("/login").toString(),
        streamUrl = platformServer.url("/ai-server/agent/stream").toString(),
        credentials = { "admin" to "test-password-not-real" },
        tokenCache = MemTokenCache(),
        threadStore = MemThreadStore(),
    )

    private fun cloudClient() = OpenAIChatClient(
        http = OkHttpClient(),
        baseUrl = cloudServer.url("/v1").toString(),
        apiKey = CLOUD_KEY,
        model = CLOUD_MODEL,
    )

    private fun router(llmIsCloud: Boolean) = TextChatRouter(
        llmIsCloud = { llmIsCloud },
        cloudClient = { cloudClient() },
        platformClient = platformClient(),
    )

    /** OpenAI 兼容 SSE：逐行 `data: {...}`，`data: [DONE]` 收尾。 */
    private fun cloudSse(vararg deltas: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            deltas.joinToString("\n") {
                """data: {"choices":[{"delta":{"content":"$it"}}]}"""
            } + "\ndata: [DONE]\n",
        )

    private fun loginOk() = MockResponse().setBody("""{"code":200,"msg":"ok","token":"$TOKEN_A"}""")

    private fun threadOk() = MockResponse().setBody("""{"code":200,"msg":"ok","data":"$THREAD_ID"}""")

    private fun aguiSse(text: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            listOf(
                """{"type":"RUN_STARTED","runId":"r"}""",
                """{"type":"STEP_STARTED","stepName":"think"}""",
                """{"type":"TEXT_MESSAGE_START","messageId":"m1","role":"assistant"}""",
                """{"type":"TEXT_MESSAGE_CONTENT","messageId":"m1","delta":"$text"}""",
                """{"type":"TEXT_MESSAGE_END","messageId":"m1"}""",
                """{"type":"MESSAGES_SNAPSHOT","messages":[{"id":"m1","role":"assistant","content":"$text"}]}""",
                """{"type":"RUN_FINISHED","runId":"r"}""",
            ).joinToString("\n\n") { "data: $it" } + "\n\n",
        )

    private fun takePlatformRequest() = platformServer.takeRequest(2, TimeUnit.SECONDS)
        ?: error("expected a platform request but none arrived")

    private fun takeCloudRequest() = cloudServer.takeRequest(2, TimeUnit.SECONDS)
        ?: error("expected a cloud request but none arrived")

    private fun msg(id: String, role: ChatMessage.Role, text: String) =
        ChatMessage(id, role, text, ChatMessage.Status.DONE, 1L)

    /** 1. 云端路：POST /v1/chat/completions；历史去占位+滤 SYSTEM/空文本+role.wire 映射；平台零请求 */
    @Test
    fun cloudModeRoutesToCloudAndSkipsPlatform() {
        cloudServer.enqueue(cloudSse("晴", "天"))
        val deltas = mutableListOf<String>()

        val result = router(llmIsCloud = true).chat(
            messages = listOf(
                msg("m0", ChatMessage.Role.SYSTEM, "系统提示"), // 滤除：SYSTEM
                msg("m1", ChatMessage.Role.USER, "你好"),
                msg("m2", ChatMessage.Role.ASSISTANT, "你好呀"),
                msg("m3", ChatMessage.Role.USER, "   "), // 滤除：空文本
                msg("m4", ChatMessage.Role.USER, "今天天气"),
                // dropLast(1)：本轮占位。占位 text 必须非空——若为空串，「删 dropLast」变异
                // 会被 isNotBlank 过滤掩盖而存活（critic P3-1，实测该变异曾存活）
                msg("m5", ChatMessage.Role.ASSISTANT, "残留"),
            ),
            userText = "今天天气",
            wireId = "w-1",
            threadKey = "s1",
            threadName = "标题",
            fileRefs = emptyList(),
            onDelta = { deltas.add(it) },
        )

        assertTrue(result is AguiClient.ChatResult.Success)
        result as AguiClient.ChatResult.Success
        assertEquals("晴天", result.text)
        assertNotNull(result.firstTokenLatencyMs)
        assertEquals(1, result.attempts) // 云端适配常量 attempts=1（无空 run 重试概念）
        assertEquals(listOf("晴", "天"), deltas)

        val req = takeCloudRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer $CLOUD_KEY", req.getHeader("Authorization"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals(CLOUD_MODEL, body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        val msgs = body.getJSONArray("messages")
        assertEquals(3, msgs.length())
        assertEquals("user", msgs.getJSONObject(0).getString("role"))
        assertEquals("你好", msgs.getJSONObject(0).getString("content"))
        assertEquals("assistant", msgs.getJSONObject(1).getString("role"))
        assertEquals("你好呀", msgs.getJSONObject(1).getString("content"))
        assertEquals("user", msgs.getJSONObject(2).getString("role"))
        assertEquals("今天天气", msgs.getJSONObject(2).getString("content"))

        assertEquals(0, platformServer.requestCount) // 不碰平台 login
    }

    /** 2. 平台路：login + createThreadId + stream 三请求；云端零请求 */
    @Test
    fun platformModeRoutesToPlatformAndSkipsCloud() {
        platformServer.enqueue(loginOk())
        platformServer.enqueue(threadOk())
        platformServer.enqueue(aguiSse("在呢"))
        val deltas = mutableListOf<String>()

        val result = router(llmIsCloud = false).chat(
            messages = listOf(
                msg("m1", ChatMessage.Role.USER, "在吗"),
                msg("m2", ChatMessage.Role.ASSISTANT, ""),
            ),
            userText = "在吗",
            wireId = "w-2",
            threadKey = "s1",
            threadName = "标题",
            fileRefs = emptyList(),
            onDelta = { deltas.add(it) },
        )

        assertTrue(result is AguiClient.ChatResult.Success)
        assertEquals("在呢", (result as AguiClient.ChatResult.Success).text)
        assertEquals(listOf("在呢"), deltas)

        val paths = (1..3).map { takePlatformRequest().path }
        assertEquals(
            listOf("/login", "/ai-server/agent/createThreadId", "/ai-server/agent/stream"),
            paths,
        )
        assertEquals(0, cloudServer.requestCount)
    }

    /** 3. 云端 HTTP 500 → ChatResult.Error 透传（平台零请求）；错误体 error 字段入 message */
    @Test
    fun cloudHttpErrorAdaptsToChatResultError() {
        cloudServer.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"rate limited"}"""))

        val result = router(llmIsCloud = true).chat(
            messages = listOf(
                msg("m1", ChatMessage.Role.USER, "你好"),
                msg("m2", ChatMessage.Role.ASSISTANT, ""),
            ),
            userText = "你好",
            wireId = "w-3",
            threadKey = "s1",
            threadName = "标题",
            fileRefs = emptyList(),
            onDelta = {},
        )

        assertTrue(result is AguiClient.ChatResult.Error)
        assertTrue((result as AguiClient.ChatResult.Error).message.contains("LLM HTTP 500"))
        // envelope 透传钉死（critic P3-2）：服务端错误体 error 字段必须进 message
        assertTrue(result.message.contains("rate limited"))
        assertEquals(0, platformServer.requestCount)
    }

    /** 4. S41 文字轮补接：平台路 TOOL_CALL_START 工具名透传到 onToolCallEvent
     *  （密集架动画联动；S40 副本缺口「TextChatRouter 未接 onToolCallEvent」本票拍板补上） */
    @Test
    fun platformModeForwardsToolCallName() {
        platformServer.enqueue(loginOk())
        platformServer.enqueue(threadOk())
        platformServer.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                listOf(
                    """{"type":"RUN_STARTED","runId":"r"}""",
                    """{"type":"TOOL_CALL_START","toolCallId":"t1","toolCallName":"mjj_move"}""",
                    """{"type":"TEXT_MESSAGE_START","messageId":"m1","role":"assistant"}""",
                    """{"type":"TEXT_MESSAGE_CONTENT","messageId":"m1","delta":"已打开"}""",
                    """{"type":"TEXT_MESSAGE_END","messageId":"m1"}""",
                    """{"type":"RUN_FINISHED","runId":"r"}""",
                ).joinToString("\n\n") { "data: $it" } + "\n\n",
            ),
        )
        val names = mutableListOf<String>()

        val result = router(llmIsCloud = false).chat(
            messages = listOf(
                msg("m1", ChatMessage.Role.USER, "打开1号密集架"),
                msg("m2", ChatMessage.Role.ASSISTANT, ""),
            ),
            userText = "打开1号密集架",
            wireId = "w-4",
            threadKey = "s1",
            threadName = "标题",
            fileRefs = emptyList(),
            onDelta = {},
            onToolCallEvent = { name, _ -> names.add(name) },
        )

        assertTrue(result is AguiClient.ChatResult.Success)
        assertEquals(listOf("mjj_move"), names)
    }

    private companion object {
        const val TOKEN_A = "11111111-1111-1111-1111-111111111111"
        const val THREAD_ID = "0123456789abcdef0123456789abcdef"
        const val CLOUD_KEY = "sk-test-cloud-key"
        const val CLOUD_MODEL = "test-model"
    }
}
