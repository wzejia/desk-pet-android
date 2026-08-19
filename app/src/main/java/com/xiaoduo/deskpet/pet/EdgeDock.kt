package com.xiaoduo.deskpet.pet

/**
 * 贴边收纳吸附几何（S10 半身贴边，纯 Kotlin，零 Android import，JVM 可测）。
 *
 * 语义（用户四拍板 2026-08-18）：拖到屏缘松手 -> 窗口滑入只露半个身位（窗中心恰在屏缘）；
 * 单击滑出完全可见贴缘。贴边坐标是 [OverlayClamp.clamp] 的不动点--持久化、STICKY 重启恢复、
 * onConfigurationChanged 重钳位三者零新机制自然成立；「当前是否贴边」由几何推导
 * （窗中心 ≤0 / ≥屏宽）而非额外状态位，重启恢复后语义自动正确。
 */
object EdgeDock {

    /** 吸附目标边。 */
    enum class Side { LEFT, RIGHT }

    /**
     * 松手吸附判定：精灵中心距左/右屏缘 ≤ [zonePx] -> 吸对应边（边界恰含）。
     * 完全离开边缘不吸（反例：屏中松手 -> null 原位不动）。
     * @param centerX 精灵/窗体中心 X（px，屏幕坐标；调用方传 clamp 后的中心，
     *   窄屏时 clamp 把中心钳到屏中，恰落 null 兜底不吸）
     * @param screenW 屏幕宽（px）
     * @param zonePx 吸附区半宽（px；S10-2 取 petW/2，即精灵仍有部分贴缘/出屏才吸）
     */
    fun dockSide(centerX: Int, screenW: Int, zonePx: Int): Side? {
        val nearLeft = centerX <= zonePx
        val nearRight = centerX >= screenW - zonePx
        // S10-1 回归守卫：两侧同时命中（屏不宽于 2·zonePx，zone=winW/2 时即屏不宽于窗）
        // -> null 兜底（删此 -> 窄屏中部松手恒吸一侧 -> screen-not-wider 测试红）
        if (nearLeft && nearRight) return null
        if (nearLeft) return Side.LEFT
        if (nearRight) return Side.RIGHT
        return null
    }

    /**
     * 贴边（半身）窗体左上角 X：窗中心恰在屏缘，与 [OverlayClamp.clampAxis] 同套整除运算
     * （winW/2 截断），保证 x + winW/2 == 0 / == screenW 逐像素成立--
     * 因此 dockedX 是 clamp 的不动点，持久化恢复无需重钳。
     */
    fun dockedX(side: Side, winW: Int, screenW: Int): Int = when (side) {
        Side.LEFT -> -winW / 2
        Side.RIGHT -> screenW - winW / 2
    }

    /**
     * 滑出（单击召回）窗体左上角 X：完全可见贴缘。
     * 前提 winW < screenW（窗宽于屏时不存在「完全可见」解，调用方经 clamp 居中、不进入贴边态）。
     */
    fun slideOutX(side: Side, winW: Int, screenW: Int): Int = when (side) {
        Side.LEFT -> 0
        Side.RIGHT -> screenW - winW
    }

    /**
     * 几何推导当前贴边态（无额外状态位，重启恢复后语义自动正确）：
     * 窗中心 ≤0 -> LEFT / ≥screenW -> RIGHT / 其余（含全可见贴缘 x=0、屏中）-> null。
     * 中心整除口径与 [dockedX] 一致（x + winW/2）。
     */
    fun dockedSideOf(x: Int, winW: Int, screenW: Int): Side? {
        val center = x + winW / 2
        if (center <= 0) return Side.LEFT
        if (center >= screenW) return Side.RIGHT
        return null
    }
}
