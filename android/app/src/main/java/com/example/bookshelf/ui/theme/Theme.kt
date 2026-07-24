package com.example.bookshelf.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bookshelf.domain.AppThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF17382C),
    onPrimary = Color(0xFFF5F0E6),
    primaryContainer = Color(0xFFD8E3DA),
    onPrimaryContainer = Color(0xFF10271F),
    secondary = Color(0xFFC77932),
    onSecondary = Color(0xFF20160D),
    secondaryContainer = Color(0xFFF0D7BD),
    onSecondaryContainer = Color(0xFF5C3014),
    tertiary = Color(0xFF95521F),
    background = Color(0xFFF2EEE3),
    onBackground = Color(0xFF18231D),
    surface = Color(0xFFFAF7EF),
    onSurface = Color(0xFF18231D),
    surfaceVariant = Color(0xFFE7DFCE),
    onSurfaceVariant = Color(0xFF696B62),
    outline = Color(0xFFCFC5B1),
    outlineVariant = Color(0xFFE0D7C6),
    error = Color(0xFFA13F35),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9BC6AE),
    onPrimary = Color(0xFF0C261B),
    primaryContainer = Color(0xFF285341),
    onPrimaryContainer = Color(0xFFD8E7DD),
    secondary = Color(0xFFD99A5D),
    onSecondary = Color(0xFF3D210C),
    secondaryContainer = Color(0xFF69401F),
    onSecondaryContainer = Color(0xFFFFDFC1),
    tertiary = Color(0xFFEDB47C),
    background = Color(0xFF101B16),
    onBackground = Color(0xFFE9ECE6),
    surface = Color(0xFF17251F),
    onSurface = Color(0xFFE9ECE6),
    surfaceVariant = Color(0xFF25362E),
    onSurfaceVariant = Color(0xFFB7C1BA),
    outline = Color(0xFF536A5E),
    outlineVariant = Color(0xFF30483C),
    error = Color(0xFFFFB4AB),
)

private val BaseTypography = Typography()
private val HeadingFamily = FontFamily.Serif
private val BodyFamily = FontFamily.SansSerif
private val PageShelfTypography = Typography(
    displaySmall = BaseTypography.displaySmall.copy(fontFamily = HeadingFamily, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = HeadingFamily, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = HeadingFamily, fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = BaseTypography.titleLarge.copy(fontFamily = HeadingFamily, fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontFamily = HeadingFamily, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = BodyFamily),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = BodyFamily, fontSize = 14.sp),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = BodyFamily),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = BodyFamily, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = BodyFamily),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = BodyFamily, letterSpacing = TextUnit.Unspecified),
)

private val PageShelfShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

@Composable
fun PageShelfTheme(themeMode: AppThemeMode = AppThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = PageShelfTypography,
        shapes = PageShelfShapes,
        content = content,
    )
}
