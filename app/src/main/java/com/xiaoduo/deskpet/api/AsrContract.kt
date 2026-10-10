package com.xiaoduo.deskpet.api

/** ASR 统一结果契约（spec §4.2 一刀：自 voice.AsrClient 嵌套类型原样下沉，2026-10-10；
 *  纯 Kotlin 零依赖，voice/cloud 两侧共同消费面）。 */
object AsrContract {

    /** 知识包踩坑 #13：language 必须显式 zh（auto 会把中文误判韩文/英文）。 */
    const val LANGUAGE_ZH = "zh"

    sealed interface Result {
        /** 成功：strip 后的识别文本。 */
        data class Success(val text: String) : Result

        /** 失败：message 透传真实原因（平台 msg / 网络异常）。 */
        data class Error(val message: String) : Result
    }
}
