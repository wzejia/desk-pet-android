package com.xiaoduo.deskpet.ui

import kotlin.math.max
import kotlin.math.min

/**
 * S17 全屏语音页布局计算器（纯 Kotlin，零 Android 依赖，JVM 可测）。
 * 三合一适配（用户 2026-08-29 拍板，原型 designs/xiaoduo-motion-preview/字幕适配方案对比.html）：
 *
 * 1. **比例系统化**：宠物宽 = min(0.62×屏宽, 440dp, 0.45×屏高等比) --宽屏/横屏按高约束
 *    收敛（拔除 S14 遗留「横屏宠物顶格压字幕」根因）；锚点全部比例化 + 钳制。
 * 2. **跨区字幕**：字幕区 = 宠物底+16dp -> 状态区顶-12dp，底对齐可滚动，
 *    高度 clamp(跨区实际值, 64dp, 28%屏高) --手机与 S14 几乎一致，大屏自动放大 ~4 倍。
 * 3. **列宽钳制**：字幕/状态列宽 = min(屏宽-48dp, 520dp) 居中 --行长恒 ~30 字内可读。
 *
 * **上让位规则**（横屏防护栏）：宠物 42% 中心位置若侵入字幕兜底区（64dp），
 * 优先上移宠物；顶到 0 仍放不下则等比缩宠物（极端横条屏，布局不破优先于宠物大小）。
 *
 * 关键性质：OPPO 手机 360×792 上所有输出与 S14-2 验收值一致（零回归）。
 * Android 侧（FullscreenVoiceScreen）只消费本计算器输出，不再自带布局常量。
 */
object FullscreenVoiceLayout {

    /** 宠物宽度占屏宽比例（S14-2 原型验收值）。 */
    const val PET_WIDTH_RATIO = 0.62f

    /** 宠物宽度绝对上限（dp，防超宽屏比例失调）。 */
    const val PET_MAX_WIDTH_DP = 440f

    /** 宠物高度占屏高上限（横屏/矮屏收敛约束，S17 新增）。 */
    const val PET_MAX_HEIGHT_RATIO = 0.45f

    /** 宠物精灵帧宽高比（192×208，与 PetView/PetFrameTable 同源）。 */
    const val PET_ASPECT_H_OVER_W = 208f / 192f

    /** 宠物中心 Y（屏高比例，S14-2 三段式值）。 */
    const val PET_CENTER_Y_RATIO = 0.42f

    /** 宠物顶边最小留白（屏高比例，防顶到状态栏）。 */
    const val PET_TOP_MIN_RATIO = 0.06f

    /** 状态区底距：max(120dp, 19%屏高)（手机 792dp 上 = 150dp，与 S14 一致）。 */
    const val STATUS_BOTTOM_MIN_DP = 120f
    const val STATUS_BOTTOM_RATIO = 0.19f

    /** 状态区高度（指示器 28 + 间距 12 + caption 行高 24，S14 实测）。 */
    const val STATUS_ZONE_HEIGHT_DP = 64f

    /** 跨区字幕与宠物的上间距 / 与状态区的下间距。 */
    const val SUBTITLE_GAP_PET_DP = 16f
    const val SUBTITLE_GAP_STATUS_DP = 12f

    /** 字幕区高度下限（跨区不足时兜底）/ 上限（屏高比例）。 */
    const val SUBTITLE_MIN_H_DP = 64f
    const val SUBTITLE_MAX_H_RATIO = 0.28f

    /** 字幕列宽上限（dp，行长可读性钳制）与两侧留白。 */
    const val SUBTITLE_MAX_COLUMN_DP = 520f
    const val SUBTITLE_SIDE_PADDING_DP = 24f

    /** 字号缩放：clamp(min(宽,高)/360, 1.0, 1.3)。 */
    const val FONT_SCALE_REF_DP = 360f
    const val FONT_SCALE_MAX = 1.3f

    /** 全部布局锚点（dp；Android 侧直接消费）。 */
    data class Anchors(
        val petWidthDp: Float,
        val petHeightDp: Float,
        val petTopDp: Float,
        val petBottomDp: Float,
        /** 状态区（指示器+caption）底边距屏底距离。 */
        val statusBottomDp: Float,
        /** 字幕区底边距屏底距离（= 状态区高 + 12dp 间距 + 状态区底距）。 */
        val subtitleBottomDp: Float,
        /** 字幕区高度上限（跨区实际值或兜底，底对齐 + 可滚动）。 */
        val subtitleMaxHeightDp: Float,
        /** 字幕/状态内容列宽（居中）。 */
        val subtitleColumnWidthDp: Float,
        /** 字号缩放系数（caption/字幕 lineHeight 乘此值）。 */
        val fontScale: Float,
    )

    fun anchors(widthDp: Float, heightDp: Float): Anchors {
        require(widthDp > 0 && heightDp > 0) { "屏幕尺寸必须为正: ${widthDp}x${heightDp}" }

        // ---- 状态区（先算：宠物让位规则依赖它的顶） ----
        val statusBottom = max(STATUS_BOTTOM_MIN_DP, STATUS_BOTTOM_RATIO * heightDp)
        val statusZoneTop = heightDp - statusBottom - STATUS_ZONE_HEIGHT_DP

        // ---- 宠物几何 ----
        val subtitleReserve = SUBTITLE_GAP_PET_DP + SUBTITLE_MIN_H_DP + SUBTITLE_GAP_STATUS_DP
        var petWidth = min(
            PET_WIDTH_RATIO * widthDp,
            min(PET_MAX_WIDTH_DP, PET_MAX_HEIGHT_RATIO * heightDp / PET_ASPECT_H_OVER_W),
        )
        var petHeight = petWidth * PET_ASPECT_H_OVER_W
        val idealTop = max(PET_TOP_MIN_RATIO * heightDp, PET_CENTER_Y_RATIO * heightDp - petHeight / 2f)
        // 上让位：42% 中心若侵入字幕兜底区 -> 上移宠物；顶到 0 仍放不下 -> 缩宠物
        val topMax = statusZoneTop - subtitleReserve - petHeight
        val petTop = when {
            topMax >= 0f -> min(idealTop, topMax)
            else -> {
                petHeight = max(0f, statusZoneTop - subtitleReserve)
                petWidth = petHeight / PET_ASPECT_H_OVER_W
                0f
            }
        }

        // ---- 跨区字幕 ----
        val petBottom = petTop + petHeight
        val span = statusZoneTop - SUBTITLE_GAP_STATUS_DP - (petBottom + SUBTITLE_GAP_PET_DP)
        val subtitleMaxH = min(max(SUBTITLE_MIN_H_DP, span), SUBTITLE_MAX_H_RATIO * heightDp)
        val subtitleBottom = statusBottom + STATUS_ZONE_HEIGHT_DP + SUBTITLE_GAP_STATUS_DP

        // ---- 列宽与字号 ----
        val columnWidth = min(widthDp - SUBTITLE_SIDE_PADDING_DP * 2f, SUBTITLE_MAX_COLUMN_DP)
        val fontScale = min(FONT_SCALE_MAX, max(1f, min(widthDp, heightDp) / FONT_SCALE_REF_DP))

        return Anchors(
            petWidthDp = petWidth,
            petHeightDp = petHeight,
            petTopDp = petTop,
            petBottomDp = petBottom,
            statusBottomDp = statusBottom,
            subtitleBottomDp = subtitleBottom,
            subtitleMaxHeightDp = subtitleMaxH,
            subtitleColumnWidthDp = columnWidth,
            fontScale = fontScale,
        )
    }
}
