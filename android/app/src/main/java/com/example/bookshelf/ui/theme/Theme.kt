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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bookshelf.domain.AppThemeMode
import com.example.bookshelf.domain.AppVisualTheme

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
    // Bookish theme tokens mirror docs/project-overview.html: paper, forest, ink, amber.
    val bookishLightPaper = Color(0xFFF2EEE3)
    val bookishLightPaperSecondary = Color(0xFFE7DFCE)
    val bookishLightCard = Color(0xD1FFFCF4)
    val bookishLightSolid = Color(0xFFFAF7EF)
    val bookishLightInk = Color(0xFF18231D)
    val bookishLightMuted = Color(0xFF687068)
    val bookishLightFaint = Color(0xFF9A9B8D)
    val bookishLightLine = Color(0x2418231D)
    val bookishLightStrongLine = Color(0x4D18231D)
    val bookishAmber = Color(0xFFC77932)
    val bookishRed = Color(0xFFA13F35)

    val bookishDarkPaper = Color(0xFF0C1511)
    val bookishDarkPaperSecondary = Color(0xFF14251C)
    val bookishDarkCard = Color(0xD614271D)
    val bookishDarkSolid = Color(0xFF16291F)
    val bookishDarkInk = Color(0xFFEEF4EE)
    val bookishDarkMuted = Color(0xFFAAB9AD)
    val bookishDarkFaint = Color(0xFF738177)
    val bookishDarkLine = Color(0x21EDF4EE)
    val bookishDarkStrongLine = Color(0x47EDF4EE)

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
    val darkBackdropBase = Color(0xFF0C1511)
    val darkBackdropTop = Color(0xFF14251C)
    val darkGlassFill = Color(0xFF14271D).copy(alpha = 0.16f)
    val darkGlassFillStrong = Color(0xFF1B3626).copy(alpha = 0.23f)
    val darkGlassStroke = Color(0xFFEFD5AF).copy(alpha = 0.14f)
    val darkGlassStrokeStrong = Color(0xFFEFB273).copy(alpha = 0.32f)
    val darkGlassHighlight = Color(0xFFF5F0E6).copy(alpha = 0.24f)
    val darkGlassRimWarm = Color(0xFFEFB273).copy(alpha = 0.18f)
    val darkGlassRimCool = Color(0xFF8FC2A1).copy(alpha = 0.16f)
    val darkGlassShadow = Color.Black.copy(alpha = 0.56f)

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
    primary = Color(0xFFEFB273),
    onPrimary = Color(0xFF10281C),
    primaryContainer = Color(0xFF5C3B20),
    onPrimaryContainer = Color(0xFFF5DBB7),
    secondary = Color(0xFF9BC2A3),
    onSecondary = Color(0xFF10281C),
    secondaryContainer = Color(0xFF214331),
    onSecondaryContainer = Color(0xFFD9EADD),
    tertiary = Color(0xFFD7A16D),
    onTertiary = Color(0xFF10281C),
    tertiaryContainer = Color(0xFF453321),
    onTertiaryContainer = Color(0xFFF2D5B2),
    error = Color(0xFFFA423E),
    onError = CodexGray1000,
    errorContainer = Color(0xFF5C1C1B),
    onErrorContainer = Color(0xFFFFDAD8),
    background = GlassTokens.darkBackdropBase,
    onBackground = CodexGray0,
    surface = GlassTokens.darkGlassFill,
    onSurface = CodexGray0,
    surfaceVariant = Color(0xFF1B2F24),
    onSurfaceVariant = Color(0xFFB4C6B8),
    surfaceContainerHighest = Color(0xFF223B2B),
    outline = Color(0xFFEFB273).copy(alpha = 0.30f),
    outlineVariant = CodexGray0.copy(alpha = 0.08f),
    scrim = Color.Black.copy(alpha = 0.76f),
    inverseSurface = CodexGray100,
    inverseOnSurface = CodexGray900,
)

