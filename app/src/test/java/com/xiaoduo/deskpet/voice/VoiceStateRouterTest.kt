package com.xiaoduo.deskpet.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * VoiceStateRouter 单测（纯逻辑）。
 * 覆盖：四态分发表（idle→KWS / listening→VAD / processing+speaking→DROP / KWS pause flag）+
 * 唤醒词前缀剥除三变体 + 非前缀不剥 + 剥后为空。
 */
class VoiceStateRouterTest {

    @Test
    fun `四态分发表`() {
        val router = VoiceStateRouter()
        assertEquals(VoiceStateRouter.Sink.KWS, router.route(VoiceStateRouter.State.IDLE))
        assertEquals(VoiceStateRouter.Sink.VAD, router.route(VoiceStateRouter.State.LISTENING))
        assertEquals(VoiceStateRouter.Sink.DROP, router.route(VoiceStateRouter.State.PROCESSING))
        assertEquals(VoiceStateRouter.Sink.DROP, router.route(VoiceStateRouter.State.SPEAKING))
    }

    @Test
    fun `KWS pause flag 挂点置位后 IDLE 丢块`() {
        val router = VoiceStateRouter()
        router.kwsPaused = true
        assertEquals(VoiceStateRouter.Sink.DROP, router.route(VoiceStateRouter.State.IDLE))
        // 其余态不受 pause flag 影响
        assertEquals(VoiceStateRouter.Sink.VAD, router.route(VoiceStateRouter.State.LISTENING))
    }

    @Test
    fun `S11-3 spike 放行 SPEAKING 麦块进 KWS 默认与 PROCESSING 恒 DROP`() {
        val router = VoiceStateRouter()
        // 默认 false：SPEAKING 恒 DROP（与既有分发表一致）
        assertEquals(VoiceStateRouter.Sink.DROP, router.route(VoiceStateRouter.State.SPEAKING))
        // spike arm：SPEAKING 麦块真实放行 KWS（08-04 空证据教训：旧 spike 只旁路
        // kwsPaused，SPEAKING 恒 DROP，KWS 全程没收到音频）
        router.spikeFeedKws = true
        assertEquals(VoiceStateRouter.Sink.KWS, router.route(VoiceStateRouter.State.SPEAKING))
        // PROCESSING 不受 spike 影响（spike 只验 TTS 回声期，避免变量混杂）
        assertEquals(VoiceStateRouter.Sink.DROP, router.route(VoiceStateRouter.State.PROCESSING))
    }

    @Test
    fun `唤醒词前缀剥除三变体`() {
        assertEquals("今天天气怎么样", VoiceStateRouter.stripWakePrefix("小朵小朵今天天气怎么样"))
        assertEquals("你好", VoiceStateRouter.stripWakePrefix("小朵 你好"))
        assertEquals("讲个笑话", VoiceStateRouter.stripWakePrefix("小度小度讲个笑话"))
    }

    @Test
    fun `非前缀不剥 词中小朵不剥`() {
        assertEquals("你好小朵", VoiceStateRouter.stripWakePrefix("你好小朵"))
        assertEquals("给我讲个小朵朵的故事", VoiceStateRouter.stripWakePrefix("给我讲个小朵朵的故事"))
    }

    @Test
    fun `剥后为空返回空串 叠加前缀循环剥`() {
        assertEquals("", VoiceStateRouter.stripWakePrefix("小朵"))
        assertEquals("现在几点", VoiceStateRouter.stripWakePrefix("小朵小朵小朵现在几点"))
    }
}
