package com.xiaoduo.deskpet.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * S27 手绘图标：material-icons-core 集合没有「密集架柜格」与「全屏四角」，
 * 自绘而非引入 icons-extended 大依赖。stroke 风格，Icon() 的 tint 会罩染实际颜色。
 */

/** 密集架（田字格柜体 + 每格把手），用于导航行正中的跳转键。 */
val DenseShelfIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "DenseShelf",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // 柜体外框
            moveTo(3f, 4f)
            horizontalLineTo(21f)
            lineTo(21f, 20f)
            lineTo(3f, 20f)
            close()
            // 十字分隔
            moveTo(3f, 12f)
            horizontalLineTo(21f)
            moveTo(12f, 4f)
            lineTo(12f, 20f)
            // 四格把手
            moveTo(6f, 8f)
            horizontalLineTo(7.5f)
            moveTo(16.5f, 8f)
            horizontalLineTo(18f)
            moveTo(6f, 16f)
            horizontalLineTo(7.5f)
            moveTo(16.5f, 16f)
            horizontalLineTo(18f)
        }
    }.build()
}

/** 全屏四角（进入全屏语音对话页），与输入栏「按住说话」麦克风图标区分。 */
val FullscreenVoiceIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "FullscreenVoice",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // 左上 / 右上 / 左下 / 右下 四角
            moveTo(9f, 3f)
            lineTo(3f, 3f)
            lineTo(3f, 9f)
            moveTo(15f, 3f)
            lineTo(21f, 3f)
            lineTo(21f, 9f)
            moveTo(9f, 21f)
            lineTo(3f, 21f)
            lineTo(3f, 15f)
            moveTo(15f, 21f)
            lineTo(21f, 21f)
            lineTo(21f, 15f)
        }
    }.build()
}
