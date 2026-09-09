package com.xiaoduo.deskpet.util

import kotlin.math.sqrt

/**
 * 悬浮窗点按/拖拽判据（S31，纯函数供 JVM 单测）。
 *
 * 点按 = UP 净位移（欧氏距离）≤ [MAX_DRIFT_PX]。与拖拽启动 touchSlop(12px) 解耦：
 * 12px 在 1x 密度板子上仅 1.7mm，真人按压自然漂移即可超过并误置 dragging——此时
 * 只要最终位移仍是漂移级（≤32px≈5mm）就按点按处理；真正拖拽位移远超 32px。
 */
object TapGesture {

    /** 点按净位移上限（px）。1x 密度 ≈5mm：覆盖手指漂移，排除真拖拽。改值需同步注释与测试。 */
    const val MAX_DRIFT_PX = 32.0

    /** UP 时判定本次手势是否点按：净位移 ≤ MAX_DRIFT_PX。 */
    fun isTap(driftX: Float, driftY: Float): Boolean =
        sqrt(driftX.toDouble() * driftX + driftY.toDouble() * driftY) <= MAX_DRIFT_PX
}
