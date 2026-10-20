package com.xiaoduo.deskpet.voice

/** 点按动作：START=开始聆听；END=结束聆听。真值表就两格，不引入第三态
 *  （放弃本轮=丢弃语义只在 cancelCurrentTurn 既有入口：打断键/barge-in 首字符/悬浮窗双击）。 */
enum class VoiceTapAction { START, END }

/**
 * 免按住模式（Tap-to-Talk）点按分流（纯逻辑，零 Android import，2026-10-18 拍板）：
 * 「点一下」是语音入口唯一手势，等价讯飞「点麦开始、引擎自动收口」——
 * 结束时机归状态机（VAD 判停/S51 续听/30s 超时），不绑人手松手（病 B 判停竞速根治）。
 *
 * - LISTENING 再点 = 结束聆听 → UI 调 stopListening()（已判定→派发有货送货；未判定→既有分支）；
 * - 其余三态点按 = 开始聆听 → UI 调 startListening()（IDLE 直进；SPEAKING/PROCESSING 走
 *   VSM :400 既有 cancel 接续 barge-in，UI 层不再调 cancel）。
 *
 * 两处 UI 共用本分流：ChatScreen 输入条语音区 / SettingsScreen 语音自检。
 * 变异锚 M1：删 LISTENING 分支（全返 START）→ VoiceTapRouterTest END 组红。
 */
object VoiceTapRouter {
    fun route(state: VoiceStateRouter.State): VoiceTapAction =
        if (state == VoiceStateRouter.State.LISTENING) VoiceTapAction.END else VoiceTapAction.START
}
