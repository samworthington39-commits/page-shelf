package com.example.bookshelf.ui.glass

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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.bookshelf.ui.theme.GlassTokens
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
    return if (dark) {
        GlassPalette(
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
    } else {
        GlassPalette(
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
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = glassPalette()
    val motionInvalidation = sampleMotionOffset ?: LocalGlassMotionInvalidation.current
    val fill = tint ?: when (material) {
        GlassMaterial.Standard -> palette.fill
        GlassMaterial.ReaderChrome -> palette.fillStrong.copy(
            alpha = 0.02f,
        )
    }
    val backdrop = LocalGlassBackdrop.current
    val sampleBackdrop = backdrop != null && (
        palette.liquidOptics || material == GlassMaterial.ReaderChrome
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
                        interactionProgress = interactionProgress,
                        sampleMotionOffset = motionInvalidation,
                    )
                } else {
                    Modifier
                        .background(fill)
                        .then(
                            if (specular && palette.liquidOptics) Modifier.background(sheen, shape)
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
