package com.xiaoduo.deskpet.chat

import com.xiaoduo.deskpet.device.DeviceIntentMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S30 文字设备控制分流（纯 Kotlin）：聊天框文字轮的设备意图判定语义。
 * 与语音轮共用 DeviceIntentMatcher（S26 保守匹配：须含设备名词，普通提问零劫持）；
 * 设备地址空 = 设备技能禁用（与 S26-4 语音轮同语义）。
 */
class DeviceTurnRulesTest {

    @Test
    fun `matched device phrase returns command`() {
        // 中文话术命中（与语音轮同一匹配器、同一话术域）
        val cmd = DeviceTurnRules.gate(
            baseUrl = "http://10.0.18.47:8080",
            text = "打开整理台",
        )
        assertEquals(DeviceIntentMatcher.Command.PowerOn::class, cmd!!::class)
    }

    @Test
    fun `empty baseUrl disables device turn`() {
        // 地址清空=禁用（S26-4 同语义）：即使文本命中话术也不分流
        assertNull(DeviceTurnRules.gate(baseUrl = "", text = "打开整理台"))
    }

    @Test
    fun `plain question not hijacked`() {
        // 普通提问零劫持：不含设备名词走 LLM
        assertNull(
            DeviceTurnRules.gate(
                baseUrl = "http://10.0.18.47:8080",
                text = "档案保管期限是几年？",
            ),
        )
    }

    @Test
    fun `non-device text with device configured returns null`() {
        assertNull(
            DeviceTurnRules.gate(
                baseUrl = "http://10.0.18.47:8080",
                text = "hello",
            ),
        )
    }
}
