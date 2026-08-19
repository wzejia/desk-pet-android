package com.xiaoduo.deskpet.voice

/**
 * 半双工四态麦块路由 + 唤醒词前缀剥除（纯逻辑，JVM 可测）。
 *
 * 依据：对账表状态机节（= 知识包 §3.5）：
 * - 麦块分发：IDLE→KWS / LISTENING→VAD / processing+speaking 丢弃
 * - KWS pause flag：对话期间暂停 KWS decode 防 TTS 自声误触发（挂点留给 S5）
 * - 唤醒词前缀兜底：ASR 文本若以 小朵小朵/小朵/小度小度 开头则剥除
 */
class VoiceStateRouter {

    enum class State { IDLE, LISTENING, PROCESSING, SPEAKING }

    /** 麦块去向。 */
    enum class Sink { KWS, VAD, DROP }

    /** S5 挂点：对话期间置 true 暂停 KWS decode（半双工第二层）。 */
    @Volatile
    var kwsPaused: Boolean = false

    /** 当前态麦块去向（四态分发表）。 */
    fun route(state: State): Sink = when (state) {
        State.IDLE -> if (kwsPaused) Sink.DROP else Sink.KWS
        State.LISTENING -> Sink.VAD
        State.PROCESSING, State.SPEAKING -> Sink.DROP
    }

    companion object {
        /** 对账表：["小朵小朵", "小朵", "小度小度"]，长前缀优先（小朵小朵 先于 小朵 命中）。 */
        val WAKE_PREFIXES = listOf("小朵小朵", "小度小度", "小朵")

        /** 剥除 ASR 文本开头的唤醒词（可叠加出现则循环剥），剥后 trim 首尾空白。 */
        fun stripWakePrefix(text: String): String {
            var t = text.trim()
            var again = true
            while (again) {
                again = false
                for (p in WAKE_PREFIXES) {
                    if (t.startsWith(p)) {
                        t = t.removePrefix(p).trim()
                        again = true
                        break
                    }
                }
            }
            return t
        }
    }
}
