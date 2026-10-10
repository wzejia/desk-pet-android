package com.xiaoduo.deskpet.cloud

import com.xiaoduo.deskpet.api.AsrContract
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 火山引擎（字节跳动）SAUC bigmodel ASR 客户端（WebSocket 二进制协议，纯 Kotlin，零 Android import）。
 *
 * wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream
 * 头：X-Api-Key / X-Api-Resource-Id / X-Api-Request-Id / X-Api-Sequence: -1
 * 协议（4B 帧头 + 载荷，big-endian）：
 *   客户端：FULL_CLIENT_REQUEST(JSON 配置) → AUDIO_ONLY_REQUEST(PCM 200ms/帧，正序) → 末帧(isLast)
 *   服务端：FULL_SERVER_RESPONSE(JSON：code/result 文本/payload_msg) / SERVER_ERROR
 * 帧头：byte0=0x11 / byte1=(msgType<<4)|flags / byte2=(serial<<4) / byte3=0。
 */
class VolcengineAsrClient(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val resourceId: String = "volc.seedasr.sauc.duration",
    private val asrModel: String = "bigmodel",
    private val sampleRate: Int = 16000,
    private val logger: (String) -> Unit = {},
) {
    sealed interface Result {
        data class Success(val text: String) : Result
        data class Error(val message: String) : Result
    }

    fun transcribe(wav: ByteArray, language: String = AsrContract.LANGUAGE_ZH): Result {
        val pcm = stripWavPcm(wav)
        val requestId = UUID.randomUUID().toString()
        val request = Request.Builder()
            .url(WS_URL)
            .header("X-Api-Key", apiKey)
            .header("X-Api-Resource-Id", resourceId)
            .header("X-Api-Request-Id", requestId)
            .header("X-Api-Sequence", "-1")
            .build()

        var resultText = ""
        var error: String? = null
        var openWs: WebSocket? = null
        val done = CountDownLatch(1)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                logger("volc asr WS open")
                webSocket.send(buildFullClientRequest(configJson(language)).toByteString())
                val frameBytes = (sampleRate / 10) * 2 // 200ms 音频/帧（16bit mono）
                var seq = 2
                var frames = 0
                var off = 0
                while (off < pcm.size) {
                    val end = minOf(off + frameBytes, pcm.size)
                    webSocket.send(buildAudioFrame(pcm.copyOfRange(off, end), seq, false).toByteString())
                    seq++
                    frames++
                    off = end
                }
                webSocket.send(buildAudioFrame(ByteArray(0), seq, true).toByteString())
                logger("volc asr sent $frames audio frames + last")
                openWs = webSocket
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val (msgType, flags, payload) = parseServerFrame(bytes.toByteArray())
                if (msgType == MSG_SERVER_ERROR) {
                    error = "ASR server error: " + String(payload, 0, minOf(199, payload.size - 1).coerceAtLeast(0), Charsets.UTF_8)
                    done.countDown()
                    return
                }
                if (payload.isEmpty()) return
                val json = runCatching { JSONObject(payload.decodeToString()) }.getOrNull() ?: return
                val code = json.optInt("code", 0)
                if (code != 0) {
                    error = "ASR code=$code: $json"
                    done.countDown()
                    return
                }
                val body = if (json.has("payload_msg")) json.getJSONObject("payload_msg") else json
                when (val result = body.opt("result")) {
                    is JSONObject -> {
                        val t = result.optString("text", "")
                        if (t.isNotEmpty()) resultText = t
                    }
                    is JSONArray -> {
                        val sb = StringBuilder()
                        for (i in 0 until result.length()) {
                            val t = result.optJSONObject(i)?.optString("text", "")
                            if (!t.isNullOrEmpty()) sb.append(t)
                        }
                        if (sb.isNotEmpty()) resultText = sb.toString()
                    }
                }
                if (flags and FLAG_NEGATIVE_SEQ != 0 || json.optBoolean("is_last_package", false) || body.optBoolean("is_last_package", false)) {
                    done.countDown()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                logger("volc asr WS failure: ${t.javaClass.simpleName}: ${t.message}")
                error = "ASR WS 错误: ${t.message}"
                done.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.countDown()
            }
        }

        val ws = http.newWebSocket(request, listener)
        if (!done.await(15, TimeUnit.SECONDS)) {
            ws.cancel()
            return Result.Error("ASR 超时（15s）")
        }
        openWs?.cancel()
        error?.let { return Result.Error(it) }
        if (resultText.isBlank()) return Result.Error("ASR 返回空文本")
        logger("volc asr ok, ${resultText.length} chars")
        return Result.Success(resultText.trim())
    }

    private fun configJson(language: String): JSONObject = JSONObject().apply {
        put("audio", JSONObject().apply {
            put("format", "pcm")
            put("rate", sampleRate)
            put("bits", 16)
            put("channel", 1)
            put("codec", "raw")
            put("language", if (language.startsWith(AsrContract.LANGUAGE_ZH)) "zh-CN" else language)
        })
        put("request", JSONObject().apply {
            put("model_name", asrModel)
            put("enable_itn", true)
            put("enable_punc", true)
        })
    }

    companion object {
        private const val WS_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream"

        private const val MSG_FULL_CLIENT_REQUEST = 1
        private const val MSG_AUDIO_ONLY_REQUEST = 2
        private const val MSG_FULL_SERVER_RESPONSE = 9
        private const val MSG_SERVER_ERROR = 15
        private const val FLAG_NO_SEQ = 0
        private const val FLAG_POSITIVE_SEQ = 1
        private const val FLAG_NEGATIVE_SEQ = 2
        private const val SERIAL_JSON = 1

        private fun headerBytes(msgType: Int, flags: Int, serial: Int): ByteArray =
            byteArrayOf(0x11, ((msgType shl 4) or flags).toByte(), (serial shl 4).toByte(), 0)

        /**
         * RIFF 容器剥 PCM：优先按 data 子块取；无 data 块按 44B 头处理。
         * ocr[41] 同型族（P3-WAV-FAMILY，与 AliyunAsrClient 同款）：损坏输入契约与
         * WavCodec/ROB-14 一致——一律 IllegalArgumentException（调用方映射 Result.Error），
         * 不得 IOOBE 等意外异常。变异锚：回退旧溢出代码 → WavPcmStripBoundsTest 红。
         */
        fun stripWavPcm(wav: ByteArray): ByteArray {
            val magic = "data".toByteArray(Charsets.US_ASCII)
            var i = 12
            while (i + 8 <= wav.size) {
                if (wav[i] == magic[0] && wav[i + 1] == magic[1] && wav[i + 2] == magic[2] && wav[i + 3] == magic[3]) {
                    val start = i + 8
                    val size = ByteBuffer.wrap(wav, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    require(size >= 0) { "WAV data chunk size 负值（size=$size）" }
                    // 旧 `minOf(start + size, wav.size)` 在 size 巨量时回绕负 → copyOfRange(from>to) IAE
                    // （碰巧落在契约内但语义错）；无溢出钳位：end ≤ wav.size - start（循环守卫保证右式 ≥ 0）。
                    val end = minOf(size, wav.size - start)
                    return wav.copyOfRange(start, start + end)
                }
                val chunkSize = ByteBuffer.wrap(wav, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                // 负 chunkSize 旧码推进 i 回绕负 → 下轮 wav[i] IOOBE（意外异常）→ 契约化 IAE；
                // 剩余空间装不下本块（无溢出比较）：推进后循环自然退出 → 44B 回退（原行为等价化）。
                require(chunkSize >= 0) { "WAV chunk size 负值（size=$chunkSize）" }
                if (chunkSize > wav.size - i - 8) break
                i += chunkSize + 8 + (chunkSize and 1)
            }
            return wav.copyOfRange(minOf(44, wav.size), wav.size)
        }

        fun buildFullClientRequest(config: JSONObject): ByteArray {
            val payload = config.toString().toByteArray(Charsets.UTF_8)
            return ByteBuffer.allocate(payload.size + 8).order(ByteOrder.BIG_ENDIAN).apply {
                put(headerBytes(MSG_FULL_CLIENT_REQUEST, FLAG_NO_SEQ, SERIAL_JSON))
                putInt(payload.size)
                put(payload)
            }.array()
        }

        fun buildAudioFrame(audio: ByteArray, sequence: Int, isLast: Boolean): ByteArray {
            if (isLast) {
                return ByteBuffer.allocate(audio.size + 8).order(ByteOrder.BIG_ENDIAN).apply {
                    put(headerBytes(MSG_AUDIO_ONLY_REQUEST, FLAG_NEGATIVE_SEQ, 0))
                    putInt(audio.size)
                    put(audio)
                }.array()
            }
            return ByteBuffer.allocate(audio.size + 12).order(ByteOrder.BIG_ENDIAN).apply {
                put(headerBytes(MSG_AUDIO_ONLY_REQUEST, FLAG_POSITIVE_SEQ, 0))
                putInt(sequence)
                putInt(audio.size)
                put(audio)
            }.array()
        }

        /** @return Triple<msgType, flags, payload> */
        fun parseServerFrame(data: ByteArray): Triple<Int, Int, ByteArray> {
            if (data.size < 4) return Triple(0, 0, ByteArray(0))
            val msgType = (data[1].toInt() ushr 4) and 0xF
            val flags = data[1].toInt() and 0xF
            var payload = ByteArray(0)
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            if (flags and FLAG_POSITIVE_SEQ != 0 && data.size >= 4 + 8) {
                val sizeOff = 4 + 4
                val size = buf.getInt(sizeOff)
                val start = sizeOff + 4
                payload = data.copyOfRange(start, minOf(start + size, data.size))
            } else if ((msgType == MSG_FULL_SERVER_RESPONSE || msgType == MSG_SERVER_ERROR) && data.size >= 4 + 4) {
                val start = 4 + 4
                payload = data.copyOfRange(start, minOf(start + buf.getInt(4), data.size))
            }
            return Triple(msgType, flags, payload)
        }
    }
}
