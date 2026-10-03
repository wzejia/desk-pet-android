package com.xiaoduo.deskpet.net

import android.content.Context
import com.xiaoduo.deskpet.BuildConfig
import java.net.URI

/**
 * 端点派生纯逻辑（零 Android import，JVM 可测）。
 * 契约来源：知识包 §6.1 / §2.2（Agent 必须走 443 nginx 反代，不能直连 :8080）。
 */
object NetEndpoints {
    /** 登录：POST {platformBase}/login */
    fun loginUrl(platformBase: String): String = platformBase.trimEnd('/') + "/login"

    /** AG-UI：443 反代 https://{host}/ai-server/agent/stream（从 platformBase 取 host 换 https/443） */
    fun agentStreamUrl(platformBase: String): String {
        val host = runCatching { URI(platformBase.trim()).host }.getOrNull()
        return if (host.isNullOrBlank()) {
            platformBase.trimEnd('/') + "/ai-server/agent/stream"
        } else {
            "https://$host/ai-server/agent/stream"
        }
    }

    /** 文件上传：443 反代 https://{host}/ai-server/common/file/upload（与 [agentStreamUrl] 同范式） */
    fun uploadUrl(platformBase: String): String {
        val host = runCatching { URI(platformBase.trim()).host }.getOrNull()
        return if (host.isNullOrBlank()) {
            platformBase.trimEnd('/') + "/ai-server/common/file/upload"
        } else {
            "https://$host/ai-server/common/file/upload"
        }
    }

    /** S16 agent 列表：443 反代 https://{host}/ai-server/agent/list（2026-08-25 实测可用，与 [agentStreamUrl] 同范式） */
    fun agentListUrl(platformBase: String): String {
        val host = runCatching { URI(platformBase.trim()).host }.getOrNull()
        return if (host.isNullOrBlank()) {
            platformBase.trimEnd('/') + "/ai-server/agent/list"
        } else {
            "https://$host/ai-server/agent/list"
        }
    }

    /** TTS 流式：POST {ttsBase}/audio/tts/stream */
    fun ttsStreamUrl(ttsBase: String): String = ttsBase.trimEnd('/') + "/audio/tts/stream"

    /** TTS 非流式兜底：POST {ttsBase}/audio/tts/synthesize */
    fun ttsSynthesizeUrl(ttsBase: String): String = ttsBase.trimEnd('/') + "/audio/tts/synthesize"
}

/**
 * 网络配置（Spec 数据模型 NetConfig）：五字段 SharedPreferences 可编辑 + 内置默认。
 * 默认值 = 云端开箱即用（2026-08-13 拍板：三模态默认 cloud，asr/tts=aliyun、llm=openai 兼容；
 * 密钥回退链 SP → BuildConfig 内置（secrets/cloud.properties 打包注入，不入 git）→ 空串）。
 * 内网部署只需在设置页把三模态切回 platform（SP 显式存储，优先于默认）。
 * password 无默认值（云os模式免登录）；严禁写入日志（评审抽查点）。
 */
object NetConfig {
    private const val NAME = "xiaoduo_net"

