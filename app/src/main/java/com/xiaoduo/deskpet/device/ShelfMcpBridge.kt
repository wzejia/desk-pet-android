package com.xiaoduo.deskpet.device

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 密集架 MCP 动画联动桥（S41，2026-09-14 立项；E:\mjj 副本 S40-MCP 代码丢失后按
 * 设计蓝本 E:\mjj\EXECUTION-S40-MCP动画联动.md §4 重建）。
 *
 * 链路：平台执行 C 款密集架 Agent 的 MCP 工具 → SSE TOOL_CALL_START{toolCallName}
 * → [callFor] 映射 → [Client.syncAsync] POST {shelfBaseUrl}/MjjWebApi
 * body `{"Op":"mcpUiSync","tool":"<name>"}` → 密集架 App 开架动画页/Toast
 * （密集架侧已实现该 Op，蓝本 §7.2 模拟器实测 200 + 动画截图）。
 *
 * 防双控（蓝本 §4.2 铁律）：平台 MCP **已经**执行硬件/业务，本地一律只调 UI-only 的
 * mcpUiSync，绝不重复下发串口/电机/盘点任务。
 *
 * 守卫口径：
 * - 未知工具 / 空白地址 → 零请求静默返回；
 * - 非法地址 / enqueue 异常 / onFailure / 非 2xx → 只落日志，**绝不抛**进语音/文字管线；
 * - fire-and-forget 无取消 API：UI 同步是即时幂等提示（动画重入/Toast 重播安全），
 *   轮次取消无需撤销已发同步；一轮多个 TOOL_CALL_START 各同步一次（语义正确），
 *   空 run 重试理论上的重复同步接受不去重。
 */
object ShelfMcpBridge {

    /** C 款密集架 Agent 的 10 个 MCP 工具（蓝本 §1.1；契约锚：多/少/改名皆红）。 */
    val SYNCED_TOOLS: Set<String> = setOf(
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
    )

    /**
     * 平台工具名 → 桥内规范名别名表。2026-10-21 真板（10.0.14.18）SSE 实抓：平台密集架
     * Agent 已把该工具改名为 archive_query（无 c_ 前缀），TOOL_CALL_START
     * name=archive_query args={query,query_scope,keyword}，TOOL_CALL_RESULT 正常返回。
     * 映射到旧名 c_archive_query 而非反过来：密集架侧白名单（AppController.mcpSyncedTools）
     * 与结果卡标题语义（titleFor）都锚旧名，归一到旧名后密集架端零改动。
     * 2026-10-24：平台新增变体 archive_query_18082，语义同档案查询，一并归一旧名（用户指令）。
     * 注意：仅 archive_query 族有板测实证；其余工具（borrow/return/inventory 族）是否同步
     * 改名无证据，不臆测入表，待与平台核对后再补。
     */
    val PLATFORM_TOOL_ALIASES: Map<String, String> = mapOf(
        "archive_query" to "c_archive_query",
        "archive_query_18082" to "c_archive_query",
    )

    /**
     * 工具名 → mcpUiSync 请求体（逐字 JSON；名字全部出自 [SYNCED_TOOLS] 白名单，
     * 无转义/注入风险）。未知工具（含空串、非本 Agent 工具）→ null：调用方不发请求。
     * S67b：runId 非空时带上（密集架端凭它区分「新一轮 START（旧卡让位）」与「同轮第二个
     * 工具 START（旧卡保留，否则同轮第一条结果刚弹就被误杀）」）；空则不带（向后兼容）。 */
    fun callFor(tool: String, runId: String = ""): String? {
        val canonical = canonicalToolName(tool)
        if (canonical !in SYNCED_TOOLS) return null
        return if (runId.isBlank()) """{"Op":"mcpUiSync","tool":"$canonical"}"""
               else """{"Op":"mcpUiSync","tool":"$canonical","runId":"$runId"}"""
    }

    /** S46 允许本地导航的密集架页面（白名单防注入；page 拼进逐字 JSON）。 */
    val NAVIGATE_PAGES: Set<String> = setOf("borrow", "return", "inventory", "home") // S62 += home

    /**
     * S46 页面 → mcpUiNavigate 请求体（desk-pet 本地意图直发，非 MCP 工具，
     * 不走 [SYNCED_TOOLS]：导航不执行业务/硬件，无防双控问题）。未知 page → null。
     */
    fun navigateBodyFor(page: String): String? =
        if (page in NAVIGATE_PAGES) """{"Op":"mcpUiNavigate","page":"$page"}""" else null

