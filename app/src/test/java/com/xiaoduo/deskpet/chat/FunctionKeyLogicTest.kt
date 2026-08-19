package com.xiaoduo.deskpet.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 多功能键状态机真值表（spec H 节 + 2026-08-04 用户增补停止档）。
 * 优先级：输出中 STOP ＞ 上传中 DISABLED ＞ 有内容 SEND ＞ 文字模式 TO_VOICE ＞ 语音模式 TO_KEYBOARD。
 */
class FunctionKeyLogicTest {

    private fun resolve(
        busy: Boolean = false,
        voiceActive: Boolean = false,
        hasContent: Boolean = false,
        voiceMode: Boolean = false,
        uploadInFlight: Boolean = false,
    ) = FunctionKeyLogic.resolve(busy, voiceActive, hasContent, voiceMode, uploadInFlight)

    // ---- STOP：输出中（文字轮 busy 或语音轮 voiceActive），其余输入全无关 ----

    @Test
    fun `busy text turn shows stop regardless of content and mode`() {
        assertEquals(FunctionKeyLogic.Action.STOP, resolve(busy = true, hasContent = true, voiceMode = false))
        assertEquals(FunctionKeyLogic.Action.STOP, resolve(busy = true, hasContent = false, voiceMode = true))
    }

    @Test
    fun `voice active shows stop`() {
        assertEquals(FunctionKeyLogic.Action.STOP, resolve(voiceActive = true, hasContent = true))
        assertEquals(FunctionKeyLogic.Action.STOP, resolve(voiceActive = true))
    }

    @Test
    fun `stop has priority over upload in flight`() {
        // 上传中恰有输出在飞：仍须可停止（停止后键回退 DISABLED，由纯函数性质自动成立）
        assertEquals(FunctionKeyLogic.Action.STOP, resolve(busy = true, uploadInFlight = true))
        assertEquals(FunctionKeyLogic.Action.STOP, resolve(voiceActive = true, uploadInFlight = true))
    }

    // ---- DISABLED：空闲但上传在飞（DeepSeek「文件上传中请稍候」式禁发） ----

    @Test
    fun `upload in flight disables even with content`() {
        assertEquals(
            FunctionKeyLogic.Action.DISABLED,
            resolve(hasContent = true, uploadInFlight = true),
        )
    }

    @Test
    fun `upload in flight disables mode toggles too`() {
        assertEquals(FunctionKeyLogic.Action.DISABLED, resolve(uploadInFlight = true, voiceMode = false))
        assertEquals(FunctionKeyLogic.Action.DISABLED, resolve(uploadInFlight = true, voiceMode = true))
    }

    // ---- SEND：空闲 + 有内容（两模式一致） ----

    @Test
    fun `idle with content sends in both modes`() {
        assertEquals(FunctionKeyLogic.Action.SEND, resolve(hasContent = true, voiceMode = false))
        assertEquals(FunctionKeyLogic.Action.SEND, resolve(hasContent = true, voiceMode = true))
    }

    // ---- 模式切换：空闲 + 无内容 ----

    @Test
    fun `idle empty in text mode switches to voice`() {
        assertEquals(FunctionKeyLogic.Action.TO_VOICE, resolve(voiceMode = false))
    }

    @Test
    fun `idle empty in voice mode switches to keyboard`() {
        assertEquals(FunctionKeyLogic.Action.TO_KEYBOARD, resolve(voiceMode = true))
    }

    // ---- 全表穷举（2^5=32 组合）防分支回归 ----

    @Test
    fun `exhaustive truth table`() {
        for (busy in booleanArrayOf(false, true))
            for (va in booleanArrayOf(false, true))
                for (hc in booleanArrayOf(false, true))
                    for (vm in booleanArrayOf(false, true))
                        for (up in booleanArrayOf(false, true)) {
                            val expected = when {
                                busy || va -> FunctionKeyLogic.Action.STOP
                                up -> FunctionKeyLogic.Action.DISABLED
                                hc -> FunctionKeyLogic.Action.SEND
                                !vm -> FunctionKeyLogic.Action.TO_VOICE
                                else -> FunctionKeyLogic.Action.TO_KEYBOARD
                            }
                            assertEquals(
                                "busy=$busy va=$va hc=$hc vm=$vm up=$up",
                                expected,
                                resolve(busy, va, hc, vm, up),
                            )
                        }
    }
}
