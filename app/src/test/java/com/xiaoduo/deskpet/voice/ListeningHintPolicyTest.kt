package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.voice.pipeline.VoiceStateRouter
import com.xiaoduo.deskpet.voice.policy.ListeningHintPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S49 聆听提示判定锚点测试。核心回归锚：**多轮回听（from==SPEAKING）必须 SHOW**——
 * S5 旧规则在此场景 SKIP，用户实测报障「多轮对话时聆听气泡消失」（2026-09-18）；
 * 变异锚：policy 里恢复 from==SPEAKING 跳过分支 → 本组红。
 */
class ListeningHintPolicyTest {

    private val L = VoiceStateRouter.State.LISTENING
    private val S = VoiceStateRouter.State.SPEAKING

    @Test
    fun freshEntryShows() {
        assertEquals(
            ListeningHintPolicy.Action.SHOW,
            ListeningHintPolicy.decide(L, VoiceStateRouter.State.IDLE, suppressOnce = false),
        )
    }

    @Test
    fun multiTurnRelistenShows() {
        // S49 回归锚：多轮回听（TTS 播完→LISTENING）不再跳过，声纹必须弹
        assertEquals(
            ListeningHintPolicy.Action.SHOW,
            ListeningHintPolicy.decide(L, S, suppressOnce = false),
        )
    }

    @Test
    fun emptyAsrResumeShows() {
        // PROCESSING→LISTENING（空 ASR 续听）同样显示
        assertEquals(
            ListeningHintPolicy.Action.SHOW,
            ListeningHintPolicy.decide(L, VoiceStateRouter.State.PROCESSING, suppressOnce = false),
        )
    }

    @Test
    fun wozaiSuppressConsumesFlag() {
        assertEquals(
            ListeningHintPolicy.Action.SUPPRESS_ONCE,
            ListeningHintPolicy.decide(L, S, suppressOnce = true),
        )
    }

    @Test
    fun nonListeningSkips() {
        for (to in VoiceStateRouter.State.entries) {
            if (to == L) continue
            assertEquals(
                "to=$to 不得显聆听提示",
                ListeningHintPolicy.Action.SKIP,
                ListeningHintPolicy.decide(to, VoiceStateRouter.State.IDLE, suppressOnce = false),
            )
        }
    }
}
