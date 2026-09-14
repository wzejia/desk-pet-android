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
     * 工具名 → mcpUiSync 请求体（逐字 JSON；名字全部出自 [SYNCED_TOOLS] 白名单，
     * 无转义/注入风险）。未知工具（含空串、非本 Agent 工具）→ null：调用方不发请求。
     */
    fun callFor(tool: String): String? =
        if (tool in SYNCED_TOOLS) """{"Op":"mcpUiSync","tool":"$tool"}""" else null

    /**
     * 异步同步客户端（OkHttp enqueue fire-and-forget；[baseUrl] 每次调用现读，
     * 设置页改动即时生效）。本类无会话状态，进程内单例即可。
     */
    class Client(
        private val baseUrl: () -> String,
        private val http: OkHttpClient = defaultHttp(),
        private val logger: (String) -> Unit = {},
    ) {
        fun syncAsync(tool: String) {
            val body = callFor(tool) ?: return // 未知工具静默（不动画、不打扰）
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
                logger("shelf mcp sync skipped, bad base url: ${e.message}")
                return
            }
            try {
                http.newCall(req).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        logger("shelf mcp sync failed tool=$tool: ${e.message}")
                    }

                    override fun onResponse(call: Call, resp: Response) {
                        resp.use {
                            if (it.isSuccessful) logger("shelf mcp sync ok tool=$tool")
                            else logger("shelf mcp sync HTTP ${it.code} tool=$tool")
                        }
                    }
                })
            } catch (e: Exception) {
                logger("shelf mcp sync enqueue failed tool=$tool: ${e.message}")
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
