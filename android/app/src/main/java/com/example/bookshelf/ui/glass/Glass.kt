package com.example.bookshelf.ui.glass

import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.bookshelf.domain.AppVisualTheme
import com.example.bookshelf.ui.theme.GlassTokens
import com.example.bookshelf.ui.theme.LocalAppVisualTheme
import kotlin.math.max
import androidx.compose.runtime.withFrameNanos

/** The translucent fill, bright edge, specular sheen, and shadow for the current theme. */
internal data class GlassPalette(
    val liquidOptics: Boolean,
    val fill: Color,
    val fillStrong: Color,
    val stroke: Color,
    val strokeStrong: Color,
    val highlight: Color,
    val rimWarm: Color,
    val rimCool: Color,
    val shadow: Color,
)

/** Optical density presets. Reader chrome uses a thicker, more strongly diffusing glass slab. */
enum class GlassMaterial { Standard, ReaderChrome }

/**
 * Rendering cost policy for a glass surface. Auto follows the device capability.
 *
 * [BackdropBlur] keeps the translucent Gaussian-blurred look but only samples the
 * environmental backdrop and light layers. It never records the dynamic content layer
 * and never creates the liquid-optics runtime shader, so it is safe for surfaces that
 * are themselves rendered inside [GlassOpticsKt.captureGlassBackdropContent].
 */
enum class GlassPerformance { Auto, High, Low, Static, BackdropBlur }

/**
 * A draw-only invalidation hook for transient parent animations. AnimatedVisibility can move
 * the chrome through a parent graphics layer without redrawing the sampled child layer. Reader
 * screens provide this pulse only during their enter/exit transition.
 */
internal val LocalGlassMotionInvalidation =
    androidx.compose.runtime.staticCompositionLocalOf<(() -> Float)?> { null }

@Composable
internal fun rememberGlassVisibilityPulse(
    active: Boolean,
    durationMillis: Int = 720,
): () -> Float {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(active) {
        val durationNanos = durationMillis.coerceAtLeast(1) * 1_000_000L
        val startedAt = withFrameNanos { it }
        var now = startedAt
        while (now - startedAt < durationNanos) {
            now = withFrameNanos { it }
            frame += 1
        }
    }
    return remember { { frame.toFloat() } }
}

/** Whether the ACTIVE theme is dark — derived from MaterialTheme so it always matches the
 * user's theme preference, not just the system setting. */
@Composable
internal fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

