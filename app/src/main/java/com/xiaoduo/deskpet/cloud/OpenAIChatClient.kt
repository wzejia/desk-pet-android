package com.xiaoduo.deskpet.cloud

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * OpenAI 兼容 LLM 客户端（POST /chat/completions，SSE 流式，纯 Kotlin，零 Android import）。
 * 请求体：{model, messages:[{role,content}], stream:true}（systemPrompt 非空时前置 system 消息）。
 * SSE 逐行解析 `data: {...}` → choices[0].delta.content → onDelta 实时回调；`[DONE]` 收尾。
 * 首个非空 delta 记 firstTokenLatencyMs。
 */
class OpenAIChatClient(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val logger: (String) -> Unit = {},
) {
    sealed interface Result {
        data class Success(val text: String, val firstTokenLatencyMs: Long?) : Result
        data class Error(val message: String) : Result
    }

    fun chat(history: List<Message>, systemPrompt: String?, onDelta: (String) -> Unit): Result {
        val messages = JSONArray()
        if (!systemPrompt.isNullOrBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        for (msg in history) {
            messages.put(JSONObject().put("role", msg.role).put("content", msg.content))
        }
        val bodyJson = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
            .toString()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(bodyJson.toRequestBody(JSON))
            .build()

        logger("cloud llm POST $baseUrl stream=${history.size}msgs")
        val t0 = System.currentTimeMillis()
        var firstTokenMs: Long? = null
        val sb = StringBuilder()
        return try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    return@use Result.Error("LLM HTTP ${resp.code}: ${envelope(body)}")
                }
                val source = resp.body!!.source()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") break
                    val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    val delta = json.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("delta")
                        ?.optString("content") ?: continue
                    if (delta.isEmpty()) continue
                    if (firstTokenMs == null) firstTokenMs = System.currentTimeMillis() - t0
                    sb.append(delta)
                    onDelta(delta)
                }
                val text = sb.toString().trim()
                if (text.isEmpty()) {
                    Result.Error("LLM 返回空回复")
                } else {
                    logger("cloud llm ok, ${text.length} chars, firstToken=${firstTokenMs} ms")
                    Result.Success(text, firstTokenMs)
                }
            }
        } catch (e: IOException) {
            Result.Error("LLM 网络错误: ${e.message}")
        }
    }

    /** 错误体取 error 字段（OpenAI 兼容错误格式），取不到回退截断原文。 */
    private fun envelope(body: String): String {
        val err = runCatching { JSONObject(body).optString("error") }.getOrNull()
        return if (!err.isNullOrBlank()) err else body.take(120)
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** OpenAI 兼容对话消息（role: system/user/assistant）。 */
data class Message(val role: String, val content: String)
