package com.xiaoduo.deskpet.cloud

import com.xiaoduo.deskpet.api.AsrContract
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 阿里云 DashScope 实时 ASR 客户端（WebSocket duplex，纯 Kotlin，零 Android import）。
 *
 * wss://dashscope.aliyuncs.com/api-ws/v1/inference/
 * 头：Authorization: Bearer {apiKey}
 * 协议（TEXT JSON 命令帧 + 二进制音频帧）：
 *   run-task（配置）→ 二进制 PCM 帧（200ms/帧）→ finish-task
 *   响应 TEXT 帧 header.event: result-generated | task-finished | task-failed
 *   result-generated 的 payload.output.sentence（JSONObject 或 JSONArray）即识别文本。
 */
class AliyunAsrClient(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val model: String = "paraformer-realtime-v2",
    private val sampleRate: Int = 16000,
    private val logger: (String) -> Unit = {},
) {
    sealed interface Result {
        data class Success(val text: String) : Result
        data class Error(val message: String) : Result
    }

    fun transcribe(wav: ByteArray, language: String = AsrContract.LANGUAGE_ZH): Result {
        return try {
            val pcm = stripWavPcm(wav)
            val taskId = UUID.randomUUID().toString().replace("-", "")
            val request = Request.Builder()
                .url(WS_URL)
                .header("Authorization", "Bearer $apiKey")
                .build()

            var resultText = ""
            var error: String? = null
            val done = CountDownLatch(1)

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    logger("aliyun asr WS open")
                    webSocket.send(runTask(taskId))
                    val frameBytes = (sampleRate / 10) * 2 // 200ms 音频/帧（16bit mono）
                    var frames = 0
                    var off = 0
                    while (off < pcm.size) {
                        val end = minOf(off + frameBytes, pcm.size)
                        webSocket.send(pcm.copyOfRange(off, end).toByteString())
                        frames++
                        off = end
                    }
                    webSocket.send(finishTask(taskId))
                    logger("aliyun asr sent $frames audio frames + finish")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                    val header = json.optJSONObject("header") ?: return
                    when (header.optString("event")) {
                        "result-generated" -> {
                            val output = json.optJSONObject("payload")?.optJSONObject("output")
                            when (val sentence = output?.opt("sentence")) {
                                is JSONObject -> {
                                    val t = sentence.optString("text", "")
                                    if (t.isNotEmpty()) resultText = t
                                }
                                is JSONArray -> {
                                    val sb = StringBuilder()
                                    for (i in 0 until sentence.length()) {
                                        val t = sentence.optJSONObject(i)?.optString("text", "")
                                        if (!t.isNullOrEmpty()) sb.append(t)
                                    }
                                    if (sb.isNotEmpty()) resultText = sb.toString()
                                }
                            }
                        }
                        "task-failed" -> {
                            error = "ASR task-failed: " + header.optString("error_message", text).take(120)
                            done.countDown()
                        }
                        "task-finished" -> done.countDown()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (done.count == 0L) return
                    logger("aliyun asr WS failure: ${t.message}")
                    error = "ASR WS 错误: ${t.message}"
                    done.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    done.countDown()
                }
            }

            val ws = http.newWebSocket(request, listener)
            if (!done.await(20, TimeUnit.SECONDS)) {
                ws.cancel()
                return Result.Error("ASR 超时（20s）")
            }
            ws.cancel()
            error?.let { return Result.Error(it) }
            if (resultText.isBlank()) return Result.Error("ASR 返回空文本")
            logger("aliyun asr ok, ${resultText.length} chars")
            Result.Success(resultText.trim())
        } catch (e: RuntimeException) {
            // ROB-14：顶层异常边界——畸形 WAV（stripWavPcm 负 chunk IAE）/并发中断等
            // RuntimeException 一律映射 Error，不击穿 Result 契约（无 IOException 路径，WS 内部自转）。
            Result.Error("ASR 请求异常: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun runTask(taskId: String): String = JSONObject().apply {
        put("header", JSONObject().apply {
            put("action", "run-task")
            put("task_id", taskId)
            put("streaming", "duplex")
        })
        put("payload", JSONObject().apply {
            put("model", model)
            put("task_group", "audio")
            put("task", "asr")
            put("function", "recognition")
            put("input", JSONObject())
            put("parameters", JSONObject().apply {
                put("format", "pcm")
                put("sample_rate", sampleRate)
                put("stream", true)
            })
        })
    }.toString()

    private fun finishTask(taskId: String): String = JSONObject().apply {
        put("header", JSONObject().apply {
            put("action", "finish-task")
            put("task_id", taskId)
            put("streaming", "duplex")
        })
        put("payload", JSONObject().apply {
            put("input", JSONObject())
        })
    }.toString()

    companion object {
        private const val WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference/"

        /** RIFF 容器剥 PCM：优先按 data 子块取（与 TtsClient 剥头同族）；无 data 块按 44B 头处理。 */
        fun stripWavPcm(wav: ByteArray): ByteArray {
            val magic = "data".toByteArray(Charsets.US_ASCII)
            var i = 12
            while (i + 8 <= wav.size) {
                if (wav[i] == magic[0] && wav[i + 1] == magic[1] && wav[i + 2] == magic[2] && wav[i + 3] == magic[3]) {
                    val start = i + 8
                    val size = ByteBuffer.wrap(wav, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    return wav.copyOfRange(start, minOf(start + size, wav.size))
                }
                val chunkSize = ByteBuffer.wrap(wav, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                i += chunkSize + 8 + (chunkSize and 1)
            }
            return wav.copyOfRange(minOf(44, wav.size), wav.size)
        }
    }
}
