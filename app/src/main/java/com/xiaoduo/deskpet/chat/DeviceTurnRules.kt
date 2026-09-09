package com.xiaoduo.deskpet.chat

import com.xiaoduo.deskpet.device.DeviceIntentMatcher

/**
 * S30 文字设备控制分流（纯 Kotlin）：聊天框文字轮的设备意图判定。
 * 与语音轮（VSM，S26-3）共用 DeviceIntentMatcher；地址空=禁用（S26-4 同语义）。
 * ChatViewModel.deviceIntentOrNull 委托本 gate——分流语义纯化可 JVM 单测。
 */
object DeviceTurnRules {

    /** 返回文字输入命中的设备命令；null = 不分流（走 LLM 常规轮）。 */
    fun gate(baseUrl: String, text: String): DeviceIntentMatcher.Command? =
        if (baseUrl.isEmpty()) null else DeviceIntentMatcher.match(text)
}
