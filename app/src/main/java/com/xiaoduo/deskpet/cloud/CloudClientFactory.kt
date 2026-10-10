package com.xiaoduo.deskpet.cloud

import android.content.Context
import com.xiaoduo.deskpet.net.HttpClients
import com.xiaoduo.deskpet.net.NetConfig
import com.xiaoduo.deskpet.util.PetLogger
import com.xiaoduo.deskpet.voice.client.AsrClient
import com.xiaoduo.deskpet.voice.client.TtsClient
import com.xiaoduo.deskpet.voice.pipeline.VoiceClientFactory
import com.xiaoduo.deskpet.voice.pipeline.VoiceSessionManager
import com.xiaoduo.deskpet.api.AsrContract
import com.xiaoduo.deskpet.api.TtsContract

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

    /** 按配置的云厂商分发 ASR，统一返 AsrContract.Result（供 VoiceSessionManager 透明调用）。 */
    fun asrTranscribe(context: Context, wav: ByteArray, language: String = "zh"): AsrContract.Result {
        val app = context.applicationContext
        return when (NetConfig.asrCloudProvider(app)) {
            "volcengine" -> {
                val key = NetConfig.volcSpeechKey(app)
                if (key.isBlank()) AsrContract.Result.Error("火山 ASR 未配 X-Api-Key")
                else when (val r = VolcengineAsrClient(HttpClients.shared, key, asrModel = NetConfig.volcAsrModel(app), logger = { msg -> PetLogger.i("CloudASR", msg) }).transcribe(wav, language)) {
                    is VolcengineAsrClient.Result.Success -> AsrContract.Result.Success(r.text)
                    is VolcengineAsrClient.Result.Error -> AsrContract.Result.Error(r.message)
                }
            }
            "aliyun" -> {
                val key = NetConfig.aliyunSpeechKey(app)
                if (key.isBlank()) AsrContract.Result.Error("阿里 ASR 未配 dashscope key")
                else when (val r = AliyunAsrClient(HttpClients.shared, key, model = NetConfig.aliyunAsrModel(app), logger = { msg -> PetLogger.i("CloudASR", msg) }).transcribe(wav, language)) {
                    is AliyunAsrClient.Result.Success -> AsrContract.Result.Success(r.text)
                    is AliyunAsrClient.Result.Error -> AsrContract.Result.Error(r.message)
                }
            }
            else -> when (val r = asr(app).transcribe(wav, language)) {
                is OpenAIAsrClient.Result.Success -> AsrContract.Result.Success(r.text)
                is OpenAIAsrClient.Result.Error -> AsrContract.Result.Error(r.message)
            }
        }
    }

    /** 按配置的云厂商分发 TTS，统一返 TtsContract.Result。 */
    fun ttsSynthesize(context: Context, text: String, voice: String = "default", speed: Double = 1.0): TtsContract.Result {
        val app = context.applicationContext
        return when (NetConfig.ttsCloudProvider(app)) {
            "volcengine" -> {
                val key = NetConfig.volcSpeechKey(app)
                if (key.isBlank()) TtsContract.Result.Error("火山 TTS 未配 X-Api-Key")
                else when (val r = VolcengineTtsClient(HttpClients.shared, key, NetConfig.volcTtsSpeaker(app), model = NetConfig.volcTtsModel(app), resourceId = NetConfig.volcTtsResource(app), logger = { msg -> PetLogger.i("CloudTTS", msg) }).synthesize(text, speed)) {
                    is VolcengineTtsClient.Result.Audio -> TtsContract.Result.Audio(r.pcm)
                    is VolcengineTtsClient.Result.Error -> TtsContract.Result.Error(r.message)
                }
            }
            "aliyun" -> {
                val key = NetConfig.aliyunSpeechKey(app)
                if (key.isBlank()) TtsContract.Result.Error("阿里 TTS 未配 dashscope key")
                else when (val r = AliyunTtsClient(HttpClients.shared, key, voice = NetConfig.aliyunTtsVoice(app), model = NetConfig.aliyunTtsModel(app), logger = { msg -> PetLogger.i("CloudTTS", msg) }).synthesize(text, speed)) {
                    is AliyunTtsClient.Result.Audio -> TtsContract.Result.Audio(r.pcm)
                    is AliyunTtsClient.Result.Error -> TtsContract.Result.Error(r.message)
                }
            }
            else -> when (val r = tts(app).synthesize(text, voice, speed)) {
                is OpenAITtsClient.Result.Audio -> TtsContract.Result.Audio(r.pcm)
                is OpenAITtsClient.Result.Error -> TtsContract.Result.Error(r.message)
            }
        }
    }
}
