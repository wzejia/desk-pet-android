package com.xiaoduo.deskpet.cloud

import android.content.Context
import com.xiaoduo.deskpet.net.HttpClients
import com.xiaoduo.deskpet.net.NetConfig
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.voice.AsrClient
import com.xiaoduo.deskpet.voice.TtsClient

/**
 * 云端（OpenAI 兼容）客户端装配层：从 NetConfig 读 base/key/model，
 * 复用全局 OkHttp 单例（NO_PROXY + 白名单自签）。
 *
 * 云端模式（mode=cloud）下，ChatViewModel / VoiceClientFactory 用这里创建的客户端
 * 直连云厂商 REST API；平台模式（mode=platform）不受影响，仍走 AguiClient/AsrClient/TtsClient。
 */
object CloudClientFactory {

    fun chat(context: Context): OpenAIChatClient {
        val app = context.applicationContext
        return OpenAIChatClient(
            http = HttpClients.shared,
            baseUrl = NetConfig.cloudBase(app),
            apiKey = NetConfig.cloudKey(app),
            model = NetConfig.cloudLlmModel(app),
            logger = { msg -> PetLogger.i("CloudLLM", msg) },
        )
    }

    fun asr(context: Context): OpenAIAsrClient {
        val app = context.applicationContext
        return OpenAIAsrClient(
            http = HttpClients.shared,
            baseUrl = NetConfig.cloudBase(app),
            apiKey = NetConfig.cloudKey(app),
            model = NetConfig.cloudAsrModel(app),
            logger = { msg -> PetLogger.i("CloudASR", msg) },
        )
    }

    fun tts(context: Context): OpenAITtsClient {
        val app = context.applicationContext
        return OpenAITtsClient(
            http = HttpClients.shared,
            baseUrl = NetConfig.cloudBase(app),
            apiKey = NetConfig.cloudKey(app),
            model = NetConfig.cloudTtsModel(app),
            defaultVoice = NetConfig.cloudTtsVoice(app),
            logger = { msg -> PetLogger.i("CloudTTS", msg) },
        )
    }

    /** 按配置的云厂商分发 ASR，统一返 AsrClient.Result（供 VoiceSessionManager 透明调用）。 */
    fun asrTranscribe(context: Context, wav: ByteArray, language: String = "zh"): AsrClient.Result {
        val app = context.applicationContext
        return when (NetConfig.asrCloudProvider(app)) {
            "volcengine" -> {
                val key = NetConfig.volcSpeechKey(app)
                if (key.isBlank()) AsrClient.Result.Error("火山 ASR 未配 X-Api-Key")
                else when (val r = VolcengineAsrClient(HttpClients.shared, key, asrModel = NetConfig.volcAsrModel(app), logger = { msg -> PetLogger.i("CloudASR", msg) }).transcribe(wav, language)) {
                    is VolcengineAsrClient.Result.Success -> AsrClient.Result.Success(r.text)
                    is VolcengineAsrClient.Result.Error -> AsrClient.Result.Error(r.message)
                }
            }
            "aliyun" -> {
                val key = NetConfig.aliyunSpeechKey(app)
                if (key.isBlank()) AsrClient.Result.Error("阿里 ASR 未配 dashscope key")
                else when (val r = AliyunAsrClient(HttpClients.shared, key, model = NetConfig.aliyunAsrModel(app), logger = { msg -> PetLogger.i("CloudASR", msg) }).transcribe(wav, language)) {
                    is AliyunAsrClient.Result.Success -> AsrClient.Result.Success(r.text)
                    is AliyunAsrClient.Result.Error -> AsrClient.Result.Error(r.message)
                }
            }
            else -> when (val r = asr(app).transcribe(wav, language)) {
                is OpenAIAsrClient.Result.Success -> AsrClient.Result.Success(r.text)
                is OpenAIAsrClient.Result.Error -> AsrClient.Result.Error(r.message)
            }
        }
    }

    /** 按配置的云厂商分发 TTS，统一返 TtsClient.Result。 */
    fun ttsSynthesize(context: Context, text: String, voice: String = "default", speed: Double = 1.0): TtsClient.Result {
        val app = context.applicationContext
        return when (NetConfig.ttsCloudProvider(app)) {
            "volcengine" -> {
                val key = NetConfig.volcSpeechKey(app)
                if (key.isBlank()) TtsClient.Result.Error("火山 TTS 未配 X-Api-Key")
                else when (val r = VolcengineTtsClient(HttpClients.shared, key, NetConfig.volcTtsSpeaker(app), model = NetConfig.volcTtsModel(app), resourceId = NetConfig.volcTtsResource(app), logger = { msg -> PetLogger.i("CloudTTS", msg) }).synthesize(text, speed)) {
                    is VolcengineTtsClient.Result.Audio -> TtsClient.Result.Audio(r.pcm)
                    is VolcengineTtsClient.Result.Error -> TtsClient.Result.Error(r.message)
                }
            }
            "aliyun" -> {
                val key = NetConfig.aliyunSpeechKey(app)
                if (key.isBlank()) TtsClient.Result.Error("阿里 TTS 未配 dashscope key")
                else when (val r = AliyunTtsClient(HttpClients.shared, key, voice = NetConfig.aliyunTtsVoice(app), model = NetConfig.aliyunTtsModel(app), logger = { msg -> PetLogger.i("CloudTTS", msg) }).synthesize(text, speed)) {
                    is AliyunTtsClient.Result.Audio -> TtsClient.Result.Audio(r.pcm)
                    is AliyunTtsClient.Result.Error -> TtsClient.Result.Error(r.message)
                }
            }
            else -> when (val r = tts(app).synthesize(text, voice, speed)) {
                is OpenAITtsClient.Result.Audio -> TtsClient.Result.Audio(r.pcm)
                is OpenAITtsClient.Result.Error -> TtsClient.Result.Error(r.message)
            }
        }
    }
}