    /**
     * S43：结果卡转发体 `{"Op":"mcpUiResult","tool":..,"result":..,"args":..}`。
     * result/args 是平台原文（result 为工具结果的 JSON 字符串），经 org.json 转义嵌套——
     * 不可用字符串模板拼接（content 内含引号会产出非法 JSON）。
     * S67：runId 非空时带上（弹窗按轮次分组：同 runId 追加/去重，新 runId 整窗重置）；空则不带该键。
     * 未知工具 / 空 result → null：调用方不发请求。
     */
    fun resultBodyFor(tool: String, content: String, argsJson: String? = null, runId: String = ""): String? {
        val canonical = canonicalToolName(tool)
        if (canonical !in SYNCED_TOOLS || content.isBlank()) return null
        val body = org.json.JSONObject()
            .put("Op", "mcpUiResult")
            .put("tool", canonical)
            .put("result", content)
        if (!argsJson.isNullOrBlank()) body.put("args", argsJson)
        if (runId.isNotBlank()) body.put("runId", runId)
        return body.toString()
    }

    /**
     * 异步同步客户端（OkHttp enqueue fire-and-forget；[baseUrl] 每次调用现读，
     * 设置页改动即时生效）。本类无会话状态，进程内单例即可。
     */
    class Client(
        private val baseUrl: () -> String,
        private val http: OkHttpClient = defaultHttp(),
        private val logger: (String) -> Unit = {},
    ) {
        /** S41：密集架动画同步；S67b：runId 随体转发（弹窗让位判定用，见 [callFor]） */
        fun syncAsync(tool: String, runId: String = "") {
            postAsync(callFor(tool, runId) ?: return, tool, "sync")
        }

        /** S46：密集架页面导航（**同步** POST，须在 IO 线程调用；true=2xx）。
         *  与 sync 的 fire-and-forget 不同：导航需要成败反馈给用户语音/文字回执。 */
        fun navigate(page: String): Boolean {
            val body = navigateBodyFor(page) ?: return false
            val base = baseUrl().trim()
            if (base.isEmpty()) return false // 设置清空 = 禁用联动
            return try {
                val req = Request.Builder()
                    .url("$base/MjjWebApi")
                    .post(body.toByteArray(Charsets.UTF_8).toRequestBody(JSON))
                    .build()
                http.newCall(req).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                logger("shelf mcp navigate failed page=$page: ${e.message}")
                false
            }
        }

        /** S43：结果卡转发（fire-and-forget，守卫口径同 [syncAsync]）；S67：runId 随体转发 */
        fun resultAsync(tool: String, content: String, argsJson: String? = null, runId: String = "") {
            postAsync(resultBodyFor(tool, content, argsJson, runId) ?: return, tool, "result")
        }

        private fun postAsync(body: String, tool: String, kind: String) {
            val base = baseUrl().trim()
            if (base.isEmpty()) return // 设置清空 = 禁用联动
            val req = try {
                Request.Builder()
                    .url("$base/MjjWebApi")
                    // ByteArray 变体：String.toRequestBody 会给无 charset 的 media type
                    // 自动追加 "; charset=utf-8"，这里要求逐字 "application/json"（蓝本 §7.2 形态）
                    .post(body.toByteArray(Charsets.UTF_8).toRequestBody(JSON))
                    .build()
            } catch (e: IllegalArgumentException) {
                logger("shelf mcp $kind skipped, bad base url: ${e.message}")
                return
            }
            try {
                http.newCall(req).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        logger("shelf mcp $kind failed tool=$tool: ${e.message}")
                    }

                    override fun onResponse(call: Call, resp: Response) {
                        resp.use {
                            if (it.isSuccessful) logger("shelf mcp $kind ok tool=$tool")
                            else logger("shelf mcp $kind HTTP ${it.code} tool=$tool")
                        }
                    }
                })
            } catch (e: Exception) {
                logger("shelf mcp $kind enqueue failed tool=$tool: ${e.message}")
            }
        }
    }

    /** 显式 NO_PROXY 局域网直连（对齐 DeviceClient：系统代理会把内网请求卡死/502）。 */
    fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /** 逐字 Content-Type（蓝本 §7.2 实测可通形态；不带 charset 后缀）。 */
    private val JSON = "application/json".toMediaType()
}

/**
 * 平台工具名归一：命中 [ShelfMcpBridge.PLATFORM_TOOL_ALIASES] 别名则映射（如
 * archive_query → c_archive_query），否则原样返回（identity）。纯 JVM 顶层函数
 * （seam 纪律：免构造 Android 类即可单测），[ShelfMcpBridge.callFor] 与
 * [ShelfMcpBridge.resultBodyFor] 入口先经此归一，再判白名单/拼请求体。
 */
internal fun canonicalToolName(raw: String): String =
    ShelfMcpBridge.PLATFORM_TOOL_ALIASES[raw] ?: raw
