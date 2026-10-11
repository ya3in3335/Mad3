package com.samtex.eidreader.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * SAMTEX visual identity: dark navy surfaces with an electric-blue accent.
 * Semantic names: [Primary] is the primary *text* colour; [Electric] is the brand accent.
 */
object SamtexColors {
    val Background = Color(0xFF070D1F)
    val SecondaryBackground = Color(0xFF101A33)
    val Elevated = Color(0xFF16233F)
    val Primary = Color(0xFFE8EEFA)
    val Muted = Color(0xFF8FA1C7)
    val Border = Color(0xFF24365F)
    val Electric = Color(0xFF2F7BFF)
    val ElectricGlow = Color(0xFF38BDF8)
    val OnElectric = Color(0xFFFFFFFF)
    val Accent = Electric
    val Success = Color(0xFF34D399)
    val Error = Color(0xFFFB7185)
    val Warning = Color(0xFFFBBF24)
    val Info = Color(0xFF60A5FA)
}

private val colorScheme = darkColorScheme(
    primary = SamtexColors.Electric,
    onPrimary = SamtexColors.OnElectric,
    primaryContainer = SamtexColors.Elevated,
    onPrimaryContainer = SamtexColors.Primary,
    secondary = SamtexColors.ElectricGlow,
    onSecondary = SamtexColors.Background,
    tertiary = SamtexColors.Success,
    background = SamtexColors.Background,
    onBackground = SamtexColors.Primary,
    surface = SamtexColors.Background,
    onSurface = SamtexColors.Primary,
    surfaceVariant = SamtexColors.SecondaryBackground,
    onSurfaceVariant = SamtexColors.Muted,
    surfaceContainerLowest = SamtexColors.Background,
    surfaceContainerLow = SamtexColors.SecondaryBackground,
    surfaceContainer = SamtexColors.SecondaryBackground,
    surfaceContainerHigh = SamtexColors.Elevated,
    surfaceContainerHighest = SamtexColors.Elevated,
    outline = SamtexColors.Border,
    outlineVariant = SamtexColors.Border,
    error = SamtexColors.Error,
    onError = SamtexColors.Background,
)

private val shapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)

private val typography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 4.sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun SamtexTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme, shapes = shapes, typography = typography, content = content)
}
