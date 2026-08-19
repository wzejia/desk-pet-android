package com.xiaoduo.deskpet.cloud

import com.xiaoduo.deskpet.voice.AsrClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * OpenAI 兼容 ASR 客户端（POST /audio/transcriptions，multipart 表单，纯 Kotlin，零 Android import）。
 * 表单：file=utterance.wav(audio/wav) / model / language(可选) / response_format=json。
 * 响应 JSON 的 text 字段即识别文本。
 */
class OpenAIAsrClient(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val logger: (String) -> Unit = {},
) {
    sealed interface Result {
        data class Success(val text: String) : Result
        data class Error(val message: String) : Result
    }

    fun transcribe(wav: ByteArray, language: String = AsrClient.LANGUAGE_ZH): Result {
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "utterance.wav", wav.toRequestBody(WAV))
            .addFormDataPart("model", model)
            .apply {
                if (language.isNotBlank()) addFormDataPart("language", language)
            }
            .addFormDataPart("response_format", "json")
            .build()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(form)
            .build()

        logger("cloud asr POST, wav=${wav.size}B")
        return try {
            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    Result.Error("ASR HTTP ${resp.code}: ${body.take(120)}")
                } else {
                    val text = runCatching { JSONObject(body).optString("text") }.getOrNull()?.trim() ?: ""
                    if (text.isEmpty()) {
                        Result.Error("ASR 返回空文本")
                    } else {
                        logger("cloud asr ok, ${text.length} chars")
                        Result.Success(text)
                    }
                }
            }
        } catch (e: IOException) {
            Result.Error("ASR 网络错误: ${e.message}")
        }
    }

    companion object {
        private val WAV = "audio/wav".toMediaType()
    }
}
