package com.xiaoduo.deskpet.cloud

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.Base64
import java.util.UUID

/**
 * 火山豆包语音合成 TTS 客户端（HTTP NDJSON，纯 Kotlin，零 Android import）。
 *
 * POST https://openspeech.bytedance.com/api/v3/tts/unidirectional
 * 头：X-Api-Key、X-Api-Resource-Id(seed-tts-2.0)、X-Api-Request-Id
 * 体：req_params{text, speaker, audio_params{format:pcm, sample_rate, speech_rate}}
 * 响应：NDJSON 每行 {code, data:"<base64 PCM>"}，末帧 code=20000000(OK，无 data)。
 *
 * 协议移植自网关已验证的 Python tts_volcengine.py。
 */
class VolcengineTtsClient(
    private val http: OkHttpClient,
    private val apiKey: String,          // X-Api-Key（语音服务 UUID）
    private val speaker: String,         // 音色 ID
    private val model: String = "seed-tts-2.0-standard",
    private val resourceId: String = "seed-tts-2.0",
    private val sampleRate: Int = 24000,
    private val logger: (String) -> Unit = {},
) {
    sealed interface Result {
        data class Audio(val pcm: ByteArray) : Result
        data class Error(val message: String) : Result
    }

    fun synthesize(text: String, speed: Double = 1.0): Result {
        // speech_rate: [-50,100]，100=2x，-50=0.5x；speed=1.0 → 0
        val speechRate = ((speed - 1.0) * 100).toInt().coerceIn(-50, 100)
        val payload = JSONObject().apply {
            put("req_params", JSONObject().apply {
                put("text", text)
                put("model", model)
                put("speaker", speaker)
                put("audio_params", JSONObject().apply {
                    put("format", "pcm")
                    put("sample_rate", sampleRate)
                    put("speech_rate", speechRate)
                })
            })
        }
        val req = Request.Builder()
            .url(URL)
            .header("X-Api-Key", apiKey)
            .header("X-Api-Resource-Id", resourceId)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .post(payload.toString().toRequestBody(JSON))
            .build()
        logger("volc tts POST, ${text.length} chars")
        val pcm = java.io.ByteArrayOutputStream()
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return Result.Error("TTS HTTP ${resp.code}: ${resp.body?.string()?.take(120)}")
                BufferedReader(InputStreamReader(resp.body!!.byteStream())).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        val code = obj.optInt("code", 0)
                        val b64 = obj.optString("data", "")
                        if (b64.isEmpty()) continue // OK 末帧（code=20000000）等跳过
                        if (code != 0 && code != 20000000) return Result.Error("TTS code=$code: ${obj.optString("message")}")
                        pcm.write(Base64.getDecoder().decode(b64))
                    }
                }
                if (pcm.size() == 0) Result.Error("TTS 返回空音频")
                else { logger("volc tts ok, ${pcm.size()}B pcm"); Result.Audio(pcm.toByteArray()) }
            }
        } catch (e: IOException) {
            Result.Error("TTS 网络错误: ${e.message}")
        }
    }

    companion object {
        private const val URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
