package com.xiaoduo.deskpet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 主题（KD11：Material3 dynamicColor 关闭，自定义色板双色，跟随系统）。
 * 浅色对标 ChatGPT 大留白（近白底 #FFFFFF/#F7F7F8 系）；
 * 深色对标其纯黑底（#000000/#171717 系）。
 */
private val LightColors = lightColorScheme(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF7F7F8),
    primary = Color(0xFF0D0D0D),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF6E6E73),
    onSurface = Color(0xFF0D0D0D),
    onSurfaceVariant = Color(0xFF6E6E73),
    outline = Color(0xFFE5E5E5),
)

private val DarkColors = darkColorScheme(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF171717),
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF0D0D0D),
    secondary = Color(0xFFA3A3A3),
    onSurface = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFFA3A3A3),
    outline = Color(0xFF2E2E2E),
)

/** 圆角/间距/字号常量（全 App 统一从这里取，不就地写字面量）。 */
object XiaoduoDimens {
    val CornerCard = 16.dp
    val CornerBubble = 18.dp
    val CornerInputBar = 28.dp
    val SpaceS = 8.dp
    val SpaceM = 16.dp
    val SpaceL = 24.dp
}

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelMedium = TextStyle(fontSize = 12.sp),
)

private val AppShapes = Shapes(
    medium = RoundedCornerShape(XiaoduoDimens.CornerCard),
    large = RoundedCornerShape(XiaoduoDimens.CornerBubble),
)

@Composable
fun XiaoduoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
