package com.example.bookshelf.ui.glass

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import com.example.bookshelf.ui.theme.GlassTokens
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Returns the translucent glass fill, bright edge, and specular sheen for the current theme. */
internal data class GlassPalette(
    val fill: Color,
    val fillStrong: Color,
    val stroke: Color,
    val strokeStrong: Color,
    val highlight: Color,
    val shadow: Color,
)

@Composable
internal fun glassPalette(): GlassPalette {
    val dark = isSystemInDarkTheme()
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
 * The animated aurora backdrop behind every screen. Slowly drifting, heavily blurred color blobs
 * give glass surfaces something luminous to refract — the essence of the Liquid Glass look.
 */
@Composable
fun LiquidBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val dark = isSystemInDarkTheme()
    val blobs = if (dark) GlassTokens.auroraDark else GlassTokens.auroraLight
    val base = if (dark) GlassTokens.darkBackdropBase else GlassTokens.lightBackdropBase
    val top = if (dark) GlassTokens.darkBackdropTop else GlassTokens.lightBackdropTop
    val transition = rememberInfiniteTransition(label = "liquid-backdrop")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 24_000, easing = LinearEasing)),
        label = "backdrop-phase",
    )
    val phase2 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 31_000, easing = LinearEasing)),
        label = "backdrop-phase-2",
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
        // Deterministic per-composition offsets so the scene doesn't reshuffle on rotation.
        val seeds = remember(width, height) {
            List(4) { i -> AuroraSeed(i, width, height) }
        }
        for (i in blobs.indices) {
            val seed = seeds[i % seeds.size]
            val p = if (i % 2 == 0) phase else phase2
            val angle = p * 2.0 * PI
            val xF = seed.baseX + seed.driftX * sin(angle + seed.phaseOffset).toFloat()
            val yF = seed.baseY + seed.driftY * cos(angle * 0.7 + seed.phaseOffset).toFloat()
            val scale = seed.scale + 0.12f * sin(angle * 0.5 + seed.phaseOffset).toFloat()
            val alpha = seed.alpha
            Box(
                Modifier
                    .offset { IntOffset((xF * widthPx).roundToInt(), (yF * heightPx).roundToInt()) }
                    .size((width * scale))
                    .blur(if (dark) 64.dp else 48.dp)
                    .background(blobs[i].copy(alpha = alpha), CircleShape),
            )
        }
        content()
    }
}

private data class AuroraSeed(
    val baseX: Float,
    val baseY: Float,
    val driftX: Float,
    val driftY: Float,
    val scale: Float,
    val alpha: Float,
    val phaseOffset: Double,
)

private fun AuroraSeed(index: Int, width: Dp, height: Dp): AuroraSeed {
    val random = Random(index * 7919 + 17)
    val scale = 0.42f + random.nextFloat() * 0.42f
    return AuroraSeed(
        baseX = -0.12f + random.nextFloat() * 0.5f,
        baseY = -0.12f + random.nextFloat() * 0.6f,
        driftX = 0.06f + random.nextFloat() * 0.14f,
        driftY = 0.05f + random.nextFloat() * 0.12f,
        scale = scale,
        alpha = if (width > 600.dp) 0.34f else 0.42f,
        phaseOffset = random.nextDouble() * PI * 2,
    )
}


/**
 * A translucent glass panel: frosted fill, a bright Liquid-Glass edge, a soft drop shadow and a
 * specular sheen that catches the light along the top edge.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
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
            0.32f to palette.highlight.copy(alpha = palette.highlight.alpha * 0.45f),
            1f to Color.Transparent,
        ),
    )
    Box(
        modifier = modifier
            .then(
                if (shadow) {
                    Modifier.shadow(
                        elevation = 12.dp,
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
            .border(borderWidth, palette.stroke, shape),
    ) {
        content()
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
 * Scales a composable down slightly while it is pressed and springs back when released — the
 * tactile "liquid" response used across glass controls.
 */
@Composable
fun rememberPressScale(interactionSource: MutableInteractionSource): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.955f else 1f,
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
    animationSpec = tween(420, easing = FastOutSlowInEasing),
) + androidx.compose.animation.slideInVertically(
    animationSpec = tween(460, easing = FastOutSlowInEasing),
    initialOffsetY = { it / 2 },
) + androidx.compose.animation.scaleIn(
    initialScale = 0.965f,
    animationSpec = tween(460, easing = FastOutSlowInEasing),
)

internal fun glassExit() = androidx.compose.animation.fadeOut(
    animationSpec = tween(220, easing = FastOutSlowInEasing),
) + androidx.compose.animation.slideOutVertically(
    animationSpec = tween(240, easing = FastOutSlowInEasing),
    targetOffsetY = { it / 4 },
)

