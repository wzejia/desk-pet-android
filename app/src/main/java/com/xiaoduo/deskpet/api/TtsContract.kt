package com.xiaoduo.deskpet.api


/** TTS 统一结果契约（spec §4.2 一刀：自 voice.TtsClient 嵌套类型原样下沉，2026-10-10；
 *  纯 Kotlin 零依赖，voice/cloud 两侧共同消费面）。 */
object TtsContract {

    sealed interface Result {
        /** 成功：24kHz/Int16/mono 裸 PCM（多帧 WAV 已剥头拼接）。 */
        data class Audio(val pcm: ByteArray) : Result

        /** 失败：message 透传真实原因（平台 msg / 网络异常）。 */
        data class Error(val message: String) : Result
    }
}
