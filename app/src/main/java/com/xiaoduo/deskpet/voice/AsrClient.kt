package com.xiaoduo.deskpet.voice

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import com.xiaoduo.deskpet.api.AsrContract
import java.util.concurrent.TimeUnit

/**
 * SenseVoice ASR 客户端（纯 Kotlin 零 Android import，JVM 可测）。
 *
 * 契约依据：知识包 §2.3 + 踩坑 #13（language 必须显式 zh）/ #15（code=500 的 msg 当文档用，
 * 透传真实 msg）/ #20（空文本视为失败）。直连 10.0.18.97:9802，引擎端不验令牌（§6.3 #40）。
 * 同步阻塞执行，调用方负责切 IO 线程/协程。
 */
class AsrClient(
    private val http: OkHttpClient,
    private val endpoint: String, // 完整转写 URL，如 http://10.0.18.97:9802/audio/asr/transcribe
    private val logger: (String) -> Unit = {},
) {

    /**
     * 整段 16kHz/Int16/mono PCM 一次无状态转写（VAD 判停后送，不含尾部静音防幻觉 #14）。
     */
    fun transcribe(pcm16k: ByteArray): AsrContract.Result {
        val wav = WavCodec.pcmToWav(pcm16k, VadDetector.SAMPLE_RATE)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "utterance.wav", wav.toRequestBody(WAV_MIME))
            .addFormDataPart("language", AsrContract.LANGUAGE_ZH) // #13：auto 会把中文误判韩文/英文
            .addFormDataPart("serviceName", SERVICE_NAME)
            .build()
        val req = Request.Builder().url(endpoint).post(body).build()
        // 超时 30s（§2.3）：按调用克隆，不动共享 client
        val client = http.newBuilder()
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()
        logger("asr POST $endpoint, wav=${wav.size}B (pcm=${pcm16k.size}B)")
        val respText = try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return fail("ASR HTTP ${resp.code}: ${envelopeMsg(text)}")
                }
                text
            }
        } catch (e: IOException) {
            return fail("ASR 网络错误: ${e.message}")
        }
        val json = runCatching { JSONObject(respText) }.getOrNull()
            ?: return fail("ASR 响应非 JSON 信封: ${respText.take(120)}")
        if (json.optInt("code", -1) != 200) {
            // #15：code=500 的 msg 常带合法取值等真实原因，原样透传
            return fail("ASR 失败: ${json.optString("msg", "未知错误")}")
        }
        val text = json.optJSONObject("data")?.optString("text")?.trim().orEmpty()
        if (text.isEmpty()) {
            // #20：空文本不当异常也不当成功——显式失败让上层回退，不静默
            return fail("ASR 返回空文本（无语音或全噪声）")
        }
        logger("asr ok, text=${text.length} chars")
        return AsrContract.Result.Success(text)
    }

    private fun fail(msg: String): AsrContract.Result {
        logger(msg)
        return AsrContract.Result.Error(msg)
    }

    private fun envelopeMsg(body: String): String =
        runCatching { JSONObject(body).optString("msg") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: body.take(120)

    companion object {
        const val SERVICE_NAME = "sensevoice"
        const val TIMEOUT_S = 30L
        private val WAV_MIME = "audio/wav".toMediaType()
    }
}
