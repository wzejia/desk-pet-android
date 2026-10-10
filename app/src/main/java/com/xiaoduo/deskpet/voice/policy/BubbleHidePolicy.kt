package com.xiaoduo.deskpet.voice.policy

import java.util.concurrent.atomic.AtomicBoolean

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
 *
 * 并发守卫（ocr[5]/[6]，2026-10-10 P3-T2）：show 写线程（pipeline IO/playAckThenListen）
 * 与回 IDLE 读线程（transition）不同执行域——裸 var 无 happens-before（可见性丢失=
 * 12s 收泡静默丢发）且 read-then-clear 非原子（并发 IDLE 转移可重复消费）。改
 * AtomicBoolean：写可见由原子类保证，「读取即复位」= getAndSet 一步原子。
 * 变异锚（BubbleHidePolicyConcurrencyTest）：删复位（getAndSet(false)→get()）→
 * 串行语义用例红（重复消费）；删写（onBubbleShown 空转）→ 同用例红。
 */
class BubbleHidePolicy {

    /**
     * 本轮是否弹过气泡（reveal 首字符 show 时置位）。ocr[6]：消费型一次性令牌——
     * [onReturnToIdle] 返回 true 的瞬间状态即已复位、不重试，调用方拿到 true 必须
     * 立刻调度 hideLater，不得丢弃返回值（服务侧 STALE 令牌只兜重发，兜不了丢发）。
     */
    private val pendingHide = AtomicBoolean(false)

    fun onBubbleShown() {
        pendingHide.set(true)
    }

    /** 会话回 IDLE 时调用：本轮弹过气泡则返回 true（应收）并复位，否则 false。 */
    fun onReturnToIdle(): Boolean = pendingHide.getAndSet(false)

    companion object {
        /** 播完 12s 收（用户拍板 2026-07-30）。 */
        const val DELAY_MS = 12_000L
    }
}
