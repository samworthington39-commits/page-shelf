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

private val BackendForest = Color(0xFF17382C)
private val BackendForestSoft = Color(0xFF285341)
private val BackendSuccess = Color(0xFF2D6B4F)
private val BackendLiveGreen = Color(0xFF78BD8F)
private val BackendInkOnGreen = Color(0xFFF5F0E6)
private val GlassMintEdge = Color(0xFF9FB9AD)
private val GlassWarmEdge = Color(0xFFD9C7B8)
private val CodexGray0 = Color(0xFFFFFFFF)
private val CodexGray100 = Color(0xFFEDEDED)
private val CodexGray900 = Color(0xFF181818)
private val CodexGray1000 = Color(0xFF0D0D0D)
private val CodexGreen = Color(0xFF40C977)
private val CodexBlue = Color(0xFF339CFF)

/**
 * Liquid Glass tokens tuned for readability: warm paper, ink text, and the backend forest green.
 *
 * The "页架" identity is a quiet study — paper, ink, and the reading lamp. Glass surfaces stay
 * translucent enough to feel alive, but opaque enough that body text keeps strong contrast.
 */
object GlassTokens {
    val backendForest = BackendForest
    val backendForestSoft = BackendForestSoft
    val backendSuccess = BackendSuccess
    val backendLiveGreen = BackendLiveGreen
    val backendInkOnGreen = BackendInkOnGreen
    val backendPaper = Color(0xFFF2EEE3)
    val backendCard = Color(0xFFFAF7EF)
    val libraryDarkBase = CodexGray900

    // Light: the same shallow warm paper used by the reader. Keeping the environment and
    // material in one narrow hue range prevents transparent cards changing color while moving.
    val lightBackdropBase = Color(0xFFF6F2E9)
    val lightBackdropTop = Color(0xFFFAF8F2)
    // Keep the material tint in the optical range (roughly 0.05..0.14 alpha) so backdrop
    // detail remains visible through the center of a surface.
    val lightGlassFill = Color(0x0DFAF8F2)
    val lightGlassFillStrong = Color(0x12FAF8F2)
    val lightGlassStroke = Color(0xFF747873).copy(alpha = 0.16f)
    val lightGlassStrokeStrong = Color(0xFF4F5550).copy(alpha = 0.23f)
    // The bright return is deliberately restrained; most of the perceived edge should come
    // from the sampled backdrop folding around the contour, not from a painted white border.
    val lightGlassHighlight = Color.White.copy(alpha = 0.48f)
    val lightGlassRimWarm = GlassWarmEdge.copy(alpha = 0.15f)
    val lightGlassRimCool = GlassMintEdge.copy(alpha = 0.14f)
    val lightGlassShadow = Color(0xFF4A4B48).copy(alpha = 0.18f)

    // Dark: the same transparent optical material, tuned cooler and dimmer for night reading.
    val darkBackdropBase = Color(0xFF05070A)
    val darkBackdropTop = Color(0xFF0B1220)
    val darkGlassFill = Color(0xFF0E1726).copy(alpha = 0.16f)
    val darkGlassFillStrong = Color(0xFF111C2D).copy(alpha = 0.23f)
    val darkGlassStroke = CodexGray0.copy(alpha = 0.13f)
    val darkGlassStrokeStrong = Color(0xFF5EA9FF).copy(alpha = 0.30f)
    val darkGlassHighlight = CodexGray0.copy(alpha = 0.28f)
    val darkGlassRimWarm = Color(0xFFA78BFA).copy(alpha = 0.15f)
    val darkGlassRimCool = Color(0xFF63C4FF).copy(alpha = 0.20f)
    val darkGlassShadow = Color.Black.copy(alpha = 0.52f)

    // Backdrop color washes — calm, low saturation, low alpha (readable).
    val washLight = listOf(
        Color(0xFFE8DFC8), // warm parchment
        Color(0xFFD8E2D4), // sage
        Color(0xFFEAD9C4), // lamp cream
    )
    val washDark = listOf(
        Color(0xFF0A1020),
        Color(0xFF0D1728),
        Color(0xFF101B30),
    )
}

private val LightColors = lightColorScheme(
    primary = BackendForest,
    onPrimary = BackendInkOnGreen,
    primaryContainer = Color(0xFFD6DBD3),
    onPrimaryContainer = BackendForest,
    secondary = BackendSuccess,
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
    primary = CodexBlue,
    onPrimary = CodexGray1000,
    primaryContainer = Color(0xFF163B63),
    onPrimaryContainer = Color(0xFFD7E9FF),
    secondary = Color(0xFF8CC4FF),
    onSecondary = CodexGray1000,
    secondaryContainer = Color(0xFF1B3554),
    onSecondaryContainer = Color(0xFFE5F1FF),
    tertiary = CodexGreen,
    onTertiary = CodexGray1000,
    tertiaryContainer = Color(0xFF173D25),
    onTertiaryContainer = Color(0xFFD8F8E1),
    error = Color(0xFFFA423E),
    onError = CodexGray1000,
    errorContainer = Color(0xFF5C1C1B),
    onErrorContainer = Color(0xFFFFDAD8),
    background = GlassTokens.darkBackdropBase,
    onBackground = CodexGray0,
    surface = GlassTokens.darkGlassFill,
    onSurface = CodexGray0,
    surfaceVariant = Color(0xFF111827),
    onSurfaceVariant = CodexGray0.copy(alpha = 0.70f),
    surfaceContainerHighest = Color(0xFF182235),
    outline = Color(0xFF5EA9FF).copy(alpha = 0.28f),
    outlineVariant = CodexGray0.copy(alpha = 0.08f),
    scrim = Color.Black.copy(alpha = 0.76f),
    inverseSurface = CodexGray100,
    inverseOnSurface = CodexGray900,
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
