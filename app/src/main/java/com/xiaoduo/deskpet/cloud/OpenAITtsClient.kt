package com.xiaoduo.deskpet.cloud

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * OpenAI 兼容 TTS 客户端（纯 Kotlin，零 Android import，JVM 可测）。
 *
 * POST {base}/audio/speech（JSON {model,input,voice,response_format}）→ 音频字节。
 * 请求 pcm（24kHz/16-bit/mono，OpenAI TTS pcm 格式即此）→ 直接裸 PCM，
 * 与 TtsClient 输出契约（剥 WAV 头后的裸 PCM）一致。
 */
class OpenAITtsClient(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val defaultVoice: String,
    private val logger: (String) -> Unit = {},
) {
    sealed interface Result {
        data class Audio(val pcm: ByteArray) : Result
        data class Error(val message: String) : Result
    }

    fun synthesize(text: String, voice: String = "default", speed: Double = 1.0): Result = try {
        val payload = JSONObject()
            .put("model", model)
            .put("input", text)
            .put("voice", if (voice == "default") defaultVoice else voice)
            .put("response_format", "pcm")
            .apply { if (speed != 1.0) put("speed", speed) }
            .toString()
            .toRequestBody(JSON)
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/audio/speech")
            .header("Authorization", "Bearer $apiKey")
            .post(payload)
            .build()
        logger("cloud tts POST, ${text.length} chars")
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body?.string().orEmpty()
                return Result.Error("TTS HTTP ${resp.code}: ${body.take(120)}")
            }
            val pcm = resp.body?.bytes() ?: byteArrayOf()
            if (pcm.isEmpty()) Result.Error("TTS 返回空音频")
            else { logger("cloud tts ok, ${pcm.size}B pcm"); Result.Audio(pcm) }
        }
    } catch (e: IOException) {
        Result.Error("TTS 网络错误: ${e.message}")
    } catch (e: RuntimeException) {
        // ROB-14：畸形 baseUrl / OkHttp ISE 等须守 Result 契约
        Result.Error("TTS 请求异常: ${e.javaClass.simpleName}: ${e.message}")
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
