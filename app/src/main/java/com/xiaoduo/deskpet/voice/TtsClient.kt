package com.xiaoduo.deskpet.voice

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import com.xiaoduo.deskpet.api.TtsContract
import java.util.Base64

/**
 * CosyVoice TTS 客户端（纯 Kotlin 零 Android import，JVM 可测）。
 *
 * 契约依据：知识包 §2.4 + 踩坑 #2（直连 9803 勿经 166 转发）/ #3（text 数组逐句一帧）/
 * #7（NDJSON 帧 audioData base64 WAV 剥头拼 PCM；先缓冲全帧再播，防残帧+兜底重复）/
 * 预校验错误走普通 JSON 信封（不进 NDJSON）。
 *
 * 降级链：流式主路径 → 404 永久记住走非流式（[StreamSupport] 持久化）/ 流式中途错 →
 * 非流式兜底一次。单句失败由调用方降级纯文字 reveal、后续句继续（坑 #48 部分失败语义）。
 * 同步阻塞执行，调用方负责切 IO 线程/协程。
 */
class TtsClient(
    private val http: OkHttpClient,
    private val streamUrl: String,      // {base}/audio/tts/stream
    private val synthesizeUrl: String,  // {base}/audio/tts/synthesize
    private val streamSupport: StreamSupport,
    private val speaker: String = DEFAULT_SPEAKER,
    private val speed: Double = DEFAULT_SPEED,
    private val logger: (String) -> Unit = {},
) {

    /** 流式 404 永久记忆抽象（Android 侧 SharedPreferences 适配，测试侧内存实现）。 */
    interface StreamSupport {
        fun isUnsupported(): Boolean
        fun markUnsupported()
    }

    private sealed interface StreamOutcome {
        data class Ok(val pcm: ByteArray, val frames: Int) : StreamOutcome
        data object NotFound : StreamOutcome
        data class Failure(val message: String) : StreamOutcome
    }

    /** 单句合成：流式主路径，失败按降级链走非流式兜底。 */
    fun synthesize(sentence: String): TtsContract.Result {
        if (!streamSupport.isUnsupported()) {
            // ROB-14：流路径 RuntimeException（url 构造 IAE / body 异常形态 / 帧解析越界等）
            // 就地转 Failure——保住「失败走非流式兜底一次」的文档化降级链，不击穿。
            when (val r = runCatching { synthesizeStream(sentence) }.getOrElse { e ->
                StreamOutcome.Failure("TTS stream 异常: ${e.javaClass.simpleName}: ${e.message}")
            }) {
                is StreamOutcome.Ok -> {
                    logger("tts stream ok, ${r.frames} frame(s), pcm=${r.pcm.size}B")
                    return TtsContract.Result.Audio(r.pcm)
                }
                StreamOutcome.NotFound -> {
                    logger("tts stream 404, 永久切非流式兜底")
                    streamSupport.markUnsupported()
                }
                is StreamOutcome.Failure ->
                    logger("tts stream failed, 非流式兜底一次: ${r.message}")
            }
        }
        // ROB-14：兜底路径同样上 RuntimeException 边界（Url 构造畸形等），恒守 Result 契约
        return try {
            synthesizeOnce(sentence)
        } catch (e: RuntimeException) {
            fail("TTS 请求异常: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 流式：body {"text":[sentence]（数组每元素一帧）, serviceName, speaker, speed}。
     * NDJSON 逐帧收，**先缓冲全帧**剥头拼 PCM，done 帧终止（坑 #7：残帧+兜底重复）。
     */
    private fun synthesizeStream(sentence: String): StreamOutcome {
        val pcm = ByteArrayOutputStream()
        var frames = 0
        val req = Request.Builder().url(streamUrl)
            .post(
                JSONObject()
                    .put("text", JSONArray().put(sentence))
                    .put("serviceName", SERVICE_NAME)
                    .put("speaker", speaker)
                    .put("speed", speed)
                    .toString()
                    .toRequestBody(JSON),
            )
            .build()
        logger("tts stream POST $streamUrl, ${sentence.length} chars")
        try {
            http.newCall(req).execute().use { resp ->
                if (resp.code == 404) return StreamOutcome.NotFound
                if (!resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty()
                    return StreamOutcome.Failure("TTS stream HTTP ${resp.code}: ${envelopeMsg(text)}")
                }
                // ROB-14：body! 可 NPE——安全调用，null 视为流失败（走兜底链）
                val source = resp.body?.source()
                    ?: return StreamOutcome.Failure("TTS stream 响应无 body")
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    when {
                        json.has("audioData") -> {
                            val wav = Base64.getDecoder().decode(json.getString("audioData"))
                            pcm.write(WavCodec.stripWavHeader(wav))
                            frames++
                        }
                        json.optString("type") == "done" -> {
                            if (pcm.size() == 0) return StreamOutcome.Failure("TTS 返回空音频（done 但 0 帧）")
                            return StreamOutcome.Ok(pcm.toByteArray(), frames)
                        }
                        json.optString("type") == "error" ->
                            return StreamOutcome.Failure(
                                "TTS stream error: code=${json.optInt("code")} ${json.optString("msg")}",
                            )
                        // 预校验错误走普通 JSON 信封（不进 NDJSON），msg 原样透传
                        json.has("code") && json.optInt("code") != 200 ->
                            return StreamOutcome.Failure("TTS 预校验失败: ${json.optString("msg", "未知错误")}")
                    }
                }
            }
        } catch (e: IOException) {
            return StreamOutcome.Failure("TTS stream 网络错误: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return StreamOutcome.Failure("TTS 帧解析失败: ${e.message}")
        } catch (e: RuntimeException) {
            // ROB-14：getString 类型不符（JSONException）等剩余 RuntimeException 转 Failure
            return StreamOutcome.Failure("TTS stream 异常: ${e.javaClass.simpleName}: ${e.message}")
        }
        // 无 done 帧的干净断流（166 转发 30s 截断同形态）——显式报错，不伪装成功
        return StreamOutcome.Failure("TTS 流提前结束（未见 done 帧，已收 $frames 帧丢弃）")
    }

    /** 非流式兜底：text 传字符串，信封 code==200 取 data.audioData（base64 WAV 剥头）。 */
    private fun synthesizeOnce(sentence: String): TtsContract.Result {
        val req = Request.Builder().url(synthesizeUrl)
            .post(
                JSONObject()
                    .put("text", sentence)
                    .put("serviceName", SERVICE_NAME)
                    .put("speaker", speaker)
                    .put("speed", speed)
                    .toString()
                    .toRequestBody(JSON),
            )
            .build()
        logger("tts synthesize POST $synthesizeUrl, ${sentence.length} chars")
        val respText = try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return fail("TTS HTTP ${resp.code}: ${envelopeMsg(text)}")
                }
                text
            }
        } catch (e: IOException) {
            return fail("TTS 网络错误: ${e.message}")
        }
        val json = runCatching { JSONObject(respText) }.getOrNull()
            ?: return fail("TTS 响应非 JSON 信封: ${respText.take(120)}")
        if (json.optInt("code", -1) != 200) {
            return fail("TTS 失败: ${json.optString("msg", "未知错误")}")
        }
        val b64 = json.optJSONObject("data")?.optString("audioData").orEmpty()
        if (b64.isEmpty()) return fail("TTS 返回空 audioData")
        val pcm = try {
            WavCodec.stripWavHeader(Base64.getDecoder().decode(b64))
        } catch (e: IllegalArgumentException) {
            return fail("TTS 音频解析失败: ${e.message}")
        }
        logger("tts synthesize ok, pcm=${pcm.size}B")
        return TtsContract.Result.Audio(pcm)
    }

    private fun fail(msg: String): TtsContract.Result {
        logger(msg)
        return TtsContract.Result.Error(msg)
    }

    private fun envelopeMsg(body: String): String =
        runCatching { JSONObject(body).optString("msg") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: body.take(120)

    companion object {
        const val SERVICE_NAME = "cosyvoice3"
        const val DEFAULT_SPEAKER = "default"
        const val DEFAULT_SPEED = 1.0
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
