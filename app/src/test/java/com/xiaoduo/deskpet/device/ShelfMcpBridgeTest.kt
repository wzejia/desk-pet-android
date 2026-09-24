package com.xiaoduo.deskpet.device

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * S41 密集架 MCP 动画联动桥单测（模式 A/C：集合/映射纯逻辑 + Client MockWebServer 集成）。
 *
 * 链路（蓝本 E:\mjj\EXECUTION-S40-MCP动画联动.md §4.1）：平台执行 C 款密集架 Agent 的
 * MCP 工具 → SSE TOOL_CALL_START{toolCallName} → [ShelfMcpBridge.callFor] 映射 →
 * Client POST {shelfBaseUrl}/MjjWebApi body {"Op":"mcpUiSync","tool":"<name>"}
 * → 密集架 App 动画页/Toast（UI-only，防双控：绝不重复下发硬件 Op）。
 *
 * 守卫口径：未知工具/空白地址零请求；非 2xx/网络失败只记日志绝不抛给语音/文字管线。
 */
class ShelfMcpBridgeTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl() = server.url("").toString().removeSuffix("/")

    /** 1. SYNCED_TOOLS 恰好为 C 款密集架 Agent 的 10 个 MCP 工具（契约锚：多/少/改名皆红） */
    @Test
    fun `synced tools are exactly the 10 shelf mcp tools`() {
        assertEquals(
            setOf(
                "c_archive_query",
                "archive_borrow",
                "archive_box_borrow",
                "archive_box_return",
                "archive_return",
                "archives_inventory",
                "archives_inventory_save",
                "mjj_close",
                "mjj_move",
                "mjj_ventilate",
            ),
            ShelfMcpBridge.SYNCED_TOOLS,
        )
    }

    /** 2. callFor：10 个工具逐一映射为逐字 JSON body */
    @Test
    fun `callFor maps every synced tool to byte-exact mcpUiSync body`() {
        for (tool in ShelfMcpBridge.SYNCED_TOOLS) {
            assertEquals("""{"Op":"mcpUiSync","tool":"$tool"}""", ShelfMcpBridge.callFor(tool))
        }
    }

    /** 3. callFor：未知工具（含空串/大小写漂移）→ null（调用方不发请求） */
    @Test
    fun `callFor returns null for unknown tools`() {
        assertNull(ShelfMcpBridge.callFor("open_the_door"))
        assertNull(ShelfMcpBridge.callFor(""))
        assertNull(ShelfMcpBridge.callFor("MJJ_MOVE")) // 大小写敏感，契约锚
    }

    /** 4. Client：POST {baseUrl}/MjjWebApi，Content-Type=application/json，body 逐字（蓝本 §7.2 实测形态） */
    @Test
    fun `client posts byte-exact json to MjjWebApi`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":200,"data":"ok"}"""))
        val client = ShelfMcpBridge.Client(baseUrl = { baseUrl() }, logger = {})

        client.syncAsync("mjj_move")

        val req = server.takeRequest(2, TimeUnit.SECONDS) ?: error("no request arrived")
        assertEquals("POST", req.method)
        assertEquals("/MjjWebApi", req.path)
        assertEquals("application/json", req.getHeader("Content-Type"))
        assertEquals("""{"Op":"mcpUiSync","tool":"mjj_move"}""", req.body.readUtf8())
    }

    /** 5. Client：非 2xx 只记日志不抛异常（失败绝不抛给语音/文字管线） */
    @Test
    fun `client does not throw on non-2xx and logs failure`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val client = ShelfMcpBridge.Client(baseUrl = { baseUrl() }, logger = { logs.add(it) })

        client.syncAsync("mjj_close") // 不抛即过一半；再断言请求真发出 + 失败落日志

        val req = server.takeRequest(2, TimeUnit.SECONDS) ?: error("no request arrived")
        assertEquals("/MjjWebApi", req.path)
        val deadline = System.currentTimeMillis() + 2000
        while (logs.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("非 2xx 必须落日志: $logs", logs.any { it.contains("500") })
    }

    /** 6. Client：未知工具零请求（callFor null 短路） */
    @Test
    fun `client sends nothing for unknown tool`() {
        val client = ShelfMcpBridge.Client(baseUrl = { baseUrl() }, logger = {})
        client.syncAsync("not_a_shelf_tool")
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    /** 7. Client：空白 baseUrl 零请求（设置清空=禁用） */
    @Test
    fun `client sends nothing when base url blank`() {
        val client = ShelfMcpBridge.Client(baseUrl = { "  " }, logger = {})
        client.syncAsync("mjj_move")
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    /** 8. Client：baseUrl 每次调用现读（设置改动即时生效——改指新地址后请求打到新 server） */
    @Test
    fun `client reads base url per call`() {
        val server2 = MockWebServer()
        server2.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200))
            server2.enqueue(MockResponse().setResponseCode(200))
            var base = baseUrl()
            val client = ShelfMcpBridge.Client(baseUrl = { base }, logger = {})

            client.syncAsync("mjj_move")
            assertEquals("/MjjWebApi", server.takeRequest(2, TimeUnit.SECONDS)?.path)

            base = server2.url("").toString().removeSuffix("/")
            client.syncAsync("mjj_close")
            assertEquals("/MjjWebApi", server2.takeRequest(2, TimeUnit.SECONDS)?.path)
            assertNull("旧地址不得再收到请求", server.takeRequest(300, TimeUnit.MILLISECONDS))
        } finally {
            server2.shutdown()
        }
    }

    /** ===== S43：结果卡转发体（resultBodyFor / resultAsync）===== */

    /** S43-1：已知工具+结果 → 合法 JSON，Op/tool/result 逐字，content 内引号正确转义 */
    @Test
    fun `result body escapes nested content verbatim`() {
        val body = ShelfMcpBridge.resultBodyFor(
            "c_archive_query", "{\"success\":true,\"status\":\"no_result\"}", "{\"tm\":\"测试档案0001\"}"
        )
        val j = org.json.JSONObject(requireNotNull(body))
        assertEquals("mcpUiResult", j.getString("Op"))
        assertEquals("c_archive_query", j.getString("tool"))
        assertEquals("{\"success\":true,\"status\":\"no_result\"}", j.getString("result"))
        assertEquals("{\"tm\":\"测试档案0001\"}", j.getString("args"))
    }

    /** S43-2：未知工具 / 空白 content → null（静默不发）；args 缺省时不带该键 */
    @Test
    fun `result body guards unknown tool and blank content`() {
        assertNull(ShelfMcpBridge.resultBodyFor("not_a_shelf_tool", "x"))
        assertNull(ShelfMcpBridge.resultBodyFor("archive_return", "  "))
        val j = org.json.JSONObject(requireNotNull(ShelfMcpBridge.resultBodyFor("archive_return", "ok")))
        assertEquals(false, j.has("args"))
    }

    /** S67-1：runId 非空 → body 带 runId（弹窗按轮次分组：同 runId 追加/去重，新 runId 重置） */
    @Test
    fun `result body carries runId when present`() {
        val body = ShelfMcpBridge.resultBodyFor(
            "c_archive_query", "{\"status\":\"success\"}", null, "run-abc-1"
        )
        assertEquals("run-abc-1", org.json.JSONObject(requireNotNull(body)).getString("runId"))
    }

    /** S67b-1：sync 体 runId 非空带该键（密集架凭它区分新轮 START/同轮 START，防误杀第一张结果卡） */
    @Test
    fun `sync body carries runId when present`() {
        assertEquals(
            """{"Op":"mcpUiSync","tool":"mjj_move","runId":"run-x-1"}""",
            ShelfMcpBridge.callFor("mjj_move", "run-x-1"),
        )
    }

    /** S67b-2：sync 体 runId 缺省 → 逐字现状体（向后兼容旧密集架端） */
    @Test
    fun `sync body omits runId when absent`() {
        assertEquals("""{"Op":"mcpUiSync","tool":"mjj_move"}""", ShelfMcpBridge.callFor("mjj_move"))
        assertEquals("""{"Op":"mcpUiSync","tool":"mjj_move"}""", ShelfMcpBridge.callFor("mjj_move", ""))
    }

    /** S67-2：runId 缺省/空串 → 不带该键（向后兼容旧弹窗） */
    @Test
    fun `result body omits runId when absent`() {
        val j = org.json.JSONObject(requireNotNull(ShelfMcpBridge.resultBodyFor("archive_return", "ok")))
        assertEquals(false, j.has("runId"))
        val j2 = org.json.JSONObject(requireNotNull(ShelfMcpBridge.resultBodyFor("archive_return", "ok", null, "")))
        assertEquals(false, j2.has("runId"))
    }

    /** S43-3：Client.resultAsync 真实发出 POST，路径/Content-Type 与 sync 同形态 */
    @Test
    fun `client posts result to mjj web api`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client = ShelfMcpBridge.Client(baseUrl = { baseUrl() }, logger = {})
        client.resultAsync("archive_return", "{\"status\":\"success\"}")
        val req = server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("/MjjWebApi", req?.path)
        assertEquals("application/json", req?.getHeader("Content-Type"))
        assertTrue(req?.body?.readUtf8()?.contains("mcpUiResult") == true)
    }
}
