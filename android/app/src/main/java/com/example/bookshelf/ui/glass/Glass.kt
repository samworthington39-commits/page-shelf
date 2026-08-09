package com.example.bookshelf.ui.glass

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.bookshelf.ui.theme.GlassTokens
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** The translucent fill, bright edge, specular sheen, and shadow for the current theme. */
internal data class GlassPalette(
    val fill: Color,
    val fillStrong: Color,
    val stroke: Color,
    val strokeStrong: Color,
    val highlight: Color,
    val shadow: Color,
)

/** Whether the ACTIVE theme is dark — derived from MaterialTheme so it always matches the
 * user's theme preference, not just the system setting. */
@Composable
internal fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

@Composable
internal fun glassPalette(): GlassPalette {
    val dark = isDarkScheme()
    return if (dark) {
        GlassPalette(
            fill = GlassTokens.darkGlassFill,
            fillStrong = GlassTokens.darkGlassFillStrong,
            stroke = GlassTokens.darkGlassStroke,
            strokeStrong = GlassTokens.darkGlassStrokeStrong,
            highlight = GlassTokens.darkGlassHighlight,
            shadow = GlassTokens.darkGlassShadow,
        )
    } else {
        GlassPalette(
            fill = GlassTokens.lightGlassFill,
            fillStrong = GlassTokens.lightGlassFillStrong,
            stroke = GlassTokens.lightGlassStroke,
            strokeStrong = GlassTokens.lightGlassStrokeStrong,
            highlight = GlassTokens.lightGlassHighlight,
            shadow = GlassTokens.lightGlassShadow,
        )
    }
}

/**
 * A calm study backdrop: warm paper with very faint, slow-moving color washes and a soft vignette.
 * It adds atmosphere without ever fighting the text — readability comes first.
 */
@Composable
fun LiquidBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val dark = isDarkScheme()
    val washes = if (dark) GlassTokens.washDark else GlassTokens.washLight
    val base = if (dark) GlassTokens.darkBackdropBase else GlassTokens.lightBackdropBase
    val top = if (dark) GlassTokens.darkBackdropTop else GlassTokens.lightBackdropTop
    val transition = rememberInfiniteTransition(label = "study-wash")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 42_000, easing = LinearEasing)),
        label = "wash-phase",
    )
    BoxWithConstraints(
        modifier = modifier.background(Brush.verticalGradient(listOf(top, base))),
        contentAlignment = Alignment.Center,
    ) {
        val width = maxWidth
        val height = maxHeight
        val density = LocalDensity.current
        val widthPx = with(density) { width.toPx() }
        val heightPx = with(density) { height.toPx() }
        val seeds = remember(width, height) {
            List(3) { i -> WashSeed(i) }
        }
        for (i in washes.indices) {
            val seed = seeds[i % seeds.size]
            val angle = phase * 2.0 * PI + seed.phaseOffset
            val xF = seed.baseX + seed.driftX * sin(angle).toFloat()
            val yF = seed.baseY + seed.driftY * cos(angle * 0.6).toFloat()
            val scale = seed.scale + 0.05f * sin(angle * 0.4).toFloat()
            val alpha = if (dark) 0.11f else 0.20f
            Box(
                Modifier
                    .offset { IntOffset((xF * widthPx).roundToInt(), (yF * heightPx).roundToInt()) }
                    .size(width * scale)
                    .blur(if (dark) 90.dp else 72.dp)
                    .background(washes[i].copy(alpha = alpha), CircleShape),
            )
        }
        // Soft vignette for depth without contrast loss.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color.Transparent, if (dark) Color.Black.copy(alpha = 0.10f) else Color(0xFF6B5A3F).copy(alpha = 0.05f)),
                        center = Offset(widthPx / 2f, heightPx * 0.42f),
                        radius = widthPx * 0.75f,
                    ),
                ),
        )
        content()
    }
}

private data class WashSeed(
    val baseX: Float,
    val baseY: Float,
    val driftX: Float,
    val driftY: Float,
    val scale: Float,
    val phaseOffset: Double,
)

private fun WashSeed(index: Int): WashSeed {
    val random = Random(index * 104729 + 7)
    return WashSeed(
        baseX = -0.08f + random.nextFloat() * 0.35f,
        baseY = -0.06f + random.nextFloat() * 0.45f,
        driftX = 0.05f + random.nextFloat() * 0.10f,
        driftY = 0.04f + random.nextFloat() * 0.09f,
        scale = 0.7f + random.nextFloat() * 0.5f,
        phaseOffset = random.nextDouble() * PI * 2,
    )
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
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = glassPalette()
    val fill = tint ?: palette.fill
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
            .background(fill)
            .then(if (specular) Modifier.background(sheen, shape) else Modifier)
            // Liquid Glass edges are defined by refracted light, not a hard outline. The
            // stroke stays a hairline at low alpha (green in light mode) so it reads as an
            // edge glow while content keeps strong separation.
            .border(borderWidth, palette.stroke, shape),
    ) {
        // Bright rim-light layer at the top edge — the "strong light layer" that makes the
        // surface feel like a thin sheet of glass lit from above.
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to palette.highlight,
                            0.18f to palette.highlight.copy(alpha = palette.highlight.alpha * 0.35f),
                            1f to Color.Transparent,
                        ),
                    ),
                ),
        )
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

/**
 * Scales a composable down slightly while pressed and springs back on release — the tactile
 * "liquid" response used across glass controls.
 */
@Composable
fun rememberPressScale(interactionSource: MutableInteractionSource): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "glass-press-scale",
    )
    return scale
}

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
