package com.xiaoduo.deskpet.ui.overlay

import com.xiaoduo.deskpet.api.BubbleOwner

/**
 * 气泡所有权判定（纯逻辑，零 Android import；PetOverlayService 只做接线）。
 *
 * 规则（BLOCKED.md backlog #4：跨通道互杀防线，S5 语音气泡与文字气泡并存前置）：
 * - [claim] 分配单调递增 round 并记录 owner；旧 round 即刻全部失效。
 * - show/append 走 [judgePublish]、hide 走 [judgeHide]：仅当前 round 受理（APPLY），
 *   过期一律 DROP_STALE（调用方必须日志，不许静默）。
 * - show_bubble=false：show/append → FORCE_HIDE（no-op + 立即隐藏），不验 round——
 *   与旧语义一致（设置关闭期间任何弹泡请求都强制收泡，含设置翻转前已弹出的泡）。
 * - 延迟隐藏到点时用 [judgeHide] 再验一次：新 round 占用期间旧 round 的延迟 hide 不生效。
 */
class BubbleOwnership {

    enum class Verdict { APPLY, DROP_STALE, FORCE_HIDE }

    private var currentRound = 0
    private var currentOwner: BubbleOwner? = null

    /** 认领新 round（单调递增）。调用方线程不定（IO/Default），内部同步。 */
    @Synchronized
    fun claim(owner: BubbleOwner): Int {
        currentRound += 1
        currentOwner = owner
        return currentRound
    }

    /** round 是否为当前有效轮（round 从 1 起，0/未认领一律无效）。 */
    @Synchronized
    fun isCurrent(owner: BubbleOwner, round: Int): Boolean =
        round > 0 && round == currentRound && owner == currentOwner

    /** show/append 判定：设置关闭优先于 round 校验（FORCE_HIDE 无条件收泡）。 */
    @Synchronized
    fun judgePublish(owner: BubbleOwner, round: Int, bubbleEnabled: Boolean): Verdict = when {
        !bubbleEnabled -> Verdict.FORCE_HIDE
        isCurrent(owner, round) -> Verdict.APPLY
        else -> Verdict.DROP_STALE
    }

    /** hide 判定（含延迟隐藏到点复验）：与 show_bubble 开关无关，收泡永远允许当前轮。 */
    @Synchronized
    fun judgeHide(owner: BubbleOwner, round: Int): Verdict =
        if (isCurrent(owner, round)) Verdict.APPLY else Verdict.DROP_STALE
}
