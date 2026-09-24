package com.xiaoduo.deskpet.agui

import org.json.JSONException
import org.json.JSONObject

/**
 * 平台 AG-UI SSE 消费器。纯 Kotlin，零 Android import，可 JVM 单测。
 *
 * 契约依据：docs/m1-dev-kit/android-dev-brief.md §2.2 + 踩坑 #23-26。
 * 铁律（三件套 + SNAPSHOT 兜底）：
 * 1. 主消费 TEXT_MESSAGE_CONTENT 的 delta 增量；RAW 等事件忽略。
 * 2. finish() 时未见 RUN_FINISHED = 中间层断流 → 显式 Error，绝不伪装成功（#26）。
 * 3. 全程累计 0 字（含 SNAPSHOT 补尾后）= 平台空 run → EmptyRun（重试由调用方负责）。
 * 兜底：MESSAGES_SNAPSHOT 末条 assistant 文本（content 兼容 string 与
 * [{type:"text",text}] 分片）前缀比对，比已产出长则以已产出为前缀才补尾部增量。
 *
 * 用法：逐行 feedLine()，流结束后调 finish() 取终态结果。
 * 非线程安全：内部状态无同步保护，须在单线程（或串行协程）内 feed/finish。
 */
class AguiSseConsumer(
    private val onDelta: (String) -> Unit = {},
    /** S20/S41：TOOL_CALL_START 触发，透传 toolCallName（S20 静音标记源 + S41 密集架
     *  动画联动桥工具名源；每轮可多次调用，调用方自幂等；缺名字段时为 ""）。 */
    private val onToolCallEvent: (String) -> Unit = {},
    /** S66：TOOL_CALL_RESULT 到达即回调（不等流结束）——结果卡与 TTS 播报并行的消费器侧
     *  基础；每次结果各回调一次（覆盖式语义由下游承接），name 缺失对齐收尾取出语义（空串）。 */
    private val onToolCallResult: (ToolCallOutcome) -> Unit = {},
) {

    sealed interface RunResult {
        /** 正常收尾：RUN_FINISHED 已见且有产出文本 */
        data class Success(val text: String) : RunResult

        /** 平台空 run：RUN_FINISHED 已见但全程 0 字，调用方应同 id 重试（共 3 次） */
        data object EmptyRun : RunResult

        /** 断流：流结束但未见 RUN_FINISHED（reason 供日志透传，不伪装成功） */
        data class Error(val reason: String) : RunResult
    }

    /** S43：本轮最后一次工具调用的结果（SSE 实证：TOOL_CALL_START 顶层带 toolCallId/toolCallName
     *  与 rawEvent 内完整 args；TOOL_CALL_RESULT 顶层带 toolCallId/content。多结果轮取最后一条，
     *  平台内部澄清轮次自然被覆盖，密集架侧结果卡只看到最终结果）。
     *  S67：runId 由客户端层（AguiClient）注入，consumer 层不填（默认空串=旧语义）；
     *  密集架弹窗据此分组：同 runId 追加/去重，新 runId 整窗重置。 */
    data class ToolCallOutcome(
        val toolCallName: String,
        val content: String,
        val argsJson: String?,
        val runId: String = "",
    )

    private val accumulated = StringBuilder()
    private var sawRunFinished = false
    private var snapshotText: String? = null
    private val toolCallNames = HashMap<String, String>()
    private val toolCallArgs = HashMap<String, String?>()
    private var lastResultId: String? = null
    private var lastResultContent: String? = null

    /** 因坏 JSON 等原因跳过的 data 行数（容错观测用） */
    var skippedLines: Int = 0
        private set

    /** 喂入一行 SSE 原文。容忍空行、`: ` 心跳注释、`event:` 行；坏 JSON data 行记 skipped 不抛异常。 */
    fun feedLine(line: String) {
        if (line.isBlank()) return
        if (!line.startsWith("data:")) return // 心跳注释 / event: / 其他行一律容忍
        val payload = line.substring(5).trim()
        if (payload.isEmpty()) return
        val json = try {
            JSONObject(payload)
        } catch (e: JSONException) {
            skippedLines++
            return
        }
        when (json.optString("type")) {
            "TEXT_MESSAGE_CONTENT" -> {
                val delta = json.optString("delta", "")
                if (delta.isNotEmpty()) {
                    accumulated.append(delta)
                    onDelta(delta)
                }
            }
            "MESSAGES_SNAPSHOT" -> snapshotText = extractLastAssistantText(json)
            "TOOL_CALL_START" -> {
                val name = json.optString("toolCallName", "")
                onToolCallEvent(name) // S20 标记 + S41 透传工具名（先于最终文本到达）
                val id = json.optString("toolCallId", "")
                if (id.isNotEmpty()) {
                    toolCallNames[id] = name
                    toolCallArgs[id] = extractArgs(json)
                }
            }
            "TOOL_CALL_RESULT" -> {
                // S43：记最后一次结果（多结果轮覆盖，finish 后由 takeToolCallOutcome 取出）
                val id = json.optString("toolCallId", "")
                if (id.isNotEmpty()) {
                    lastResultId = id
                    lastResultContent = json.optString("content", "")
                }
                // S66：即时回调（不等流结束）——结果卡转发时机从 run 收尾提前到结果到达；
                // name/args 经 toolCallId 查 START 期登记，缺失对齐收尾取出语义（name 空串/args null）
                onToolCallResult(
                    ToolCallOutcome(
                        toolCallName = toolCallNames[id].orEmpty(),
                        content = json.optString("content", ""),
                        argsJson = toolCallArgs[id],
                    )
                )
            }
            "RUN_FINISHED" -> sawRunFinished = true
            // RUN_STARTED / RAW / STEP_STARTED / TEXT_MESSAGE_START/END 等一律忽略（#24）
        }
    }

    /**
     * 流收尾。先执行 SNAPSHOT 前缀比对补尾，再按三件套判定终态。
     * 顺序：断流校验 > 空 run 校验 > 成功。
     */
    fun finish(): RunResult {
        applySnapshotBackfill()
        if (!sawRunFinished) {
            return RunResult.Error("stream ended without RUN_FINISHED (produced ${accumulated.length} chars)")
        }
        if (accumulated.isEmpty()) {
            return RunResult.EmptyRun
        }
        return RunResult.Success(accumulated.toString())
    }

    /** SNAPSHOT 兜底：末条 assistant 文本以已产出为前缀且更长 → 补尾部增量（#26 前缀比对铁律） */
    private fun applySnapshotBackfill() {
        val snap = snapshotText ?: return
        val produced = accumulated.toString()
        if (snap.length > produced.length && snap.startsWith(produced)) {
            val tail = snap.substring(produced.length)
            accumulated.append(tail)
            onDelta(tail)
        }
    }

    /** 取 messages 数组中最后一条 role==assistant 的文本；content 兼容 string 与分片数组；末条缺 content 则继续向上找 */
    private fun extractLastAssistantText(snapshot: JSONObject): String? {
        val messages = snapshot.optJSONArray("messages") ?: return null
        for (i in messages.length() - 1 downTo 0) {
            val msg = messages.optJSONObject(i) ?: continue
            if (msg.optString("role") != "assistant") continue
            val text = when (val content = msg.opt("content")) {
                is String -> content
                is org.json.JSONArray -> buildString {
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        append(part.optString("text", ""))
                    }
                }
                else -> null // 缺 content 或非常规形态：继续找上一条 assistant
            }
            if (text != null) return text
        }
        return null
    }

    /** S43：finish 后取本轮最后一次工具结果；无结果轮（纯文本/断流未收 RESULT）返回 null */
    fun takeToolCallOutcome(): ToolCallOutcome? {
        val id = lastResultId ?: return null
        val content = lastResultContent ?: return null
        return ToolCallOutcome(
            toolCallName = toolCallNames[id].orEmpty(),
            content = content,
            argsJson = toolCallArgs[id],
        )
    }

    /** 从 TOOL_CALL_START 的 rawEvent 提取工具参数原文（实证路径 rawEvent.data.chunk.tool_calls[0].args，
     *  args 为 JSON 对象；防御性提取，任一层缺失/形态不符 → null，不影响主链路）。 */
    private fun extractArgs(start: JSONObject): String? = runCatching {
        val args = start.optJSONObject("rawEvent")
            ?.optJSONObject("data")
            ?.optJSONObject("chunk")
            ?.optJSONArray("tool_calls")
            ?.optJSONObject(0)
            ?.opt("args")
        when (args) {
            is JSONObject -> args.toString()
            is org.json.JSONArray -> args.toString()
            is String -> args.takeIf { it.isNotBlank() }
            else -> null
        }
    }.getOrNull()
}
