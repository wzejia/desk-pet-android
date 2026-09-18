package com.xiaoduo.deskpet.voice

/**
 * S49 聆听提示判定（纯逻辑，JVM 可测）：进 LISTENING 时指示/提示怎么显。
 *
 * 历史：S5 定「多轮回听（from==SPEAKING）不显提示泡」——旧时代聆听反馈是文字泡，
 * 回复泡跨轮驻留（用户可读）即反馈本体，提示泡反而顶掉它。
 * S49（用户拍板 2026-09-18）：聆听反馈改为声纹泡后，多轮回听**同样显示声纹**——
 * 否则多轮聆听期零反馈（用户实测报障）。代价：回复文字泡在回听起点被声纹顶替
 * （TTS 已播完，文本在会话历史里，信息不丢）。
 *
 * wozai 唤醒路径的「我在」驻留由一次性抑制标（suppressListeningHintOnce）承接：
 * 抑制标分支返回 SUPPRESS_ONCE（消费标 + 不当即显示，驻留 swap job 负责顶替）。
 */
object ListeningHintPolicy {

    enum class Action {
        /** 消费一次性抑制标，不当即显示（wozai 驻留 swap job 稍后顶替）。 */
        SUPPRESS_ONCE,

        /** 立即显示聆听提示（声纹泡 / 未接指示层时「在听…」文字泡）。 */
        SHOW,

        /** 不动（非 LISTENING 迁移）。 */
        SKIP,
    }

    fun decide(
        to: VoiceStateRouter.State,
        from: VoiceStateRouter.State,
        suppressOnce: Boolean,
    ): Action = when {
        to != VoiceStateRouter.State.LISTENING -> Action.SKIP
        suppressOnce -> Action.SUPPRESS_ONCE
        // S49：from==SPEAKING（多轮回听）不再跳过——聆听即声纹，无条件反馈
        else -> Action.SHOW
    }
}