    const val KEY_PLATFORM_BASE = "platform_base"
    const val KEY_ASR_BASE = "asr_base"
    const val KEY_TTS_BASE = "tts_base"
    const val KEY_ACCOUNT = "account"
    const val KEY_PASSWORD = "password"
    const val KEY_MODE = "mode"                       // 兼容旧（已废弃）
    const val KEY_LLM_MODE = "llm_mode"               // LLM 独立：platform | cloud
    const val KEY_ASR_MODE = "asr_mode"               // ASR 独立
    const val KEY_TTS_MODE = "tts_mode"               // TTS 独立
    const val KEY_CLOUD_BASE = "cloud_base"            // OpenAI 兼容 base，如 https://api.openai.com/v1
    const val KEY_CLOUD_KEY = "cloud_key"              // API Key（sk-xxx）
    const val KEY_CLOUD_LLM_MODEL = "cloud_llm_model"
    const val KEY_CLOUD_ASR_MODEL = "cloud_asr_model"
    const val KEY_CLOUD_TTS_MODEL = "cloud_tts_model"
    const val KEY_CLOUD_TTS_VOICE = "cloud_tts_voice"
    const val KEY_LLM_PROVIDER = "llm_cloud_provider"   // 每模态云厂商：openai | aliyun | volcengine
    const val KEY_ASR_PROVIDER = "asr_cloud_provider"
    const val KEY_TTS_PROVIDER = "tts_cloud_provider"
    const val KEY_VOLC_SPEECH_KEY = "volc_speech_key"    // 火山语音 X-Api-Key
    const val KEY_VOLC_TTS_SPEAKER = "volc_tts_speaker"  // 火山 TTS 音色
    const val KEY_VOLC_ASR_MODEL = "volc_asr_model"      // 火山 ASR 模型名（bigmodel）
    const val KEY_VOLC_TTS_MODEL = "volc_tts_model"      // 火山 TTS 模型（seed-tts-2.0-standard）
    const val KEY_VOLC_TTS_RESOURCE = "volc_tts_resource" // 火山 TTS resource_id（seed-tts-2.0）
    const val KEY_ALIYUN_SPEECH_KEY = "aliyun_speech_key" // 阿里 dashscope key（ASR/TTS 共用）
    const val KEY_ALIYUN_TTS_VOICE = "aliyun_tts_voice"   // 阿里 cosyvoice 音色
    const val KEY_ALIYUN_TTS_MODEL = "aliyun_tts_model"   // 阿里 TTS 模型（cosyvoice-v2 / qwen-audio-3.0-tts-flash）
    const val KEY_ALIYUN_ASR_MODEL = "aliyun_asr_model"   // 阿里 ASR 模型（paraformer-realtime-v2 / qwen-audio-3.0-asr-flash-streaming）

    const val DEFAULT_PLATFORM_BASE = "http://10.0.18.166:8080"
    const val DEFAULT_ASR_BASE = "http://10.0.18.97:9802/audio/asr/transcribe"
    const val DEFAULT_TTS_BASE = "http://10.0.18.97:9803"
    const val DEFAULT_ACCOUNT = "admin"
    const val MODE_PLATFORM = "platform"
    const val MODE_CLOUD = "cloud"
    const val DEFAULT_CLOUD_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    const val DEFAULT_CLOUD_LLM_MODEL = "qwen-plus"
    const val DEFAULT_CLOUD_ASR_MODEL = "whisper-1"
    const val DEFAULT_CLOUD_TTS_MODEL = TtsVoiceDefaults.OPENAI_MODEL
    const val DEFAULT_CLOUD_TTS_VOICE = TtsVoiceDefaults.OPENAI_VOICE

    /** 打包内置密钥（BuildConfig ← secrets/cloud.properties，gitignored；空串=无内置）。 */
    private val BUILTIN_CLOUD_KEY: String get() = BuildConfig.CLOUD_KEY
    private val BUILTIN_ALIYUN_KEY: String get() = BuildConfig.ALIYUN_SPEECH_KEY.ifBlank { BuildConfig.CLOUD_KEY }
    private val BUILTIN_VOLC_KEY: String get() = BuildConfig.VOLC_SPEECH_KEY

