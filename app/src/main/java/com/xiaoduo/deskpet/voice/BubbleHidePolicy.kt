package com.xiaoduo.deskpet.voice

/**
 * 语音气泡自动收泡判定（纯逻辑，JVM 可测，零 Android import）。
 *
 * 用户拍板（2026-07-30）：TTS 全部播完 + reveal 完成后，延时 [DELAY_MS]（12s）收泡。
 * 语义（自定，已注释留档）：
 * - **回到 LISTENING 的多轮轮次不收**——用户可回看上一轮回答，气泡随新一轮 show 换新。
 * - **回 IDLE 才收**——30s 聆听超时 / 手动松开结束 / 管线失败兜底回 IDLE 时，
 *   若本轮弹过气泡则延时收；12s 内新轮次 show 认领新 round，
 *   旧收泡请求被服务侧 BubbleOwnership 令牌判 STALE 丢弃（不需要本侧取消）。
 * - **错误泡同走 12s 收**（critic-S5 ② 裁决）：ASR/AG-UI 失败直弹的错误泡也置位
 *   [onBubbleShown]——错误泡永久挂屏比早收更糟，语义与回复泡一致。
 * - 收泡只发一次：回 IDLE 判定后复位，重复 IDLE 转移不重复发。
 */
class BubbleHidePolicy {

    /** 本轮是否弹过气泡（reveal 首字符 show 时置位）。 */
    private var bubbleShownThisRound = false

    fun onBubbleShown() {
        bubbleShownThisRound = true
    }

    /** 会话回 IDLE 时调用：本轮弹过气泡则返回 true（应收）并复位，否则 false。 */
    fun onReturnToIdle(): Boolean {
        val hide = bubbleShownThisRound
        bubbleShownThisRound = false
        return hide
    }

    companion object {
        /** 播完 12s 收（用户拍板 2026-07-30）。 */
        const val DELAY_MS = 12_000L
    }
}
