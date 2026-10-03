package com.xiaoduo.deskpet.agui

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * CONC-6：AguiClient 共享实例 auth 状态并发竞态。
 * - reloginUsed 是「每次 chat 透明重登 1 次」的调用级额度：实例级共享会让并发 chat
 *   互相清零/互耗配额（A 的重登把 B 的额度烧掉）。
 * - lastAuthError 同理串台（A 的错误消息贴给 B）。
 * - runId 仅 clock()+attempt：同毫秒并发/连发可撞（平台 runId 应唯一）。
 */
class AguiClientConcurrencyTest {

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

    private val uuid = "00000000-0000-0000-0000-000000000001"

    private fun loginOk(token: String = uuid) =
        MockResponse().setBody("""{"code":200,"msg":"ok","token":"$token"}""")

    private fun threadOk(id: String) =
        MockResponse().setBody("""{"code":200,"msg":"ok","data":"$id"}""")

    private fun sse401() = MockResponse().setResponseCode(401)

    private fun sseOk(text: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            listOf(
                """{"type":"RUN_STARTED","runId":"r"}""",
                """{"type":"TEXT_MESSAGE_START","messageId":"m1","role":"assistant"}""",
                """{"type":"TEXT_MESSAGE_CONTENT","messageId":"m1","delta":"$text"}""",
                """{"type":"TEXT_MESSAGE_END","messageId":"m1"}""",
                """{"type":"RUN_FINISHED","runId":"r"}""",
            ).joinToString("\n\n") { "data: $it" } + "\n\n",
        )

    /**
     * 红测 1：A 在重登中途挂起（credentials 阻塞）时，B 的 401 必须走 B 自己的重登额度。
     * 修复前：A 已把共享 reloginUsed 置 true → B 的 reloginOnce 早退 → B 报
     * 「token 过期透明重登失败」→ 红。
     */
    @Test
    fun `CONC-6 concurrent chats have independent relogin budgets`() {
        val cache = MemTokenCache()
        val threads = MemThreadStore()
        val reloginGate = CountDownLatch(1)
        val aReloginStarted = CountDownLatch(1)
        var credCalls = 0
        val client = AguiClient(
            http = OkHttpClient(),
            loginUrl = server.url("/login").toString(),
            streamUrl = server.url("/ai-server/agent/stream").toString(),
            credentials = {
                credCalls++
                if (credCalls == 2) { // A 的重登 login：占住不回（模拟慢网络），此后共享态已置位
                    aReloginStarted.countDown()
                    reloginGate.await(30, TimeUnit.SECONDS)
                }
                "u" to "p"
            },
            tokenCache = cache,
            threadStore = threads,
        )
        // A 前半程入队：login → thread(default) → SSE 401 →（重登 login 在 credentials 内阻塞，
        // 其响应须等门开后再入队，否则会错位喂给 B 的请求）
        server.enqueue(loginOk())
        server.enqueue(threadOk("threadA"))
        server.enqueue(sse401())

        val pool = Executors.newSingleThreadExecutor()
        val aFuture = pool.submit(java.util.concurrent.Callable { client.chat("A") })
        // 等 A 进入重登 login（此时共享 reloginUsed 已置位、缓存已清）
        assertTrue("A 应进入重登", aReloginStarted.await(10, TimeUnit.SECONDS))

        // B 全程入队：login → thread(B) → SSE 401 → login（重登）→ SSE ok
        //（重登后 threadId 已在 "B" 键下缓存，不再 createThreadId）
        server.enqueue(loginOk())
        server.enqueue(threadOk("threadB"))
        server.enqueue(sse401())
        server.enqueue(loginOk())
        server.enqueue(sseOk("B-done"))

        val bResult = client.chat("B", threadKey = "B")

        assertTrue(
            "B 的 401 必须走 B 自己的重登额度（实际=$bResult）",
            bResult is AguiClient.ChatResult.Success,
        )
        assertEquals("B-done", (bResult as AguiClient.ChatResult.Success).text)

        // B 收尾后入队 A 的重登两响应（thread 已在 default 键下，重登后不再 createThreadId）
        server.enqueue(loginOk())
        server.enqueue(sseOk("A-done"))

        reloginGate.countDown()
        val aResult = aFuture.get(20, TimeUnit.SECONDS)
        pool.shutdownNow()
        assertTrue("A 最终也应成功（实际=$aResult）", aResult is AguiClient.ChatResult.Success)
    }

    /**
     * 红测 2：同一 clock 毫秒下连发两轮 chat，runId 不得相撞（请求体解析锚）。
     * 修复前：runId = deskpet_run_{clock}_{attempt} 恒等 → 红。
     */
    @Test
    fun `CONC-6 runId unique across chats sharing the same clock`() {
        val cache = MemTokenCache()
        val threads = MemThreadStore()
        val client = AguiClient(
            http = OkHttpClient(),
            loginUrl = server.url("/login").toString(),
            streamUrl = server.url("/ai-server/agent/stream").toString(),
            credentials = { "u" to "p" },
            tokenCache = cache,
            threadStore = threads,
            clock = { 1000L }, // 恒定时钟：暴露纯 attempt 计数的碰撞
        )
        server.enqueue(loginOk())
        server.enqueue(threadOk("threadA"))
        server.enqueue(sseOk("one"))
        server.enqueue(sseOk("two"))

        client.chat("一")
        client.chat("二")

        // 消费队列：login、thread、两条 stream 请求
        server.takeRequest(5, TimeUnit.SECONDS)
        server.takeRequest(5, TimeUnit.SECONDS)
        val req1 = server.takeRequest(5, TimeUnit.SECONDS)
        val req2 = server.takeRequest(5, TimeUnit.SECONDS)
        val r1 = JSONObject(req1!!.body.readUtf8()).optString("runId")
        val r2 = JSONObject(req2!!.body.readUtf8()).optString("runId")
        assertTrue(r1.isNotBlank() && r2.isNotBlank())
        assertNotEquals("同毫秒两轮 runId 不得相撞: $r1 vs $r2", r1, r2)
    }
}
