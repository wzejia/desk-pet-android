package com.xiaoduo.deskpet.cloud

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 阿里云 CosyVoice TTS 客户端（dashscope WebSocket，纯 Kotlin，零 Android import）。
 *
 * wss://dashscope.aliyuncs.com/api-ws/v1/inference/
 * 头：Authorization: Bearer {apiKey}
 * 协议（duplex，TEXT JSON 命令帧 + 二进制 PCM 响应）：
 *   run-task（配置）→ continue-task（文本）→ finish-task
 *   响应 TEXT 帧 header.event: task-started | task-finished | task-failed
 *   响应 二进制帧 = PCM（24kHz/16-bit/mono）
 * 协议移植自 dashscope SDK speech_synthesizer.py（网关 tts_aliyun.py 已验证）。
 */
class AliyunTtsClient(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val model: String = "cosyvoice-v2",
    private val voice: String = "longxiaochun_v2",
    private val sampleRate: Int = 24000,
    private val logger: (String) -> Unit = {},
) {
    sealed interface Result {
        data class Audio(val pcm: ByteArray) : Result
        data class Error(val message: String) : Result
    }

    fun synthesize(text: String, speed: Double = 1.0): Result {
        if (text.isBlank()) return Result.Error("TTS 文本为空")
        val taskId = UUID.randomUUID().toString().replace("-", "")
        val request = Request.Builder()
            .url(WS_URL)
            .header("Authorization", "Bearer $apiKey")
            .build()

        val pcm = ByteArrayOutputStream()
        var error: String? = null
        val done = CountDownLatch(1)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                logger("aliyun tts WS open, ${text.length} chars")
                // run-task（配置）→ continue-task（文本）→ finish-task（WebSocket 保序，可连发）
                webSocket.send(runTask(taskId, speed))
                webSocket.send(continueTask(taskId, text))
                webSocket.send(finishTask(taskId))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
                val header = obj.optJSONObject("header") ?: return
                when (header.optString("event")) {
                    "task-finished" -> done.countDown()
                    "task-failed" -> {
                        error = "TTS task-failed: ${header.optString("error_message", text).take(200)}"
                        done.countDown()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                pcm.write(bytes.toByteArray())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.countDown() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (done.count == 0L) return // 已完成（task-finished）后的竞态关闭忽略
                logger("aliyun tts WS failure: ${t.message}")
                error = "TTS WS 错误: ${t.message}"; done.countDown()
            }
        }

        val ws = http.newWebSocket(request, listener)
        if (!done.await(30, TimeUnit.SECONDS)) {
            ws.cancel()
            return Result.Error("TTS 超时（30s）")
        }
        ws.cancel()
        error?.let { return Result.Error(it) }
        if (pcm.size() == 0) return Result.Error("TTS 返回空音频")
        logger("aliyun tts ok, ${pcm.size()}B pcm")
        return Result.Audio(pcm.toByteArray())
    }

    private fun runTask(taskId: String, speed: Double): String = JSONObject().apply {
        put("header", JSONObject().apply {
            put("action", "run-task"); put("task_id", taskId); put("streaming", "duplex")
        })
        put("payload", JSONObject().apply {
            put("model", model); put("task_group", "audio"); put("task", "tts"); put("function", "SpeechSynthesizer")
            put("input", JSONObject())
            put("parameters", JSONObject().apply {
                put("voice", voice); put("format", "pcm"); put("sample_rate", sampleRate)
                put("rate", speed); put("text_type", "PlainText"); put("pitch", 1.0)
            })
        })
    }.toString()

    private fun continueTask(taskId: String, text: String): String = JSONObject().apply {
        put("header", JSONObject().apply {
            put("action", "continue-task"); put("task_id", taskId); put("streaming", "duplex")
        })
        put("payload", JSONObject().apply {
            put("model", model); put("task_group", "audio"); put("task", "tts"); put("function", "SpeechSynthesizer")
            put("input", JSONObject().apply { put("text", text) })
        })
    }.toString()

    private fun finishTask(taskId: String): String = JSONObject().apply {
        put("header", JSONObject().apply {
            put("action", "finish-task"); put("task_id", taskId); put("streaming", "duplex")
        })
        put("payload", JSONObject().apply { put("input", JSONObject()) })
    }.toString()

    companion object {
        private const val WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference/"
    }
}