    private fun sp(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun platformBase(context: Context): String =
        sp(context).getString(KEY_PLATFORM_BASE, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PLATFORM_BASE

    fun asrBase(context: Context): String =
        sp(context).getString(KEY_ASR_BASE, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_ASR_BASE

    fun ttsBase(context: Context): String =
        sp(context).getString(KEY_TTS_BASE, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_TTS_BASE

    fun account(context: Context): String =
        sp(context).getString(KEY_ACCOUNT, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_ACCOUNT

    /** 密码无默认值，未设置返回空串。 */
    fun password(context: Context): String =
        sp(context).getString(KEY_PASSWORD, null) ?: ""

    /** 服务模式（各模态独立）：cloud（云端，默认）| platform（内网平台，SP 显式存储优先）。 */
    private fun modeOf(context: Context, key: String): String {
        // 显式存储值优先（用户在设置页选过）；缺失/非法值 → 默认 cloud（2026-08-13 起云端开箱即用）
        return when (sp(context).getString(key, null)) {
            MODE_CLOUD -> MODE_CLOUD
            MODE_PLATFORM -> MODE_PLATFORM
            else -> MODE_CLOUD
        }
    }

    fun llmMode(context: Context): String = modeOf(context, KEY_LLM_MODE)
    fun asrMode(context: Context): String = modeOf(context, KEY_ASR_MODE)
    fun ttsMode(context: Context): String = modeOf(context, KEY_TTS_MODE)

    fun llmIsCloud(context: Context): Boolean = llmMode(context) == MODE_CLOUD
    fun asrIsCloud(context: Context): Boolean = asrMode(context) == MODE_CLOUD
    fun ttsIsCloud(context: Context): Boolean = ttsMode(context) == MODE_CLOUD

    /** @deprecated 旧全局 mode，仅迁移用。 */
    fun mode(context: Context): String =
        sp(context).getString(KEY_MODE, null)?.takeIf { it == MODE_CLOUD }
            ?: sp(context).getString(KEY_LLM_MODE, null)?.takeIf { it == MODE_CLOUD } ?: MODE_PLATFORM

    fun isCloud(context: Context): Boolean = llmIsCloud(context)

    fun cloudBase(context: Context): String =
        sp(context).getString(KEY_CLOUD_BASE, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_CLOUD_BASE

    /** API Key：SP 未设/清空 → 内置密钥回退。严禁入日志。 */
    fun cloudKey(context: Context): String =
        sp(context).getString(KEY_CLOUD_KEY, null)?.takeIf { it.isNotBlank() } ?: BUILTIN_CLOUD_KEY

    fun cloudLlmModel(context: Context): String =
        sp(context).getString(KEY_CLOUD_LLM_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_CLOUD_LLM_MODEL

    fun cloudAsrModel(context: Context): String =
        sp(context).getString(KEY_CLOUD_ASR_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_CLOUD_ASR_MODEL

    fun cloudTtsModel(context: Context): String =
        sp(context).getString(KEY_CLOUD_TTS_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_CLOUD_TTS_MODEL

    fun cloudTtsVoice(context: Context): String =
        sp(context).getString(KEY_CLOUD_TTS_VOICE, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_CLOUD_TTS_VOICE

    /** 各模态的云厂商（openai|aliyun|volcengine）。 */
    fun llmCloudProvider(context: Context): String =
        sp(context).getString(KEY_LLM_PROVIDER, null)?.takeIf { it.isNotBlank() } ?: "openai"
    fun asrCloudProvider(context: Context): String =
        sp(context).getString(KEY_ASR_PROVIDER, null)?.takeIf { it.isNotBlank() } ?: "aliyun"
    fun ttsCloudProvider(context: Context): String =
        sp(context).getString(KEY_TTS_PROVIDER, null)?.takeIf { it.isNotBlank() } ?: "aliyun"

    /** 火山语音凭证（ASR/TTS 共用 X-Api-Key）：SP 未设 → 内置回退。严禁入日志。 */
    fun volcSpeechKey(context: Context): String =
        sp(context).getString(KEY_VOLC_SPEECH_KEY, null)?.takeIf { it.isNotBlank() } ?: BUILTIN_VOLC_KEY
    fun volcTtsSpeaker(context: Context): String =
        sp(context).getString(KEY_VOLC_TTS_SPEAKER, null)?.takeIf { it.isNotBlank() } ?: TtsVoiceDefaults.VOLC_VOICE

    /** 阿里 dashscope 凭证（ASR/TTS 共用）：SP 未设 → 内置回退（未单独配置时共用 CLOUD_KEY）。严禁入日志。 */
    fun aliyunSpeechKey(context: Context): String =
        sp(context).getString(KEY_ALIYUN_SPEECH_KEY, null)?.takeIf { it.isNotBlank() } ?: BUILTIN_ALIYUN_KEY
    fun aliyunTtsVoice(context: Context): String =
        sp(context).getString(KEY_ALIYUN_TTS_VOICE, null)?.takeIf { it.isNotBlank() } ?: TtsVoiceDefaults.ALIYUN_VOICE
    fun aliyunTtsModel(context: Context): String =
        sp(context).getString(KEY_ALIYUN_TTS_MODEL, null)?.takeIf { it.isNotBlank() } ?: TtsVoiceDefaults.ALIYUN_MODEL
    fun aliyunAsrModel(context: Context): String =
        sp(context).getString(KEY_ALIYUN_ASR_MODEL, null)?.takeIf { it.isNotBlank() } ?: "qwen-audio-3.0-asr-flash-streaming"

    /** 火山模型/资源（与端点耦合，换需了解火山 SAUC/TTS 服务参数）。 */
    fun volcAsrModel(context: Context): String =
        sp(context).getString(KEY_VOLC_ASR_MODEL, null)?.takeIf { it.isNotBlank() } ?: "bigmodel"
    fun volcTtsModel(context: Context): String =
        sp(context).getString(KEY_VOLC_TTS_MODEL, null)?.takeIf { it.isNotBlank() } ?: TtsVoiceDefaults.VOLC_MODEL
    fun volcTtsResource(context: Context): String =
        sp(context).getString(KEY_VOLC_TTS_RESOURCE, null)?.takeIf { it.isNotBlank() } ?: "seed-tts-2.0"

    // TODO(SEC-3 BLOCKED 2026-10-03)：password/cloud_key/volc_speech_key/aliyun_speech_key 仍为
    //  SharedPreferences 明文。迁移 EncryptedSharedPreferences 需新增 androidx.security:security-crypto
    //  依赖（项目规矩「不新增依赖须报批」）→ 本轮未做；自封装 Keystore AES 属新机制，需立项。
    //  迁移时必须兼容板端存量 prefs：首启读旧明文 → 写加密 → 清旧键（迁移逻辑单测）。
    //  已落地最小缓解：TokenEntry.toString 脱敏（防日志/调试泄露），见 TokenStore.kt。
    fun saveAll(
        context: Context,
        platformBase: String,
        asrBase: String,
        ttsBase: String,
        account: String,
        password: String,
        mode: String = MODE_PLATFORM,
        llmMode: String = mode,
        asrMode: String = mode,
        ttsMode: String = mode,
        cloudBase: String = "",
        cloudKey: String = "",
        cloudLlmModel: String = "",
        cloudAsrModel: String = "",
        cloudTtsModel: String = "",
        cloudTtsVoice: String = "",
        llmCloudProvider: String = "openai",
        asrCloudProvider: String = "openai",
        ttsCloudProvider: String = "openai",
        volcSpeechKey: String = "",
        volcTtsSpeaker: String = "",
        volcAsrModel: String = "",
        volcTtsModel: String = "",
        volcTtsResource: String = "",
        aliyunSpeechKey: String = "",
        aliyunTtsVoice: String = "",
        aliyunTtsModel: String = "",
        aliyunAsrModel: String = "",
    ) {
        sp(context).edit()
            .putString(KEY_PLATFORM_BASE, platformBase.trim())
            .putString(KEY_ASR_BASE, asrBase.trim())
            .putString(KEY_TTS_BASE, ttsBase.trim())
            .putString(KEY_ACCOUNT, account.trim())
            .putString(KEY_PASSWORD, password)
            .putString(KEY_LLM_MODE, if (llmMode == MODE_CLOUD) MODE_CLOUD else MODE_PLATFORM)
            .putString(KEY_ASR_MODE, if (asrMode == MODE_CLOUD) MODE_CLOUD else MODE_PLATFORM)
            .putString(KEY_TTS_MODE, if (ttsMode == MODE_CLOUD) MODE_CLOUD else MODE_PLATFORM)
            .putString(KEY_CLOUD_BASE, cloudBase.trim())
            .putString(KEY_CLOUD_KEY, cloudKey)
            .putString(KEY_CLOUD_LLM_MODEL, cloudLlmModel.trim())
            .putString(KEY_CLOUD_ASR_MODEL, cloudAsrModel.trim())
            .putString(KEY_CLOUD_TTS_MODEL, cloudTtsModel.trim())
            .putString(KEY_CLOUD_TTS_VOICE, cloudTtsVoice.trim())
            .putString(KEY_LLM_PROVIDER, llmCloudProvider.trim())
            .putString(KEY_ASR_PROVIDER, asrCloudProvider.trim())
            .putString(KEY_TTS_PROVIDER, ttsCloudProvider.trim())
            .putString(KEY_VOLC_SPEECH_KEY, volcSpeechKey)
            .putString(KEY_VOLC_TTS_SPEAKER, volcTtsSpeaker.trim())
            .putString(KEY_VOLC_ASR_MODEL, volcAsrModel.trim())
            .putString(KEY_VOLC_TTS_MODEL, volcTtsModel.trim())
            .putString(KEY_VOLC_TTS_RESOURCE, volcTtsResource.trim())
            .putString(KEY_ALIYUN_SPEECH_KEY, aliyunSpeechKey)
            .putString(KEY_ALIYUN_TTS_VOICE, aliyunTtsVoice.trim())
            .putString(KEY_ALIYUN_TTS_MODEL, aliyunTtsModel.trim())
            .putString(KEY_ALIYUN_ASR_MODEL, aliyunAsrModel.trim())
            .apply()
    }
}
