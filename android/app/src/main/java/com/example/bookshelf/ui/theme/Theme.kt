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

/**
 * Liquid Glass design tokens.
 *
 * The palette is built around Apple's "Liquid Glass" look: translucent, glossy surfaces that sit
 * on a softly blurred, colorful backdrop. `glassFill` is the translucent fill of every glass
 * surface, `glassStroke` the bright edge, `glassHighlight` the specular sheen.
 */
object GlassTokens {
    // Light
    val lightBackdropBase = Color(0xFFF2F5FB)
    val lightBackdropTop = Color(0xFFEAF3FF)
    val lightGlassFill = Color(0xE6FFFFFF)          // ~90% white frosted glass
    val lightGlassFillStrong = Color(0xF2FFFFFF)
    val lightGlassStroke = Color(0x59FFFFFF)        // bright edge (35% white)
    val lightGlassStrokeStrong = Color(0x8CFFFFFF)
    val lightGlassHighlight = Color(0xB3FFFFFF)     // specular sheen (70% white)
    val lightGlassShadow = Color(0x1F1B4E6B)

    // Dark
    val darkBackdropBase = Color(0xFF0A0E16)
    val darkBackdropTop = Color(0xFF101828)
    val darkGlassFill = Color(0x1FFFFFFF)           // ~12% white over deep backdrop
    val darkGlassFillStrong = Color(0x2EFFFFFF)
    val darkGlassStroke = Color(0x3DFFFFFF)
    val darkGlassStrokeStrong = Color(0x66FFFFFF)
    val darkGlassHighlight = Color(0x38FFFFFF)
    val darkGlassShadow = Color(0x66000000)

    // Accent blobs for the animated aurora backdrop
    val auroraLight = listOf(
        Color(0xFF8BD8FF), // sky
        Color(0xFFC3B6FF), // lilac
        Color(0xFF9BE8D2), // mint
        Color(0xFFFFD9A0), // warm sand
    )
    val auroraDark = listOf(
        Color(0xFF2B6FBE),
        Color(0xFF6B4FBF),
        Color(0xFF1F8A6E),
        Color(0xFFB96A2B),
    )
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF042F2A),
    secondary = Color(0xFFC2672A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE4C9),
    onSecondaryContainer = Color(0xFF4A2404),
    tertiary = Color(0xFF6D5BC7),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE7E0FF),
    onTertiaryContainer = Color(0xFF1E1250),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = GlassTokens.lightBackdropBase,
    onBackground = Color(0xFF17202A),
    surface = GlassTokens.lightGlassFill,
    onSurface = Color(0xFF17202A),
    surfaceVariant = Color(0xFFE3E9F2),
    onSurfaceVariant = Color(0xFF5C6470),
    surfaceContainerHighest = Color(0xFFE9EEF6),
    outline = Color(0xFFB9C2CE),
    outlineVariant = Color(0xFFDCE3EC),
    scrim = Color(0x660A0E16),
    inverseSurface = Color(0xFF2C3540),
    inverseOnSurface = Color(0xFFF0F4FA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF3DD6C3),
    onPrimary = Color(0xFF043B35),
    primaryContainer = Color(0xFF11524A),
    onPrimaryContainer = Color(0xFFB7F4EA),
    secondary = Color(0xFFFFB877),
    onSecondary = Color(0xFF4A2404),
    secondaryContainer = Color(0xFF6E3A0D),
    onSecondaryContainer = Color(0xFFFFDCC0),
    tertiary = Color(0xFFC9BFFF),
    onTertiary = Color(0xFF2E2266),
    tertiaryContainer = Color(0xFF51449C),
    onTertiaryContainer = Color(0xFFE7E0FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = GlassTokens.darkBackdropBase,
    onBackground = Color(0xFFE4E9F0),
    surface = GlassTokens.darkGlassFill,
    onSurface = Color(0xFFE4E9F0),
    surfaceVariant = Color(0xFF2A3340),
    onSurfaceVariant = Color(0xFFB6C0CC),
    surfaceContainerHighest = Color(0xFF232C38),
    outline = Color(0xFF6B7684),
    outlineVariant = Color(0xFF2E3844),
    scrim = Color(0x99000000),
    inverseSurface = Color(0xFFE4E9F0),
    inverseOnSurface = Color(0xFF2C3540),
)

private val BaseTypography = Typography()
private val HeadingFamily = FontFamily.Serif
private val BodyFamily = FontFamily.SansSerif
private val PageShelfTypography = Typography(
    displaySmall = BaseTypography.displaySmall.copy(
        fontFamily = HeadingFamily,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.8).sp,
    ),
    headlineLarge = BaseTypography.headlineLarge.copy(
        fontFamily = HeadingFamily,
        fontSize = 34.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = BaseTypography.headlineMedium.copy(
        fontFamily = HeadingFamily,
        fontSize = 28.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = BaseTypography.headlineSmall.copy(
        fontFamily = HeadingFamily,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = BaseTypography.titleLarge.copy(
        fontFamily = HeadingFamily,
        fontSize = 21.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = BaseTypography.titleMedium.copy(
        fontFamily = HeadingFamily,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = BaseTypography.titleSmall.copy(
        fontFamily = BodyFamily,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = BodyFamily, fontSize = 16.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = BodyFamily, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = BodyFamily, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = BodyFamily, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = BodyFamily, fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = BodyFamily, fontSize = 11.sp, letterSpacing = TextUnit.Unspecified),
)

/** Large, soft continuous corners — the signature of glass surfaces. */
private val PageShelfShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
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