@Composable
internal fun glassPalette(): GlassPalette {
    val dark = isDarkScheme()
    val bookish = LocalAppVisualTheme.current == AppVisualTheme.BOOKISH
    return when {
        bookish && dark -> GlassPalette(
            liquidOptics = false,
            fill = GlassTokens.bookishDarkCard,
            fillStrong = GlassTokens.bookishDarkSolid,
            stroke = GlassTokens.bookishDarkLine,
            strokeStrong = GlassTokens.bookishDarkStrongLine,
            highlight = Color.White.copy(alpha = 0.08f),
            rimWarm = GlassTokens.bookishAmber.copy(alpha = 0.18f),
            rimCool = GlassTokens.bookishDarkMuted.copy(alpha = 0.12f),
            shadow = Color.Black.copy(alpha = 0.28f),
        )
        bookish -> GlassPalette(
            liquidOptics = false,
            fill = GlassTokens.bookishLightCard,
            fillStrong = GlassTokens.bookishLightSolid,
            stroke = GlassTokens.bookishLightLine,
            strokeStrong = GlassTokens.bookishLightStrongLine,
            highlight = Color.White.copy(alpha = 0.22f),
            rimWarm = GlassTokens.bookishAmber.copy(alpha = 0.18f),
            rimCool = GlassTokens.backendForestSoft.copy(alpha = 0.10f),
            shadow = Color(0xFF232A20).copy(alpha = 0.14f),
        )
        dark -> GlassPalette(
            liquidOptics = true,
            fill = GlassTokens.darkGlassFill,
            fillStrong = GlassTokens.darkGlassFillStrong,
            stroke = GlassTokens.darkGlassStroke,
            strokeStrong = GlassTokens.darkGlassStrokeStrong,
            highlight = GlassTokens.darkGlassHighlight,
            rimWarm = GlassTokens.darkGlassRimWarm,
            rimCool = GlassTokens.darkGlassRimCool,
            shadow = GlassTokens.darkGlassShadow,
        )
        else -> GlassPalette(
            liquidOptics = true,
            fill = GlassTokens.lightGlassFill,
            fillStrong = GlassTokens.lightGlassFillStrong,
            stroke = GlassTokens.lightGlassStroke,
            strokeStrong = GlassTokens.lightGlassStrokeStrong,
            highlight = GlassTokens.lightGlassHighlight,
            rimWarm = GlassTokens.lightGlassRimWarm,
            rimCool = GlassTokens.lightGlassRimCool,
            shadow = GlassTokens.lightGlassShadow,
        )
    }
}
@Composable
private fun BookishSurface(
    modifier: Modifier,
    shape: Shape,
    tint: Color?,
    shadow: Boolean,
    borderWidth: Dp,
    material: GlassMaterial,
    tintOpacity: Float?,
    enabled: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = isDarkScheme()
    val baseFill = tint ?: if (material == GlassMaterial.ReaderChrome) {
        if (dark) GlassTokens.bookishDarkSolid else GlassTokens.bookishLightSolid
    } else {
        if (dark) GlassTokens.bookishDarkCard else GlassTokens.bookishLightCard
    }
    val fill = baseFill.copy(
        alpha = (tintOpacity ?: baseFill.alpha).coerceIn(0f, 1f) * if (enabled) 1f else 0.72f,
    )
    val border = if (dark) GlassTokens.bookishDarkStrongLine else GlassTokens.bookishLightStrongLine
    val accent = MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.32f else 0.22f)
    Box(
        modifier = modifier
            .then(
                if (shadow) {
                    Modifier.shadow(
                        elevation = 2.dp,
                        shape = shape,
                        ambientColor = if (dark) Color.Black.copy(alpha = 0.22f) else Color(0xFF232A20).copy(alpha = 0.10f),
                        spotColor = if (dark) Color.Black.copy(alpha = 0.24f) else Color(0xFF232A20).copy(alpha = 0.10f),
                    )
                } else Modifier
            )
            .clip(shape)
            .background(fill)
            .drawBehind {
                // A printed rule replaces the optical rim: quiet, editorial, and recognisable as
                // a separate paper system rather than a disabled glass card.
                drawLine(
                    color = accent,
                    start = Offset(0f, 1.dp.toPx()),
                    end = Offset(size.width, 1.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .border(borderWidth, border, shape),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            content()
        }
    }
}
/**
 * A frosted glass panel: mostly-opaque warm fill for readability, a thin bright edge, a faint
 * top sheen, and a soft low shadow.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    tint: Color? = null,
    specular: Boolean = true,
    shadow: Boolean = true,
    borderWidth: Dp = 1.dp,
    material: GlassMaterial = GlassMaterial.Standard,
    interactionProgress: Float = 0f,
    sampleMotionOffset: (() -> Float)? = null,
    tintOpacity: Float? = null,
    blurRadius: Dp? = null,
    edgeBand: Dp? = null,
    bendStrength: Dp? = null,
    dispersion: Dp? = null,
    specularStrength: Float = 0.17f,
    interactionSource: MutableInteractionSource? = null,
    enabled: Boolean = true,
    hoverEnabled: Boolean = true,
    performance: GlassPerformance = GlassPerformance.Auto,
    content: @Composable BoxScope.() -> Unit,
) {
    if (LocalAppVisualTheme.current == AppVisualTheme.BOOKISH) {
        BookishSurface(
            modifier = modifier,
            shape = shape,
            tint = tint,
            shadow = shadow,
            borderWidth = borderWidth,
            material = material,
            tintOpacity = tintOpacity,
            enabled = enabled,
            content = content,
        )
        return
    }
    val palette = glassPalette()
    val motionInvalidation = sampleMotionOffset ?: LocalGlassMotionInvalidation.current
    val baseTint = tint ?: when (material) {
        GlassMaterial.Standard -> palette.fill
        GlassMaterial.ReaderChrome -> palette.fillStrong
    }
    val fill = baseTint.copy(
        alpha = (tintOpacity ?: baseTint.alpha)
            .coerceIn(0f, 1f) * if (enabled) 1f else 0.72f,
    )
    val backdrop = LocalGlassBackdrop.current
    // Liquid Glass samples the shared environment; Bookish intentionally uses a quieter paper/card
    // stack so the app matches the backend overview instead of looking like frosted chrome.
    val sampleBackdrop = palette.liquidOptics && backdrop != null && performance != GlassPerformance.Static
    val resolvedPerformance = when (performance) {
        GlassPerformance.Auto -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GlassPerformance.High
        } else {
            GlassPerformance.Low
        }
        else -> performance
    }
    // Press drives a soft light reaction: the touched surface brightens and the glow is shared
    // through the backdrop so neighbouring glass bends it back at their edges (edge reflections).
    val surfaceInteraction = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by surfaceInteraction.collectIsPressedAsState()
    val opticalProgress by animateFloatAsState(
        targetValue = max(interactionProgress.coerceIn(0f, 1f), if (pressed) 1f else 0f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "liquid-glass-interaction",
    )
    val sheen = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to palette.highlight,
            0.25f to palette.highlight.copy(alpha = palette.highlight.alpha * 0.4f),
            1f to Color.Transparent,
        ),
    )
    Box(
        modifier = modifier
            .then(
                if (shadow) {
                    Modifier.shadow(
                        elevation = 6.dp,
                        shape = shape,
                        ambientColor = palette.shadow,
                        spotColor = palette.shadow,
                    )
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .then(
                if (sampleBackdrop) {
                    Modifier.glassOptics(
                        backdrop = requireNotNull(backdrop),
                        shape = shape,
                        palette = palette,
                        fill = fill,
                        specular = specular,
                        borderWidth = borderWidth,
                        material = material,
                        interactionProgress = opticalProgress,
                        sampleMotionOffset = motionInvalidation,
                        blurRadius = blurRadius,
                        edgeBand = edgeBand,
                        bendStrength = bendStrength,
                        dispersion = dispersion,
                        specularStrength = specularStrength.coerceIn(0f, 0.25f),
                        performance = resolvedPerformance,
                        useLiquidOptics = performance != GlassPerformance.BackdropBlur,
                        sampleDynamicContent = performance != GlassPerformance.BackdropBlur,
                    )
                } else {
                    Modifier
                        .background(fill)
                        .then(
                            if (specular && palette.liquidOptics) Modifier.background(
                                sheen,
                                shape,
                            )
                            else Modifier,
                        )
                        .border(
                            borderWidth,
                            if (palette.liquidOptics) palette.stroke else palette.strokeStrong,
                            shape,
                        )
                },
            ),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            content()
        }
    }
}

/** Alias for callers that want to name the material rather than its layout surface. */
@Composable
fun LiquidGlass(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    tint: Color? = null,
    tintOpacity: Float? = null,
    blurRadius: Dp? = null,
    edgeBand: Dp? = null,
    bendStrength: Dp? = null,
    dispersion: Dp? = null,
    specularStrength: Float = 0.17f,
    specular: Boolean = true,
    shadow: Boolean = true,
    borderWidth: Dp = 1.dp,
    material: GlassMaterial = GlassMaterial.Standard,
    interactionSource: MutableInteractionSource? = null,
    enabled: Boolean = true,
    hoverEnabled: Boolean = true,
    performance: GlassPerformance = GlassPerformance.Auto,
    content: @Composable BoxScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        shape = shape,
        tint = tint,
        tintOpacity = tintOpacity,
        blurRadius = blurRadius,
        edgeBand = edgeBand,
        bendStrength = bendStrength,
        dispersion = dispersion,
        specularStrength = specularStrength,
        specular = specular,
        shadow = shadow,
        borderWidth = borderWidth,
        material = material,
        interactionSource = interactionSource,
        enabled = enabled,
        hoverEnabled = hoverEnabled,
        performance = performance,
        content = content,
    )
}

