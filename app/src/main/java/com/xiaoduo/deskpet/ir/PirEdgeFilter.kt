package com.xiaoduo.deskpet.ir

/**
 * S70 PIR 电平边沿判定器（纯逻辑，零 Android import，JVM 可测）。
 *
 * 输入 GPIO 轮询样本序列，输出「本样本是否应触发唤醒」。三道防重：
 * 1. 上升沿记忆：只有稳定态 false→true 的跳变才可能触发，持续高电平不重复触发；
 * 2. 连续 [debounceCount] 样本去抖：单样本毛刺（电磁干扰/接触抖动）不触发；
 * 3. [cooldownMs] 冷却窗：触发后窗口内一切样本忽略（含新的完整上升沿），防 PIR 反复触发打断播报。
 *
 * 时钟由调用方注入（nowMs），本类不读系统时钟——测试可推进模拟时间。
 * 非线程安全：调用方（IrWakeController 轮询协程）须单线程串行调用。
 *
 * null 样本（读失败）不参与边沿判定、不污染稳定态、不打断已挂起的去抖候选。
 */
class PirEdgeFilter(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val debounceCount: Int = DEFAULT_DEBOUNCE_COUNT,
) {

    /** 前一稳定态（初始低电平；开机时 PIR 已高 → 首个去抖确认即一次正常上升沿）。 */
    private var stableLevel = false

    /** 去抖候选电平（null=无挂起候选）。 */
    private var candidate: Boolean? = null
    private var candidateCount = 0

    /** 上次触发时刻；null=从未触发。 */
    private var lastTriggerMs: Long? = null

    /**
     * 喂入一个轮询样本。
     * @param level GPIO 电平；null=读失败（忽略样本，不改任何状态）
     * @param nowMs 当前时刻（毫秒，调用方注入）
     * @return true=应触发唤醒（上升沿确认且不在冷却窗内）
     */
    fun onSample(level: Boolean?, nowMs: Long): Boolean {
        // S70 回归守卫：冷却窗检查——触发后 30s 内一切样本忽略，含新上升沿
        //（删此 → 冷却期内完整 false→true 往返在窗口内二次触发 → `冷却期内完整边沿往返不触发` 红）
        val last = lastTriggerMs
        if (last != null && nowMs - last < cooldownMs) return false
        if (level == null) return false // 读失败：不参与边沿判定，不污染稳定态，不打断候选
        if (candidate != null && candidate == level) {
            candidateCount++
        } else {
            candidate = level
            candidateCount = 1
        }
        if (candidateCount < debounceCount) return false // 去抖未满：继续等样本
        // S70 回归守卫：上升沿记忆（stableLevel）——持续高电平无新边沿不得重复触发
        //（删此 → 持续 true 跨冷却窗外的样本被判「新上升沿」→ `持续true跨冷却边界不重复触发` 红）
        val rising = !stableLevel && level
        stableLevel = level
        candidate = null
        candidateCount = 0
        if (!rising) return false
        lastTriggerMs = nowMs
        return true
    }

    companion object {
        /** 冷却窗 30s（用户 2026-10-26 拍板：常量，不进设置页）。 */
        const val DEFAULT_COOLDOWN_MS = 30_000L

        /** 去抖连续样本数（2 样本 @200ms 轮询 = 电平须稳定 ≥400ms）。 */
        const val DEFAULT_DEBOUNCE_COUNT = 2
    }
}
