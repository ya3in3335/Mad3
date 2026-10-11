package com.samtex.eidreader.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object SamtexColors {
    val Background = Color(0xFFFFFFFF)
    val Primary = Color(0xFF111827)
    val Accent = Color(0xFF10B981)
    val SecondaryBackground = Color(0xFFF3F4F6)
    val Muted = Color(0xFF6B7280)
    val Border = Color(0xFFE5E7EB)
    val Error = Color(0xFFDC2626)
    val Warning = Color(0xFFD97706)
    val Info = Color(0xFF2563EB)
}

private val colorScheme = lightColorScheme(
    primary = SamtexColors.Primary,
    onPrimary = Color.White,
    secondary = SamtexColors.Accent,
    onSecondary = Color.White,
    tertiary = SamtexColors.Accent,
    background = SamtexColors.Background,
    onBackground = SamtexColors.Primary,
    surface = SamtexColors.Background,
    onSurface = SamtexColors.Primary,
    surfaceVariant = SamtexColors.SecondaryBackground,
    onSurfaceVariant = SamtexColors.Muted,
    surfaceContainer = SamtexColors.SecondaryBackground,
    surfaceContainerHigh = SamtexColors.Background,
    surfaceContainerHighest = SamtexColors.SecondaryBackground,
    outline = SamtexColors.Border,
    outlineVariant = SamtexColors.Border,
    error = SamtexColors.Error,
    onError = Color.White,
)

private val shapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)

private val typography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 4.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun SamtexTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme, shapes = shapes, typography = typography, content = content)
}