private val BookishLightColors = lightColorScheme(
    primary = Color(0xFF17382C),
    onPrimary = Color(0xFFF2EEE3),
    primaryContainer = Color(0xFFD7E4D9),
    onPrimaryContainer = Color(0xFF17382C),
    secondary = Color(0xFFC77932),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2D8BC),
    onSecondaryContainer = Color(0xFF4A2710),
    tertiary = Color(0xFF285341),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD9E8DC),
    onTertiaryContainer = Color(0xFF17382C),
    error = Color(0xFFA13F35),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD4),
    onErrorContainer = Color(0xFF3C0603),
    background = GlassTokens.bookishLightPaper,
    onBackground = GlassTokens.bookishLightInk,
    surface = GlassTokens.bookishLightCard,
    onSurface = GlassTokens.bookishLightInk,
    surfaceVariant = GlassTokens.bookishLightPaperSecondary,
    onSurfaceVariant = GlassTokens.bookishLightMuted,
    surfaceContainerHighest = GlassTokens.bookishLightSolid,
    outline = GlassTokens.bookishLightStrongLine,
    outlineVariant = GlassTokens.bookishLightLine,
    scrim = Color(0x6618231D),
    inverseSurface = Color(0xFF285341),
    inverseOnSurface = Color(0xFFF2EEE3),
)

private val BookishDarkColors = darkColorScheme(
    primary = Color(0xFF8FC2A1),
    onPrimary = Color(0xFF10281C),
    primaryContainer = Color(0xFF285341),
    onPrimaryContainer = Color(0xFFB3D9BD),
    secondary = Color(0xFFEFB273),
    onSecondary = Color(0xFF3B210D),
    secondaryContainer = Color(0xFF5C3B20),
    onSecondaryContainer = Color(0xFFF5DBB7),
    tertiary = Color(0xFFB3D9BD),
    onTertiary = Color(0xFF10281C),
    tertiaryContainer = Color(0xFF214331),
    onTertiaryContainer = Color(0xFFD9EADD),
    error = Color(0xFFEF8D82),
    onError = Color(0xFF3C0603),
    errorContainer = Color(0xFF5C1C1B),
    onErrorContainer = Color(0xFFFFDAD8),
    background = GlassTokens.bookishDarkPaper,
    onBackground = GlassTokens.bookishDarkInk,
    surface = GlassTokens.bookishDarkCard,
    onSurface = GlassTokens.bookishDarkInk,
    surfaceVariant = GlassTokens.bookishDarkPaperSecondary,
    onSurfaceVariant = GlassTokens.bookishDarkMuted,
    surfaceContainerHighest = GlassTokens.bookishDarkSolid,
    outline = GlassTokens.bookishDarkStrongLine,
    outlineVariant = GlassTokens.bookishDarkLine,
    scrim = Color.Black.copy(alpha = 0.76f),
    inverseSurface = Color(0xFFE7DFCE),
    inverseOnSurface = Color(0xFF18231D),
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

/** Bookish is editorial rather than futuristic: quieter weight, more generous serif headings, and
 * tighter paper-card corners that echo the backend project overview. */
private val BookishTypography = PageShelfTypography.copy(
    headlineLarge = PageShelfTypography.headlineLarge.copy(
        fontSize = 34.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.2).sp,
    ),
    headlineMedium = PageShelfTypography.headlineMedium.copy(
        fontSize = 27.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    ),
    titleLarge = PageShelfTypography.titleLarge.copy(
        fontSize = 22.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    ),
    titleMedium = PageShelfTypography.titleMedium.copy(
        fontSize = 17.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    ),
    bodyLarge = PageShelfTypography.bodyLarge.copy(lineHeight = 25.sp),
    bodyMedium = PageShelfTypography.bodyMedium.copy(lineHeight = 22.sp),
)
/** A considered corner hierarchy — small for chips/covers, large for bars, extra-large for sheets. */
private val PageShelfShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val BookishShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(18.dp),
)
val LocalAppVisualTheme = staticCompositionLocalOf { AppVisualTheme.LIQUID_GLASS }

@Composable
fun PageShelfTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    visualTheme: AppVisualTheme = AppVisualTheme.LIQUID_GLASS,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    val scheme = when (visualTheme) {
        AppVisualTheme.LIQUID_GLASS -> if (dark) DarkColors else LightColors
        AppVisualTheme.BOOKISH -> if (dark) BookishDarkColors else BookishLightColors
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = if (visualTheme == AppVisualTheme.BOOKISH) BookishTypography else PageShelfTypography,
        shapes = if (visualTheme == AppVisualTheme.BOOKISH) BookishShapes else PageShelfShapes,
    ) {
        CompositionLocalProvider(
            LocalAppVisualTheme provides visualTheme,
            LocalContentColor provides scheme.onSurface,
        ) {
            content()
        }
    }
}