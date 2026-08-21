package com.xiaoduo.deskpet.voice

/**
 * 半双工四态麦块路由 + 唤醒词前缀剥除（纯逻辑，JVM 可测）。
 *
 * 依据：对账表状态机节（= 知识包 §3.5）+ S12 唤醒词打断（2026-08-20 转正）：
 * - 麦块分发：IDLE→KWS / LISTENING→VAD / SPEAKING+PROCESSING→KWS
 *   （S11-3 spike 真机双合格后转正式产品行为：TTS 播放 4min 误触发 0——硬件 AEC
 *   把回声压到能量门以下；播放中真声检出 8/10 达标。spikeFeedKws 调试开关随本票
 *   退役，空证据史见 docs/solutions/workflow-issues/aec-spike-null-evidence-feed-verification.md）
 * - KWS pause flag：只门 IDLE 分支（对话期回 IDLE 不即解 KWS）；SPEAKING/PROCESSING
 *   不受其影响——唤醒词打断正是靠这两态持续进 KWS 才可达
 * - 唤醒词前缀兜底：ASR 文本若以 小朵小朵/小朵/小度小度 开头则剥除
 */
class VoiceStateRouter {

    enum class State { IDLE, LISTENING, PROCESSING, SPEAKING }

    /** 麦块去向。 */
    enum class Sink { KWS, VAD, DROP }

    /** S5 挂点：对话期间置 true（只门 IDLE 分支；SPEAKING/PROCESSING 恒进 KWS 不受其门）。 */
    @Volatile
    var kwsPaused: Boolean = false

    /** 当前态麦块去向（四态分发表）。 */
    fun route(state: State): Sink = when (state) {
        State.IDLE -> if (kwsPaused) Sink.DROP else Sink.KWS
        State.LISTENING -> Sink.VAD
        // S12 回归守卫：SPEAKING/PROCESSING 恒 KWS（唤醒词打断的声学前提，spike 双合格
        // 转正——删此回退恒 DROP → 对话期唤醒词不可达 → VoiceStateRouterTest 红）
        State.PROCESSING -> Sink.KWS
        State.SPEAKING -> Sink.KWS
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
