package com.example.bookshelf.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bookshelf.domain.AppThemeMode

/**
 * Liquid Glass tokens tuned for readability: warm paper, ink text, one lamp-glow accent.
 *
 * The "页架" identity is a quiet study — paper, ink, and the reading lamp. Glass surfaces stay
 * translucent enough to feel alive, but opaque enough that body text keeps strong contrast.
 */
object GlassTokens {
    // Light: warm paper (matches backend login --paper / --card), green-tinted edges
    val lightBackdropBase = Color(0xFFF2EDDF)
    val lightBackdropTop = Color(0xFFF7F2E7)
    val lightGlassFill = Color(0xD9FDFAF2)          // ~85% translucent frosted glass (Liquid Glass shows content behind)
    val lightGlassFillStrong = Color(0xF2FDFAF2)
    val lightGlassStroke = Color(0x332F5D4F)     // forest-green hairline edge (勾边)
    val lightGlassStrokeStrong = Color(0x552F5D4F)
    val lightGlassHighlight = Color(0x99FFFFFF)     // bright specular light layer
    val lightGlassShadow = Color(0x262F5D4F)

    // Dark: deep warm charcoal
    val darkBackdropBase = Color(0xFF171310)
    val darkBackdropTop = Color(0xFF1E1915)
    val darkGlassFill = Color(0xB826211B)           // ~72% warm dark frosted glass
    val darkGlassFillStrong = Color(0xD92E2821)
    val darkGlassStroke = Color(0x1AFFFFFF)
    val darkGlassStrokeStrong = Color(0x2EFFFFFF)
    val darkGlassHighlight = Color(0x14FFFFFF)
    val darkGlassShadow = Color(0x99000000)

    // Backdrop color washes — calm, low saturation, low alpha (readable).
    val washLight = listOf(
        Color(0xFFE8DFC8), // warm parchment
        Color(0xFFD8E2D4), // sage
        Color(0xFFEAD9C4), // lamp cream
    )
    val washDark = listOf(
        Color(0xFF3A2E1E), // ember brown
        Color(0xFF24362C), // deep sage
        Color(0xFF3B2A1D), // warm umber
    )
}

private val LightColors = lightColorScheme(
    primary = Color(0xFFC77932),          // backend login amber (暖黄为主)
    onPrimary = Color(0xFF20160D),
    primaryContainer = Color(0xFFF2DDBF),
    onPrimaryContainer = Color(0xFF3C2407),
    secondary = Color(0xFF2F5D4F),        // forest green
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E4DA),
    onSecondaryContainer = Color(0xFF0F2A22),
    tertiary = Color(0xFF77654F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEADECB),
    onTertiaryContainer = Color(0xFF2B2010),
    error = Color(0xFFA94438),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD4),
    onErrorContainer = Color(0xFF3C0603),
    background = GlassTokens.lightBackdropBase,
    onBackground = Color(0xFF262019),
    surface = GlassTokens.lightGlassFill,
    onSurface = Color(0xFF262019),
    surfaceVariant = Color(0xFFE9E1D2),
    onSurfaceVariant = Color(0xFF4A443B),
    surfaceContainerHighest = Color(0xFFEDE5D6),
    outline = Color(0xFF6E8A7B),          // green-tinted outline
    outlineVariant = Color(0xFFCBD8CE),
    scrim = Color(0x6617130E),
    inverseSurface = Color(0xFF302B24),
    inverseOnSurface = Color(0xFFF7F1E6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FC3B0),
    onPrimary = Color(0xFF12332A),
    primaryContainer = Color(0xFF2A4A40),
    onPrimaryContainer = Color(0xFFD3E9E0),
    secondary = Color(0xFFE0A267),
    onSecondary = Color(0xFF3A2408),
    secondaryContainer = Color(0xFF5A3A16),
    onSecondaryContainer = Color(0xFFFFDCBB),
    tertiary = Color(0xFFC9B6A4),
    onTertiary = Color(0xFF332617),
    tertiaryContainer = Color(0xFF4C3D2C),
    onTertiaryContainer = Color(0xFFEADDC9),
    error = Color(0xFFFFB4A6),
    onError = Color(0xFF5C1209),
    errorContainer = Color(0xFF7A2D24),
    onErrorContainer = Color(0xFFFFDAD4),
    background = GlassTokens.darkBackdropBase,
    onBackground = Color(0xFFEDE5D7),
    surface = GlassTokens.darkGlassFill,
    onSurface = Color(0xFFEDE5D7),
    surfaceVariant = Color(0xFF2E2821),
    onSurfaceVariant = Color(0xFFC9BFAE),
    surfaceContainerHighest = Color(0xFF251F19),
    outline = Color(0xFF6F6557),
    outlineVariant = Color(0xFF393229),
    scrim = Color(0xB3000000),
    inverseSurface = Color(0xFFEDE5D7),
    inverseOnSurface = Color(0xFF302B24),
)

private val BaseTypography = Typography()
private val HeadingFamily = FontFamily.Serif
private val BodyFamily = FontFamily.SansSerif

/**
 * Editorial, bookish type: serif (Song) for titles, crisp sans for body. Body sizes are kept a
 * touch larger than default so long reading lists stay comfortable.
 */
private val PageShelfTypography = Typography(
    displaySmall = BaseTypography.displaySmall.copy(
        fontFamily = HeadingFamily,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.6).sp,
    ),
    headlineLarge = BaseTypography.headlineLarge.copy(
        fontFamily = HeadingFamily,
        fontSize = 32.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = BaseTypography.headlineMedium.copy(
        fontFamily = HeadingFamily,
        fontSize = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = BaseTypography.headlineSmall.copy(
        fontFamily = HeadingFamily,
        fontSize = 21.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = BaseTypography.titleLarge.copy(
        fontFamily = HeadingFamily,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = BaseTypography.titleMedium.copy(
        fontFamily = HeadingFamily,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = BaseTypography.titleSmall.copy(
        fontFamily = BodyFamily,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = BodyFamily, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = BodyFamily, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = BodyFamily, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = BodyFamily, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = BodyFamily, fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = BodyFamily, fontSize = 11.sp, letterSpacing = TextUnit.Unspecified),
)

/** A considered corner hierarchy — small for chips/covers, large for bars, extra-large for sheets. */
private val PageShelfShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PageShelfTheme(themeMode: AppThemeMode = AppThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    val scheme = if (dark) DarkColors else LightColors
    MaterialTheme(
        colorScheme = scheme,
        typography = PageShelfTypography,
        shapes = PageShelfShapes,
    ) {
        // Pin the content color to the active scheme so default-colored text stays readable
        // everywhere, including directly over the backdrop.
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
            content()
        }
    }
}
