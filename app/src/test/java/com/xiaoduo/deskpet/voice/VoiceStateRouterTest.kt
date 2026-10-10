package com.xiaoduo.deskpet.voice

import com.xiaoduo.deskpet.voice.pipeline.VoiceStateRouter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * VoiceStateRouter 单测（纯逻辑）。
 * 覆盖：四态分发表（idle→KWS / listening→VAD / speaking+processing→KWS——S12 唤醒词
 * 打断转正 / KWS pause flag 只门 IDLE）+ 唤醒词前缀剥除三变体 + 非前缀不剥 + 剥后为空。
 */
class VoiceStateRouterTest {

    @Test
    fun `四态分发表`() {
        val router = VoiceStateRouter()
        assertEquals(VoiceStateRouter.Sink.KWS, router.route(VoiceStateRouter.State.IDLE))
        assertEquals(VoiceStateRouter.Sink.VAD, router.route(VoiceStateRouter.State.LISTENING))
        // S12：对话两态恒 KWS（唤醒词打断的声学前提；变异锚：回退恒 DROP → 红）
        assertEquals(VoiceStateRouter.Sink.KWS, router.route(VoiceStateRouter.State.PROCESSING))
        assertEquals(VoiceStateRouter.Sink.KWS, router.route(VoiceStateRouter.State.SPEAKING))
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
    fun `S12 唤醒词打断 SPEAKING 与 PROCESSING 默认进 KWS 且不受 kwsPaused 门控`() {
        // S11-3 spike 真机双合格（TTS 播放 4min 误触发 0 / 播放中真声检出 8/10）后转正：
        // 对话两态麦块恒进 KWS = 唤醒词打断的声学前提。spikeFeedKws 调试开关随本票退役
        // （被生产行为取代，原 spike 用例同步删除）。
        // 变异锚：SPEAKING/PROCESSING 回退恒 DROP → 对话期唤醒不可达 → 红
        val router = VoiceStateRouter()
        assertEquals(VoiceStateRouter.Sink.KWS, router.route(VoiceStateRouter.State.SPEAKING))
        assertEquals(VoiceStateRouter.Sink.KWS, router.route(VoiceStateRouter.State.PROCESSING))
        // kwsPaused 只门 IDLE 分支——若门住对话两态，唤醒打断在对话期仍不可达
        router.kwsPaused = true
        assertEquals(VoiceStateRouter.Sink.KWS, router.route(VoiceStateRouter.State.SPEAKING))
        assertEquals(VoiceStateRouter.Sink.KWS, router.route(VoiceStateRouter.State.PROCESSING))
    }

    @Test
    fun `唤醒词前缀剥除三变体`() {
        assertEquals("今天天气怎么样", VoiceStateRouter.stripWakePrefix("小朵小朵今天天气怎么样"))
        assertEquals("你好", VoiceStateRouter.stripWakePrefix("小朵 你好"))
        assertEquals("讲个笑话", VoiceStateRouter.stripWakePrefix("小度小度讲个笑话"))
    }

    @Test
    fun `非前缀不剥 词中小朵不剥`() {
        // S54：原断言 stripWakePrefix("你好小朵")=="你好小朵" 删除——「你好小朵」升级为
        // 声学唤醒词（拍板 Q1=①），话头须剥除，见下方 S54 用例
        assertEquals("给我讲个小朵朵的故事", VoiceStateRouter.stripWakePrefix("给我讲个小朵朵的故事"))
    }

    @Test
    fun `剥后为空返回空串 叠加前缀循环剥`() {
        assertEquals("", VoiceStateRouter.stripWakePrefix("小朵"))
        assertEquals("现在几点", VoiceStateRouter.stripWakePrefix("小朵小朵小朵现在几点"))
    }

    @Test
    fun `S40 我在应答回声剥除`() {
        // 幽灵轮主锚：纯「我在。」剥后剩纯标点 → runPipeline onlyWakeWord 分支回 IDLE
        // （删 WAKE_PREFIXES「我在」→ 本用例红，幽灵轮复现）
        assertEquals("。", VoiceStateRouter.stripWakePrefix("我在。"))
        // 混合形态：话头「我在，」剥除，指令主体保留
        assertEquals("，查询档案", VoiceStateRouter.stripWakePrefix("我在，查询档案"))
        // 叠加：唤醒词+回声同句循环剥
        assertEquals("", VoiceStateRouter.stripWakePrefix("小朵小朵我在"))
        // 已知代价锁定：指令恰以「我在」开头会被误剥（命令式对话极少见，拍板接受）
        assertEquals("想", VoiceStateRouter.stripWakePrefix("我在想"))
    }

    // ---- S54 多唤醒/打断词（grilling 拍板 2026-09-21）----

    @Test
    fun `S54 你好小朵升级唤醒词后话头剥除`() {
        assertEquals("帮我查档案", VoiceStateRouter.stripWakePrefix("你好小朵帮我查档案"))
        assertEquals("", VoiceStateRouter.stripWakePrefix("你好小朵"))
        // 与既有前缀叠加循环剥（小朵小朵 → 你好小朵 → 空）
        assertEquals("在吗", VoiceStateRouter.stripWakePrefix("小朵小朵你好小朵在吗"))
    }

    @Test
    fun `S54 唤醒组全态动作`() {
        for (w in VoiceStateRouter.WAKE_WORDS) {
            for (s in VoiceStateRouter.State.values()) {
                assertEquals("$w@$s", VoiceStateRouter.KwsHitAction.ACT, VoiceStateRouter.decide(w, s))
            }
        }
    }

    @Test
    fun `S54 打断组对话两态动作 IDLE与LISTENING丢弃`() {
        for (w in VoiceStateRouter.INTERRUPT_WORDS) {
            // Q5=b：SPEAKING/PROCESSING 动作（= S12 唤醒词打断同链路，Q3=b 打断并续听）
            assertEquals(w, VoiceStateRouter.KwsHitAction.ACT, VoiceStateRouter.decide(w, VoiceStateRouter.State.SPEAKING))
            assertEquals(w, VoiceStateRouter.KwsHitAction.ACT, VoiceStateRouter.decide(w, VoiceStateRouter.State.PROCESSING))
            // Q6=a：IDLE 期闲聊「好了」不得唤醒小朵；LISTENING 物理不可达仍防御性丢弃
            assertEquals(w, VoiceStateRouter.KwsHitAction.DROP, VoiceStateRouter.decide(w, VoiceStateRouter.State.IDLE))
            assertEquals(w, VoiceStateRouter.KwsHitAction.DROP, VoiceStateRouter.decide(w, VoiceStateRouter.State.LISTENING))
        }
    }

    @Test
    fun `S54 spike词停恒丢弃`() {
        // Q7：「停」真机双合格（播报中 ≥8/10 且播放期误打断 0）前不接动作；
        // 转正 = 挪 VoiceStateRouter.INTERRUPT_WORDS（届时本用例随拍板红→改）
        for (s in VoiceStateRouter.State.values()) {
            assertEquals("停@$s", VoiceStateRouter.KwsHitAction.DROP, VoiceStateRouter.decide("停", s))
        }
    }

    @Test
    fun `S54 未登记词默认动作保持既有语义`() {
        // keywords.txt 与组表漂移时不静默失效（保持 S12 起命中即动作的行为）
        assertEquals(VoiceStateRouter.KwsHitAction.ACT, VoiceStateRouter.decide("某新词", VoiceStateRouter.State.IDLE))
    }
}
