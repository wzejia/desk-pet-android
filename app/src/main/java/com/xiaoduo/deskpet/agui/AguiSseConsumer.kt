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
    /** S20：TOOL_CALL_START 触发（MCP 静音开关标记源；每轮至多语义上多次调用，调用方自幂等）。 */
    private val onToolCallEvent: () -> Unit = {},
) {

    sealed interface RunResult {
        /** 正常收尾：RUN_FINISHED 已见且有产出文本 */
        data class Success(val text: String) : RunResult

        /** 平台空 run：RUN_FINISHED 已见但全程 0 字，调用方应同 id 重试（共 3 次） */
        data object EmptyRun : RunResult

        /** 断流：流结束但未见 RUN_FINISHED（reason 供日志透传，不伪装成功） */
        data class Error(val reason: String) : RunResult
    }

    private val accumulated = StringBuilder()
    private var sawRunFinished = false
    private var snapshotText: String? = null

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
            "TOOL_CALL_START" -> onToolCallEvent() // S20：MCP 调用轮标记（先于最终文本到达）
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
}
