package com.xiaoduo.deskpet.pet

/**
 * 悬浮窗位置钳制（纯 Kotlin，零 Android import，JVM 可测）。
 * 语义（真机 P2 返工）：精灵在窗内居中，故钳窗体中心 = 钳精灵中心--
 * 每轴窗中心必须钳在屏内 [0, screen]，保证精灵本体至少半身可见、可辨可拖
 * （旧「窗体留 margin 把手」语义下把手恰落在透明区，肉眼等同消失，已废弃）。
 * 窗口比屏幕还大时居中兜底（coerceIn 上下界反转会抛异常）。
 */
object OverlayClamp {

    /**
     * @param x/y 窗口左上角期望坐标（px）
     * @param winW/winH 窗口尺寸（px）
     * @param screenW/screenH 屏幕尺寸（px）
     * @return clamp 后的 (x, y)
     */
    fun clamp(
        x: Int,
        y: Int,
        winW: Int,
        winH: Int,
        screenW: Int,
        screenH: Int,
    ): Pair<Int, Int> =
        clampAxis(x, winW, screenW) to clampAxis(y, winH, screenH)

    private fun clampAxis(pos: Int, win: Int, screen: Int): Int {
        if (win >= screen) return (screen - win) / 2 // 窗口不短于屏幕：居中兜底
        return (pos + win / 2).coerceIn(0, screen) - win / 2 // 窗中心钳在屏内
    }

    /**
     * 气泡水平钳制（S3 返工#2）：气泡理想位置是宠物正上方（中心对齐）；
     * 宠物贴屏缘时气泡整体平移回屏内可见区，保持「attach 精灵正上方」的视觉关系。
     * @param petCenterX 宠物中心 X（px，屏幕坐标）
     * @param bubbleW 气泡实测宽（px；0 = 未布局，按中心点处理）
     * @param margin 距屏缘最小留白（px）
     * @return 气泡左缘的屏幕 X（px）
     */
    fun clampedBubbleLeft(petCenterX: Int, bubbleW: Int, screenW: Int, margin: Int): Int {
        val min = margin
        val max = screenW - margin - bubbleW
        val natural = petCenterX - bubbleW / 2
        // 气泡比可用区还宽时居中兜底（coerceIn 上下界反转会抛异常）
        return if (max < min) (screenW - bubbleW) / 2 else natural.coerceIn(min, max)
    }
}
