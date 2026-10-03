package com.xiaoduo.deskpet.agui

import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ROB-14（AguiClient 侧）：Request 构造在 try 外——用户可配畸形 baseUrl 使
 * Request.Builder.url 抛 IAE，击穿 Result 契约直崩调用方。修复后各公共入口
 * 顶层 RuntimeException 边界统一映射 *Result.Error。
 */
class AguiClientExceptionBoundaryTest {

    private class EmptyCache : AguiClient.TokenCache {
        override fun cached(): String? = null
        override fun saveFresh(token: String) {}
        override fun clear() {}
    }

    private class MemStore : AguiClient.ThreadIdStore {
        val map = HashMap<String, String>()
        override fun load(key: String): String? = map[key]
        override fun save(key: String, threadId: String) {
            map[key] = threadId
        }
        override fun remove(key: String) {
            map.remove(key)
        }
    }

    /** 恶意 URL：非法端口 + 空格 → Request.Builder.url 构造即抛 IAE。 */
    private val badUrl = "http://192.0.2.1:99999/bad url"

    @Test
    fun `ROB-14 chat with malformed loginUrl returns Error instead of throwing`() {
        val client = AguiClient(
            http = OkHttpClient(),
            loginUrl = badUrl,
            streamUrl = badUrl,
            credentials = { "u" to "p" },
            tokenCache = EmptyCache(),
            threadStore = MemStore(),
        )
        val r = client.chat("你好")
        assertTrue("chat 须返回 Error 而非抛异常，实际=$r", r is AguiClient.ChatResult.Error)
    }

    @Test
    fun `ROB-14 listAgents with malformed listUrl returns Error instead of throwing`() {
        val client = AguiClient(
            http = OkHttpClient(),
            loginUrl = badUrl,
            streamUrl = badUrl,
            credentials = { "u" to "p" },
            tokenCache = EmptyCache(),
            threadStore = MemStore(),
        )
        val r = client.listAgents()
        assertTrue("listAgents 须返回 Error 而非抛异常，实际=$r", r is AguiClient.AgentListResult.Error)
    }

    @Test
    fun `ROB-14 deleteThread with malformed url returns Error instead of throwing`() {
        val client = AguiClient(
            http = OkHttpClient(),
            loginUrl = badUrl,
            streamUrl = badUrl,
            credentials = { "u" to "p" },
            tokenCache = EmptyCache(),
            threadStore = MemStore(),
        )
        val r = client.deleteThread("tid")
        assertTrue("deleteThread 须返回 Error 而非抛异常，实际=$r", r is AguiClient.DeleteResult.Error)
    }
}
