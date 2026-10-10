package com.xiaoduo.deskpet.net

import com.xiaoduo.deskpet.voice.wakeack.WakeAckSelector

/**
 * TTS 音色默认值单一真理源（S9-2）。
 *
 * NetConfig 的默认回退与 WakeAckSelector 的「恰等于厂商默认三元组」判定共用这组常量——
 * 若两处各写字面量，NetConfig 改默认后选择器静默错配（正是本 S9 修复的 bug 的镜像）。
 * 值从 NetConfig 2026-08-17 现状原样提取（行为零变）。零 Android import，JVM 可测。
 *
 * 跨语言同步义务：[TEXT] 文案与 tools/wozai 合成脚本、assets/wozai-{vendor}.wav 三厂商资产
 * 必须保持一致（S9-1 管辖）。
 */
object TtsVoiceDefaults {

    const val PROVIDER_OPENAI = "openai"
    const val PROVIDER_ALIYUN = "aliyun"
    const val PROVIDER_VOLCENGINE = "volcengine"

    const val OPENAI_MODEL = "gpt-4o-mini-tts"
    const val OPENAI_VOICE = "alloy"

    const val ALIYUN_MODEL = "qwen-audio-3.0-tts-flash"
    const val ALIYUN_VOICE = "longanfengyue"

    const val VOLC_MODEL = "seed-tts-2.0-standard"
    /** 火山的 voice 语义 = speaker（NetConfig.volcTtsSpeaker）。 */
    const val VOLC_VOICE = "zh_female_qingxinnvsheng_uranus_bigtts"

    /** 唤醒应答合成文案（云内置资产与运行时预取同文同 speed=1.0）。 */
    const val TEXT = "我在"

    /** 路由后 provider → 默认 model（未知按 openai，镜像 CloudClientFactory else 分支）。 */
    fun model(provider: String): String = when (provider) {
        PROVIDER_ALIYUN -> ALIYUN_MODEL
        PROVIDER_VOLCENGINE -> VOLC_MODEL
        else -> OPENAI_MODEL
    }

    /** 路由后 provider → 默认 voice（未知按 openai）。 */
    fun voice(provider: String): String = when (provider) {
        PROVIDER_ALIYUN -> ALIYUN_VOICE
        PROVIDER_VOLCENGINE -> VOLC_VOICE
        else -> OPENAI_VOICE
    }
}
