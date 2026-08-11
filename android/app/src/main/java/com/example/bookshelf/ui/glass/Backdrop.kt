package com.example.bookshelf.ui.glass

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

enum class LiquidBackdropStyle { Study, Library }

/** A shared environmental layer that glass surfaces can sample at their window position. */
@Composable
fun LiquidBackdrop(
    modifier: Modifier = Modifier,
    style: LiquidBackdropStyle = LiquidBackdropStyle.Study,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val dark = isDarkScheme()
    val glassBackdrop = rememberGlassBackdrop()
    val transition = rememberInfiniteTransition(label = "backdrop-motion")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(
                durationMillis = if (style == LiquidBackdropStyle.Library) 32_000 else 42_000,
                easing = LinearEasing,
            ),
        ),
        label = "backdrop-phase",
    )

    BoxWithConstraints(
        modifier = modifier.clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        Box(
            Modifier
                .fillMaxSize()
                .captureGlassBackdrop(glassBackdrop),
        ) {
            when (style) {
                LiquidBackdropStyle.Study -> StudyBackdrop(
                    dark = dark,
                    phase = phase,
                    width = availableWidth,
                    height = availableHeight,
                )
                LiquidBackdropStyle.Library -> LibraryBackdropScene(dark = dark, phase = phase)
            }
        }
        CompositionLocalProvider(LocalGlassBackdrop provides glassBackdrop) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = content)
        }
    }
}

@Composable
private fun BoxScope.StudyBackdrop(
    dark: Boolean,
    phase: Float,
    width: Dp,
    height: Dp,
) {
    val washes = if (dark) GlassTokens.washDark else GlassTokens.washLight
    val base = if (dark) GlassTokens.darkBackdropBase else GlassTokens.lightBackdropBase
    val top = if (dark) GlassTokens.darkBackdropTop else GlassTokens.lightBackdropTop
    val density = LocalDensity.current
    val widthPx = with(density) { width.toPx() }
    val heightPx = with(density) { height.toPx() }
    val seeds = remember(width, height) { List(3) { index -> WashSeed(index) } }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, base)))) {
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
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Transparent,
                            if (dark) Color.Black.copy(alpha = 0.10f)
                            else Color(0xFF6B5A3F).copy(alpha = 0.05f),
                        ),
                        center = Offset(widthPx / 2f, heightPx * 0.42f),
                        radius = widthPx * 0.75f,
                    ),
                ),
        )
    }
}

@Composable
private fun LibraryBackdropScene(dark: Boolean, phase: Float) {
    val tau = (phase * PI * 2.0).toFloat()
    val sway = sin(tau)
    val lift = cos(tau * 0.74f)
    val baseColors = if (dark) {
        listOf(Color(0xFF05070A), Color(0xFF080D16), Color(0xFF0B1220))
    } else {
        listOf(Color(0xFFF7F4EC), Color(0xFFF3F0E8), Color(0xFFFAF8F2))
    }
    val coolWash = if (dark) Color(0xFF339CFF).copy(alpha = 0.065f)
    else Color(0xFFB7C1B8).copy(alpha = 0.075f)
    val softWash = if (dark) Color(0xFF1D4ED8).copy(alpha = 0.040f)
    else Color(0xFFD5C7B8).copy(alpha = 0.085f)
    val sheen = if (dark) Color.White.copy(alpha = 0.015f)
    else Color.White.copy(alpha = 0.10f)

    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(baseColors))

        val horizontalShift = sway * size.width * 0.18f
        drawRect(
            Brush.linearGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.36f to coolWash,
                    0.68f to Color.Transparent,
                ),
                start = Offset(-size.width * 0.35f + horizontalShift, 0f),
                end = Offset(size.width * 0.86f + horizontalShift, size.height),
            ),
        )
        drawRect(
            Brush.linearGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.46f to softWash,
                    0.78f to Color.Transparent,
                ),
                start = Offset(size.width * 1.18f - horizontalShift, size.height * 0.18f),
                end = Offset(-size.width * 0.16f - horizontalShift, size.height),
            ),
        )
        drawRect(
            Brush.linearGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.48f to sheen,
                    0.58f to Color.Transparent,
                ),
                start = Offset(size.width * (0.06f + sway * 0.05f), 0f),
                end = Offset(size.width * (0.80f + sway * 0.05f), size.height * (0.72f + lift * 0.03f)),
            ),
        )
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
