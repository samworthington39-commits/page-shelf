package com.example.bookshelf.ui.glass

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.bookshelf.domain.AppVisualTheme
import com.example.bookshelf.ui.theme.GlassTokens
import com.example.bookshelf.ui.theme.LocalAppVisualTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

enum class LiquidBackdropStyle { Study, Library }

/** A shared environmental layer that glass surfaces can sample at their window position. */
@Composable
fun LiquidBackdrop(
    modifier: Modifier = Modifier,
    style: LiquidBackdropStyle = LiquidBackdropStyle.Study,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val dark = isDarkScheme()
    val bookish = LocalAppVisualTheme.current == AppVisualTheme.BOOKISH
    val glassBackdrop = rememberGlassBackdrop()
    LaunchedEffect(glassBackdrop, bookish) {
        if (bookish) return@LaunchedEffect
        // A draw-phase heartbeat keeps recycled glass cards sampling the current backdrop while
        // a LazyColumn scrolls, even if Compose reuses their LayoutCoordinates and draw node.
        // Every tick re-records each glass surface's sample and the full content layer, so the
        // clock is deliberately capped at ~30fps: the sampled content is blurred and the two
        // screens that overlap during navigation transitions no longer re-render at 60fps.
        while (true) {
            withFrameNanos { }
            glassBackdrop.frameTick += 1
            delay(BACKDROP_TICK_MILLIS)
        }
    }
    // The washes drift on a 32-42s cycle; advancing the phase at the same throttled cadence
    // keeps the living backdrop without paying for a full-screen redraw on every display frame.
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(style, bookish) {
        if (bookish) {
            phase = 0f
            return@LaunchedEffect
        }
        val cycleSeconds = if (style == LiquidBackdropStyle.Library) 32f else 42f
        val startedAt = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            phase = (((now - startedAt) / 1_000_000_000.0) % cycleSeconds / cycleSeconds).toFloat()
            delay(BACKDROP_TICK_MILLIS)
        }
    }

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
            when {
                bookish -> BookishBackdrop(
                    dark = dark,
                    width = availableWidth,
                    height = availableHeight,
                )
                style == LiquidBackdropStyle.Study -> StudyBackdrop(
                    dark = dark,
                    phase = phase,
                    width = availableWidth,
                    height = availableHeight,
                )
                else -> LibraryBackdropScene(dark = dark, phase = phase)
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
private fun BoxScope.BookishBackdrop(
    dark: Boolean,
    width: Dp,
    height: Dp,
) {
    val paper = if (dark) GlassTokens.bookishDarkPaper else GlassTokens.bookishLightPaper
    val paperSecondary = if (dark) GlassTokens.bookishDarkPaperSecondary else GlassTokens.bookishLightPaperSecondary
    val ink = if (dark) GlassTokens.bookishDarkInk else GlassTokens.bookishLightInk
    val forest = if (dark) GlassTokens.bookishDarkMuted else GlassTokens.backendForest
    val amber = if (dark) Color(0xFFEFB273) else GlassTokens.bookishAmber
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(paperSecondary, paper)))
        val spacing = 76.dp.toPx()
        val line = ink.copy(alpha = if (dark) 0.09f else 0.10f)
        var x = 0f
        while (x <= size.width) {
            drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.7.dp.toPx())
            x += spacing
        }
        var y = 0f
        while (y <= size.height) {
            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.7.dp.toPx())
            y += spacing
        }
        drawCircle(
            color = forest.copy(alpha = if (dark) 0.08f else 0.045f),
            radius = size.width * 0.40f,
            center = Offset(size.width * 1.02f, size.height * 0.04f),
            style = Stroke(width = 1.dp.toPx()),
        )
        drawCircle(
            color = forest.copy(alpha = if (dark) 0.06f else 0.035f),
            radius = size.width * 0.58f,
            center = Offset(size.width * 1.02f, size.height * 0.04f),
            style = Stroke(width = 1.dp.toPx()),
        )
        drawRect(
            Brush.linearGradient(
                colors = listOf(Color.Transparent, amber.copy(alpha = if (dark) 0.10f else 0.07f), Color.Transparent),
                start = Offset(size.width * 0.08f, size.height * 0.92f),
                end = Offset(size.width * 0.80f, size.height * 0.20f),
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
        listOf(Color(0xFF0C1511), Color(0xFF10281C), Color(0xFF14251C))
    } else {
        listOf(Color(0xFFF7F4EC), Color(0xFFF3F0E8), Color(0xFFFAF8F2))
    }
    // Keep the scene quiet, but give the glass something real to reveal and refract. The Gitee CSS
    // reference places the overlay over a structured background; these low-saturation washes are
    // the app's equivalent environment layer for screens that do not have an image backdrop.
    val coolWash = if (dark) Color(0xFF8FC2A1).copy(alpha = 0.08f)
    else Color(0xFFB7C1B8).copy(alpha = 0.12f)
    val softWash = if (dark) Color(0xFFEFB273).copy(alpha = 0.055f)
    else Color(0xFFD5C7B8).copy(alpha = 0.12f)
    val sheen = if (dark) Color.White.copy(alpha = 0.02f)
    else Color.White.copy(alpha = 0.13f)

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

        // The CSS reference places the glass over a fine grid. A restrained environmental
        // lattice serves the same optical purpose here: it gives the SDF lens real structure
        // to bend while remaining quiet enough behind reading content.
        val gridSpacing = 28.dp.toPx()
        val fineGrid = if (dark) Color.White.copy(alpha = 0.024f)
        else Color(0xFF676C67).copy(alpha = 0.060f)
        val majorGrid = if (dark) Color.White.copy(alpha = 0.040f)
        else Color(0xFF5B615C).copy(alpha = 0.082f)
        var gridIndex = 0
        var x = 0f
        while (x <= size.width) {
            drawLine(
                color = if (gridIndex % 4 == 0) majorGrid else fineGrid,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = if (gridIndex % 4 == 0) 0.70.dp.toPx() else 0.45.dp.toPx(),
            )
            x += gridSpacing
            gridIndex += 1
        }
        gridIndex = 0
        var y = 0f
        while (y <= size.height) {
            drawLine(
                color = if (gridIndex % 4 == 0) majorGrid else fineGrid,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = if (gridIndex % 4 == 0) 0.70.dp.toPx() else 0.45.dp.toPx(),
            )
            y += gridSpacing
            gridIndex += 1
        }

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

private const val BACKDROP_TICK_MILLIS = 33L
