package com.xiaoduo.deskpet.chat

/**
 * 语音/文字轮并发写者门（纯 Kotlin 零 Android import，JVM 可测；DATA-3）。
 *
 * 语音 transcript sink 的 onUserUtterance 此前只看 _voiceActive / vsm 态——
 * 「我在」播放/聆听期两标志均假，文字轮在途（SENDING/STREAMING）时语音轮照样进入，
 * 与在途文字轮成双写者：_messages 交错 RMW 后写整体覆盖先写（丢消息/丢 delta），
 * 两轮收尾并发 persist 文件层 last-writer-wins。
 *
 * 规则：文字轮在途即拒新语音轮（拒入方向安全——用户可再说一遍，历史数据不被覆盖）。
 * ChatViewModel.onUserUtterance 开头调用本门。
 */
object TurnGateRules {

    /** 文字轮在途判定：SENDING / STREAMING 视为在途（busy 守卫的文字轮半边）。 */
    fun textTurnInFlight(sending: Boolean, streaming: Boolean): Boolean = sending || streaming
}