/** A circular glass tile used for floating icon buttons. */
@Composable
fun GlassIconTile(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    shape: Shape = CircleShape,
    content: @Composable BoxScope.() -> Unit,
) {
    GlassSurface(modifier = modifier.size(size), shape = shape, specular = true, shadow = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
fun rememberPressProgress(interactionSource: MutableInteractionSource): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val progress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "glass-press-progress",
    )
    return progress
}

/** Scales down on contact and springs back while the optical edge responds separately. */
@Composable
fun rememberPressScale(interactionSource: MutableInteractionSource): Float =
    1f - rememberPressProgress(interactionSource) * 0.018f

/** Standard glass entrance: a soft fade + rise with a hint of scale. */
internal fun glassEnter() = androidx.compose.animation.fadeIn(
    animationSpec = tween(360, easing = FastOutSlowInEasing),
) + androidx.compose.animation.slideInVertically(
    animationSpec = tween(380, easing = FastOutSlowInEasing),
    initialOffsetY = { it / 2 },
) + androidx.compose.animation.scaleIn(
    initialScale = 0.975f,
    animationSpec = tween(380, easing = FastOutSlowInEasing),
)

internal fun glassExit() = androidx.compose.animation.fadeOut(
    animationSpec = tween(200, easing = FastOutSlowInEasing),
) + androidx.compose.animation.slideOutVertically(
    animationSpec = tween(220, easing = FastOutSlowInEasing),
    targetOffsetY = { it / 4 },
)