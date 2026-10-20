package com.xiaoduo.deskpet.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 免按住模式（Tap-to-Talk）点按分流真值表（纯逻辑 JVM 单测）：
 * 「点一下」是语音入口唯一手势——LISTENING 态再点 = 结束聆听（stopListening：已判定→派发；
 * 未判定→既有分支）；其余三态点按 = 开始聆听（startListening：IDLE 直进；
 * SPEAKING/PROCESSING 走 VSM 既有 cancel 接续 barge-in，UI 层不再调 cancel）。
 *
 * 变异锚 M1：删 VoiceTapRouter 的 LISTENING 分支（全返 START）→ `listening routes to END` 红。
 */
class VoiceTapRouterTest {

    @Test
    fun `idle routes to START`() {
        assertEquals(VoiceTapAction.START, VoiceTapRouter.route(VoiceStateRouter.State.IDLE))
    }

    @Test
    fun `listening routes to END`() {
        assertEquals(VoiceTapAction.END, VoiceTapRouter.route(VoiceStateRouter.State.LISTENING))
    }

    @Test
    fun `processing routes to START`() {
        assertEquals(VoiceTapAction.START, VoiceTapRouter.route(VoiceStateRouter.State.PROCESSING))
    }

    @Test
    fun `speaking routes to START`() {
        assertEquals(VoiceTapAction.START, VoiceTapRouter.route(VoiceStateRouter.State.SPEAKING))
    }
}
