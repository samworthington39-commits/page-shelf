package com.example.bookshelf.ui.glass

import android.graphics.Bitmap
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader as AndroidShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Recorded environment and dynamic content layers shared by glass surfaces on one screen. */
@Stable
internal class GlassBackdrop internal constructor(
    val layer: GraphicsLayer,
    val dynamicContentLayer: GraphicsLayer,
    val lightLayer: GraphicsLayer,
) {
    var coordinates: LayoutCoordinates? by mutableStateOf(null)
    var dynamicContentCoordinates: LayoutCoordinates? by mutableStateOf(null)
    /** Draw-phase clock used to invalidate recycled LazyColumn glass surfaces in place. */
    var frameTick by mutableIntStateOf(0)
    /** Press light source in window coordinates, shared by every glass surface on this screen. */
    var touchLight: Offset? by mutableStateOf(null)
    var touchLightStrength: Float by mutableFloatStateOf(0f)
    var touchLightOwner: Any? by mutableStateOf(null)
}

internal val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

@Composable
internal fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    val dynamicContentLayer = rememberGraphicsLayer()
    val lightLayer = rememberGraphicsLayer()
    return remember(layer, dynamicContentLayer, lightLayer) {
        GlassBackdrop(layer, dynamicContentLayer, lightLayer)
    }
}

/** Records only the environmental layer, keeping glass controls out of their own sample. */
internal fun Modifier.captureGlassBackdrop(backdrop: GlassBackdrop): Modifier =
    onGloballyPositioned { coordinates ->
        if (coordinates.isAttached) backdrop.coordinates = coordinates
    }.drawWithCache {
        onDrawWithContent {
            // Keep the captured environmental layer live even when its content is moved by a
            // scrolling parent rather than recomposed. This is intentionally read in draw phase
            // so it invalidates rendering without rebuilding the modifier cache.
            val frameTick = backdrop.frameTick
            if (frameTick == Int.MIN_VALUE) return@onDrawWithContent
            backdrop.layer.record(size = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())) {
                this@onDrawWithContent.drawContent()
            }
            // The press glow lives in its own layer so glass surfaces can sample it without
            // painting a bright pool directly onto the visible background.
            backdrop.lightLayer.record(
                size = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt()),
            ) {
                val light = backdrop.touchLight
                val strength = backdrop.touchLightStrength
                val own = backdrop.coordinates
                if (light != null && strength > 0.001f && own?.isAttached == true) {
                    val center = light - own.positionInWindow()
                    val radius = max(size.width, size.height) * TOUCH_LIGHT_RADIUS_FRACTION
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to Color.White.copy(alpha = 0.95f * strength),
                                0.22f to Color.White.copy(alpha = 0.38f * strength),
                                0.55f to Color.White.copy(alpha = 0.10f * strength),
                                1f to Color.Transparent,
                            ),
                            center = center,
                            radius = radius,
                        ),
                        radius = radius,
                        center = center,
                    )
                }
            }
            drawLayer(backdrop.layer)
        }
    }

/**
 * Records the real screen content that moves behind floating glass without recording the glass
 * chrome itself. Apply this to the full-screen content layer below the toolbar overlay.
 */
internal fun Modifier.captureGlassBackdropContent(backdrop: GlassBackdrop): Modifier =
    onGloballyPositioned { coordinates ->
        if (coordinates.isAttached) backdrop.dynamicContentCoordinates = coordinates
    }.drawWithCache {
        onDrawWithContent {
            backdrop.dynamicContentLayer.record(
                size = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt()),
            ) {
                this@onDrawWithContent.drawContent()
            }
            drawLayer(backdrop.dynamicContentLayer)
        }
    }

/**
 * Samples the live backdrop at this surface's window position. API 33+ adds an SDF edge lens,
 * narrow RGB dispersion, and directional rim light; API 31-32 keeps the sampled blur.
 */
@Composable
internal fun Modifier.glassOptics(
    backdrop: GlassBackdrop,
    shape: Shape,
    palette: GlassPalette,
    fill: Color,
    specular: Boolean,
    borderWidth: Dp,
    material: GlassMaterial,
    interactionProgress: Float,
    sampleMotionOffset: (() -> Float)?,
    blurRadius: Dp?,
    edgeBand: Dp?,
    bendStrength: Dp?,
    dispersion: Dp?,
    specularStrength: Float,
    performance: GlassPerformance,
    useLiquidOptics: Boolean = true,
    sampleDynamicContent: Boolean = true,
): Modifier {
    val sampleLayer = rememberGraphicsLayer()
    val runtimeShader = remember(palette.liquidOptics, performance, useLiquidOptics) {
        if (
            useLiquidOptics &&
            palette.liquidOptics &&
            performance == GlassPerformance.High &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            runCatching { RuntimeShader(LIQUID_GLASS_SHADER) }.getOrNull()
        } else null
    }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var softwareBlurredSample by remember { mutableStateOf<ImageBitmap?>(null) }
    val density = LocalDensity.current
    val useSoftwareBlur = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    val usesLiquidOptics = useLiquidOptics && palette.liquidOptics
    val softwareBlurRadius = with(density) {
        val configured = blurRadius ?: when (material) {
            GlassMaterial.Standard -> if (usesLiquidOptics) 2.25.dp else 8.dp
            GlassMaterial.ReaderChrome -> if (usesLiquidOptics) 3.5.dp else 12.dp
        }
        (configured.toPx() / SOFTWARE_BLUR_DOWNSAMPLE).roundToInt().coerceAtLeast(2)
    }
    val softwareBlurBaseColor = fill.copy(alpha = 1f).toArgb()

    LaunchedEffect(sampleLayer, softwareBlurRadius, softwareBlurBaseColor, useSoftwareBlur) {
        if (!useSoftwareBlur) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val captured = runCatching { sampleLayer.toImageBitmap() }
                .getOrNull()
            if (captured != null && captured.width > 0 && captured.height > 0) {
                softwareBlurredSample = withContext(Dispatchers.Default) {
                    gaussianBlurFallback(
                        source = captured,
                        radius = softwareBlurRadius,
                        opaqueBaseColor = softwareBlurBaseColor,
                    )
                }
            }
            delay(SOFTWARE_BLUR_REFRESH_MS)
        }
    }

    // While this surface is pressed it owns the shared press light: it publishes its centre in
    // window coordinates and keeps the glow alive until the touch ends, then fades it out.
    val lightOwnerToken = remember(backdrop) { Any() }
    val lightActive = interactionProgress > 0.01f
    LaunchedEffect(backdrop, lightActive) {
        if (lightActive) {
            backdrop.touchLightOwner = lightOwnerToken
            backdrop.touchLightStrength = 1f
            while (true) {
                val own = coordinates
                if (own == null || !own.isAttached) break
                val centre = own.positionInWindow() +
                    Offset(own.size.width / 2f, own.size.height / 2f)
                backdrop.touchLight = centre
                delay(TOUCH_LIGHT_TICK_MILLIS)
            }
            if (backdrop.touchLightOwner === lightOwnerToken) {
                backdrop.touchLightOwner = null
                backdrop.touchLightStrength = 0f
                backdrop.touchLight = null
            }
        } else if (backdrop.touchLightOwner === lightOwnerToken) {
            while (backdrop.touchLightStrength > 0.02f) {
                backdrop.touchLightStrength *= TOUCH_LIGHT_DECAY
                delay(TOUCH_LIGHT_TICK_MILLIS)
            }
            backdrop.touchLightStrength = 0f
            backdrop.touchLight = null
            backdrop.touchLightOwner = null
        }
    }

    return onGloballyPositioned { current ->
        if (current.isAttached) coordinates = current
    }.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val cornerRadius = outline.cornerRadiusPx(size.minDimension)
        val usesLiquidOptics = useLiquidOptics && palette.liquidOptics
        val blurRadiusPx = (blurRadius ?: when (material) {
            GlassMaterial.Standard -> if (usesLiquidOptics) 2.25.dp else 8.dp
            GlassMaterial.ReaderChrome -> if (usesLiquidOptics) 3.5.dp else 12.dp
        }).toPx().coerceIn(0f, 32.dp.toPx()) * if (performance == GlassPerformance.Low) 0.65f else 1f
        val refractionBand = if (usesLiquidOptics) {
            min(
                (edgeBand ?: when (material) {
                    GlassMaterial.Standard -> 10.dp
                    GlassMaterial.ReaderChrome -> 13.dp
                }).toPx().coerceAtLeast(0.5f),
                size.minDimension * 0.30f,
            )
        } else {
            0f
        }
        val refractionStrength = if (usesLiquidOptics) {
            min(
                (bendStrength ?: when (material) {
                    GlassMaterial.Standard -> 5.5.dp
                    GlassMaterial.ReaderChrome -> 7.dp
                }).toPx().coerceAtLeast(0f),
                refractionBand * 0.58f,
            )
        } else {
            0f
        }
        val dispersion = if (usesLiquidOptics) {
            min(
                (dispersion ?: when (material) {
                    GlassMaterial.Standard -> 0.18.dp
                    GlassMaterial.ReaderChrome -> 0.32.dp
                }).toPx().coerceAtLeast(0f),
                refractionStrength * 0.06f,
            )
        } else {
            0f
        }
        val edgeWidth = borderWidth.toPx().coerceAtLeast(0.75f)
        // Refraction and blur need pixels from outside the component bounds. Capturing only the
        // exact surface rectangle clamps every outward sample at the edge, which flattens the
        // lens into a pale border. The padded crop keeps real neighbouring backdrop pixels.
        val samplePaddingPx = ceil(
            blurRadiusPx * 2.2f + refractionBand + refractionStrength * 2.2f,
        ).toInt().coerceAtLeast(2)
        if (!useSoftwareBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            sampleLayer.compositingStrategy = CompositingStrategy.Offscreen
            sampleLayer.renderEffect = if (usesLiquidOptics) {
                createGlassRenderEffect(
                    runtimeShader = runtimeShader,
                    surfaceWidth = size.width,
                    surfaceHeight = size.height,
                    samplePadding = samplePaddingPx.toFloat(),
                    radius = cornerRadius,
                    blurRadius = blurRadiusPx,
                    refractionBand = refractionBand,
                    refractionStrength = refractionStrength,
                    dispersion = dispersion,
                    specularStrength = specularStrength,
                    genericShape = outline is Outline.Generic,
                    ior = 1.47f,
                    interactionProgress = interactionProgress,
                ).asComposeRenderEffect()
            } else {
                AndroidRenderEffect.createBlurEffect(
                    blurRadiusPx,
                    blurRadiusPx,
                    AndroidShader.TileMode.CLAMP,
                ).asComposeRenderEffect()
            }
        } else {
            sampleLayer.renderEffect = null
        }

        val surfaceSheen = Brush.linearGradient(
            colorStops = arrayOf(
                0f to palette.highlight.copy(alpha = palette.highlight.alpha * 0.42f),
                0.16f to Color.Transparent,
                0.76f to Color.Transparent,
                1f to palette.highlight.copy(alpha = palette.highlight.alpha * 0.32f),
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        val innerDepthRim = Brush.linearGradient(
            colorStops = arrayOf(
                0f to palette.highlight.copy(alpha = palette.highlight.alpha * 0.24f),
                0.30f to Color.Transparent,
                0.64f to palette.shadow.copy(alpha = palette.shadow.alpha * 0.16f),
                1f to palette.shadow.copy(alpha = palette.shadow.alpha * 0.48f),
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        val ambientRim = Brush.linearGradient(
            colorStops = arrayOf(
                0f to palette.highlight.copy(alpha = palette.highlight.alpha * 0.34f),
                0.22f to palette.rimCool,
                0.58f to Color.Transparent,
                0.82f to palette.rimWarm,
                1f to palette.shadow.copy(alpha = palette.shadow.alpha * 0.26f),
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        val contourRim = Brush.linearGradient(
            colorStops = arrayOf(
                0f to palette.highlight.copy(alpha = palette.highlight.alpha * 0.82f),
                0.20f to palette.strokeStrong,
                0.52f to palette.stroke.copy(alpha = palette.stroke.alpha * 0.60f),
                0.80f to palette.rimCool.copy(alpha = palette.rimCool.alpha * 0.72f),
                1f to palette.strokeStrong,
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        val innerHighlight = Brush.linearGradient(
            colorStops = arrayOf(
                0f to palette.highlight,
                0.18f to palette.highlight.copy(alpha = palette.highlight.alpha * 0.76f),
                0.46f to Color.Transparent,
                0.78f to palette.highlight.copy(alpha = palette.highlight.alpha * 0.34f),
                1f to palette.highlight.copy(alpha = palette.highlight.alpha * 0.74f),
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )

        onDrawWithContent {
            // LazyColumn reuses the same draw node while changing its window position. The
            // backdrop clock forces this node to re-record and re-sample every frame, preventing
            // the optical layer from disappearing after a long scroll.
            val frameTick = backdrop.frameTick
            if (frameTick == Int.MIN_VALUE) return@onDrawWithContent
            // Modal sheets are positioned by a parent layout modifier. Observing the live anchor
            // offset here invalidates this draw while that parent moves, so the sampled backdrop
            // follows the sheet throughout opening and drag gestures.
            sampleMotionOffset?.invoke()
            // A LazyColumn repositions the existing LayoutCoordinates object while scrolling.
            // Resolve its numeric position during drawing instead of caching a stale offset.
            val sourceCoordinates = backdrop.coordinates
            val surfaceCoordinates = coordinates
            val sampleOffset = if (
                sourceCoordinates?.isAttached == true && surfaceCoordinates?.isAttached == true
            ) {
                surfaceCoordinates.positionInWindow() - sourceCoordinates.positionInWindow()
            } else {
                Offset.Zero
            }
            val dynamicContentCoordinates = backdrop.dynamicContentCoordinates
            val dynamicContentOffset = if (
                sampleDynamicContent &&
                dynamicContentCoordinates?.isAttached == true &&
                surfaceCoordinates?.isAttached == true
            ) {
                surfaceCoordinates.positionInWindow() -
                    dynamicContentCoordinates.positionInWindow()
            } else {
                null
            }
            val softwareBlur = useSoftwareBlur
            val paddedWidth = size.width + samplePaddingPx * 2f
            val paddedHeight = size.height + samplePaddingPx * 2f
            val layerSize = if (softwareBlur) {
                IntSize(
                    ceil(paddedWidth / SOFTWARE_BLUR_DOWNSAMPLE).toInt().coerceAtLeast(1),
                    ceil(paddedHeight / SOFTWARE_BLUR_DOWNSAMPLE).toInt().coerceAtLeast(1),
                )
            } else {
                IntSize(ceil(paddedWidth).toInt(), ceil(paddedHeight).toInt())
            }
            sampleLayer.record(size = layerSize) {
                if (softwareBlur) {
                    scale(
                        scaleX = 1f / SOFTWARE_BLUR_DOWNSAMPLE,
                        scaleY = 1f / SOFTWARE_BLUR_DOWNSAMPLE,
                        pivot = Offset.Zero,
                    ) {
                        translate(
                            -sampleOffset.x + samplePaddingPx,
                            -sampleOffset.y + samplePaddingPx,
                        ) {
                            drawLayer(backdrop.layer)
                            drawLayer(backdrop.lightLayer)
                        }
                        if (sampleDynamicContent) {
                            dynamicContentOffset?.let { offset ->
                                translate(
                                    -offset.x + samplePaddingPx,
                                    -offset.y + samplePaddingPx,
                                ) {
                                    drawLayer(backdrop.dynamicContentLayer)
                                }
                            }
                        }
                    }
                } else {
                    translate(
                        -sampleOffset.x + samplePaddingPx,
                        -sampleOffset.y + samplePaddingPx,
                    ) {
                        drawLayer(backdrop.layer)
                        drawLayer(backdrop.lightLayer)
                    }
                    if (sampleDynamicContent) {
                        dynamicContentOffset?.let { offset ->
                            translate(
                                -offset.x + samplePaddingPx,
                                -offset.y + samplePaddingPx,
                            ) {
                                drawLayer(backdrop.dynamicContentLayer)
                            }
                        }
                    }
                }
            }
            if (softwareBlur) {
                softwareBlurredSample?.let { blurred ->
                    drawImage(
                        image = blurred,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(blurred.width, blurred.height),
                        dstOffset = IntOffset(-samplePaddingPx, -samplePaddingPx),
                        dstSize = IntSize(
                            (blurred.width * SOFTWARE_BLUR_DOWNSAMPLE).roundToInt(),
                            (blurred.height * SOFTWARE_BLUR_DOWNSAMPLE).roundToInt(),
                        ),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            } else {
                translate(-samplePaddingPx.toFloat(), -samplePaddingPx.toFloat()) {
                    drawLayer(sampleLayer)
                }
            }
            drawRect(fill)
            // The touched surface itself picks up a soft local glow around the press point.
            if (interactionProgress > 0f) {
                val light = backdrop.touchLight
                val own = coordinates
                if (light != null && own?.isAttached == true) {
                    val center = light - own.positionInWindow()
                    val glowRadius = size.minDimension * 0.34f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to Color.White.copy(alpha = 0.55f * interactionProgress),
                                0.35f to Color.White.copy(alpha = 0.16f * interactionProgress),
                                1f to Color.Transparent,
                            ),
                            center = center,
                            radius = glowRadius,
                        ),
                        radius = glowRadius,
                        center = center,
                    )
                }
            }
            if (usesLiquidOptics) {
                if (specular) drawRect(surfaceSheen)
                drawOutline(
                    outline = outline,
                    brush = innerDepthRim,
                    style = Stroke(width = edgeWidth * 8f),
                )
                drawOutline(
                    outline = outline,
                    brush = ambientRim,
                    style = Stroke(width = edgeWidth * 4.8f),
                )
                drawOutline(
                    outline = outline,
                    brush = contourRim,
                    style = Stroke(width = edgeWidth * 2.2f),
                )
                drawOutline(
                    outline = outline,
                    brush = innerHighlight,
                    style = Stroke(width = edgeWidth * 0.9f),
                )
            } else {
                drawOutline(
                    outline = outline,
                    color = palette.strokeStrong,
                    style = Stroke(width = edgeWidth),
                )
            }
            drawContent()
        }
    }
}

private const val SOFTWARE_BLUR_DOWNSAMPLE = 6f
private const val SOFTWARE_BLUR_REFRESH_MS = 300L
private const val TOUCH_LIGHT_TICK_MILLIS = 16L
private const val TOUCH_LIGHT_DECAY = 0.82f
private const val TOUCH_LIGHT_RADIUS_FRACTION = 0.24f

/** Three separable box passes provide a deterministic Gaussian approximation for reader chrome. */
private fun gaussianBlurFallback(
    source: ImageBitmap,
    radius: Int,
    opaqueBaseColor: Int,
): ImageBitmap {
    val width = source.width
    val height = source.height
    val bitmap = source.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)
    val pixels = IntArray(width * height)
    val scratch = IntArray(pixels.size)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    compositeOverOpaqueBase(pixels, opaqueBaseColor)
    repeat(3) { boxBlurPass(pixels, scratch, width, height, radius) }
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap.asImageBitmap()
}

private fun compositeOverOpaqueBase(pixels: IntArray, baseColor: Int) {
    val baseRed = baseColor ushr 16 and 0xFF
    val baseGreen = baseColor ushr 8 and 0xFF
    val baseBlue = baseColor and 0xFF
    for (index in pixels.indices) {
        val color = pixels[index]
        val alpha = color ushr 24
        if (alpha == 255) continue
        val inverseAlpha = 255 - alpha
        val red = ((color ushr 16 and 0xFF) * alpha + baseRed * inverseAlpha) / 255
        val green = ((color ushr 8 and 0xFF) * alpha + baseGreen * inverseAlpha) / 255
        val blue = ((color and 0xFF) * alpha + baseBlue * inverseAlpha) / 255
        pixels[index] = packColor(255, red, green, blue)
    }
}

private fun boxBlurPass(
    pixels: IntArray,
    scratch: IntArray,
    width: Int,
    height: Int,
    radius: Int,
) {
    val kernel = radius * 2 + 1
    for (y in 0 until height) {
        val row = y * width
        var alpha = 0
        var red = 0
        var green = 0
        var blue = 0
        for (offset in -radius..radius) {
            val color = pixels[row + offset.coerceIn(0, width - 1)]
            alpha += color ushr 24
            red += color ushr 16 and 0xFF
            green += color ushr 8 and 0xFF
            blue += color and 0xFF
        }
        for (x in 0 until width) {
            scratch[row + x] = packColor(alpha / kernel, red / kernel, green / kernel, blue / kernel)
            val outgoing = pixels[row + (x - radius).coerceIn(0, width - 1)]
            val incoming = pixels[row + (x + radius + 1).coerceIn(0, width - 1)]
            alpha += (incoming ushr 24) - (outgoing ushr 24)
            red += (incoming ushr 16 and 0xFF) - (outgoing ushr 16 and 0xFF)
            green += (incoming ushr 8 and 0xFF) - (outgoing ushr 8 and 0xFF)
            blue += (incoming and 0xFF) - (outgoing and 0xFF)
        }
    }

    for (x in 0 until width) {
        var alpha = 0
        var red = 0
        var green = 0
        var blue = 0
        for (offset in -radius..radius) {
            val color = scratch[offset.coerceIn(0, height - 1) * width + x]
            alpha += color ushr 24
            red += color ushr 16 and 0xFF
            green += color ushr 8 and 0xFF
            blue += color and 0xFF
        }
        for (y in 0 until height) {
            pixels[y * width + x] = packColor(alpha / kernel, red / kernel, green / kernel, blue / kernel)
            val outgoing = scratch[(y - radius).coerceIn(0, height - 1) * width + x]
            val incoming = scratch[(y + radius + 1).coerceIn(0, height - 1) * width + x]
            alpha += (incoming ushr 24) - (outgoing ushr 24)
            red += (incoming ushr 16 and 0xFF) - (outgoing ushr 16 and 0xFF)
            green += (incoming ushr 8 and 0xFF) - (outgoing ushr 8 and 0xFF)
            blue += (incoming and 0xFF) - (outgoing and 0xFF)
        }
    }
}

private fun packColor(alpha: Int, red: Int, green: Int, blue: Int): Int =
    (alpha shl 24) or (red shl 16) or (green shl 8) or blue

private fun Outline.cornerRadiusPx(shortSide: Float): Float = when (this) {
    is Outline.Rounded -> min(
        min(roundRect.topLeftCornerRadius.x, roundRect.topRightCornerRadius.x),
        min(roundRect.bottomLeftCornerRadius.x, roundRect.bottomRightCornerRadius.x),
    )
    is Outline.Rectangle -> 0f
    is Outline.Generic -> shortSide * 0.5f
}

@RequiresApi(Build.VERSION_CODES.S)
private fun createGlassRenderEffect(
    runtimeShader: RuntimeShader?,
    surfaceWidth: Float,
    surfaceHeight: Float,
    samplePadding: Float,
    radius: Float,
    blurRadius: Float,
    refractionBand: Float,
    refractionStrength: Float,
    dispersion: Float,
    specularStrength: Float,
    genericShape: Boolean,
    ior: Float,
    interactionProgress: Float,
): AndroidRenderEffect {
    val blur = AndroidRenderEffect.createBlurEffect(
        blurRadius,
        blurRadius,
        AndroidShader.TileMode.CLAMP,
    )
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || runtimeShader == null) return blur

    return runCatching {
        runtimeShader.setFloatUniform("surfaceOrigin", samplePadding, samplePadding)
        runtimeShader.setFloatUniform("surfaceSize", surfaceWidth, surfaceHeight)
        runtimeShader.setFloatUniform("cornerRadius", radius)
        runtimeShader.setFloatUniform("edgeBand", refractionBand)
        runtimeShader.setFloatUniform("bend", refractionStrength)
        runtimeShader.setFloatUniform("dispersion", dispersion)
        runtimeShader.setFloatUniform("specularStrength", specularStrength)
        runtimeShader.setFloatUniform("genericShape", if (genericShape) 1f else 0f)
        runtimeShader.setFloatUniform("ior", ior)
        runtimeShader.setFloatUniform("interaction", interactionProgress.coerceIn(0f, 1f))
        val refraction = AndroidRenderEffect.createRuntimeShaderEffect(runtimeShader, "backdrop")
        AndroidRenderEffect.createChainEffect(refraction, blur)
    }.getOrElse { blur }
}

private const val LIQUID_GLASS_SHADER = """
uniform shader backdrop;
uniform float2 surfaceOrigin;
uniform float2 surfaceSize;
uniform float cornerRadius;
uniform float edgeBand;
uniform float bend;
uniform float dispersion;
uniform float specularStrength;
uniform float genericShape;
uniform float ior;
uniform float interaction;

float roundedRectDistance(float2 p, float2 halfSize, float radius) {
    float2 q = abs(p) - (halfSize - float2(radius));
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

float2 sdfGradient(float2 p, float2 halfSize, float radius) {
    // Finite differences keep the normal coupled to the exact SDF used by the mask. This is
    // stable across straight edges and rounded corners, unlike a hand-authored border normal.
    float epsilon = 1.0;
    float dx = roundedRectDistance(p + float2(epsilon, 0.0), halfSize, radius) -
        roundedRectDistance(p - float2(epsilon, 0.0), halfSize, radius);
    float dy = roundedRectDistance(p + float2(0.0, epsilon), halfSize, radius) -
        roundedRectDistance(p - float2(0.0, epsilon), halfSize, radius);
    return normalize(float2(dx, dy));
}

half4 main(float2 coord) {
    float2 halfSize = surfaceSize * 0.5;
    float2 centered = coord - surfaceOrigin - halfSize;
    float safeRadius = max(0.0, min(cornerRadius, min(halfSize.x, halfSize.y) - 0.5));
    float distance = roundedRectDistance(centered, halfSize, safeRadius);
    if (distance > 0.0) return backdrop.eval(coord);
    float insideDistance = max(-distance, 0.0);
    float edgeWeight = 1.0 - smoothstep(0.0, max(edgeBand, 0.5), insideDistance);
    if (genericShape > 0.5 || edgeWeight <= 0.001) return backdrop.eval(coord);

    float2 normal = sdfGradient(centered, halfSize, safeRadius);
    float edgeWeightSquared = edgeWeight * edgeWeight;
    float activeBend = bend * (1.0 + interaction * 0.15);
    float activeDispersion = min(dispersion * (1.0 + interaction * 0.20), 0.5);
    float normalizedDepth = clamp(insideDistance / max(edgeBand, 0.5), 0.0, 1.0);
    float shoulder = smoothstep(0.08, 0.22, normalizedDepth) *
        (1.0 - smoothstep(0.34, 0.58, normalizedDepth));
    float2 refractionOffset = normal * activeBend * (edgeWeightSquared + shoulder * 0.14);
    float2 refracted = coord - refractionOffset;

    half4 center = backdrop.eval(refracted);
    // Keep RGB separation below half a pixel: it should read as a close inspection detail,
    // never as a red/blue neon outline.
    half red = backdrop.eval(refracted - normal * activeDispersion).r;
    half blue = backdrop.eval(refracted + normal * activeDispersion).b;
    half3 color = half3(red, center.g, blue);

    float2 lightDirection = normalize(float2(-0.72, -0.69));
    float facing = max(dot(normal, lightDirection), 0.0);
    float opposite = max(dot(normal, -lightDirection), 0.0);

    // A second edge-local sample acts like the displaced content shadow in the CSS reference.
    // The overscanned backdrop keeps this a real environmental sample instead of a clamped echo.
    float foldWeight = pow(edgeWeight, 2.8) * 0.12;
    half3 folded = backdrop.eval(
        refracted - normal * activeBend * (0.75 + edgeWeight * 0.70)
    ).rgb;
    color = mix(color, folded, half(foldWeight));
    float returnWeight = pow(edgeWeight, 3.2) * opposite * 0.075;
    half3 depthReturn = backdrop.eval(
        coord - normal * activeBend * 2.05 + lightDirection * activeBend * 0.28
    ).rgb;
    color = mix(color, depthReturn, half(returnWeight));

    // The reference raises local contrast before adding its inset ridges. Restrict the same
    // treatment to the contour so the center remains stable and naturally blurred.
    float localContrast = 1.0 + edgeWeight * 0.10;
    color = (color - half3(0.5)) * half(localContrast) + half3(0.5);

    // Schlick Fresnel reflectance gives the contour a strong grazing-angle return while
    // keeping head-on reflections restrained for readable controls.
    float f0 = ((ior - 1.0) / (ior + 1.0));
    f0 *= f0;
    float cosTheta = clamp(abs(dot(normal, lightDirection)), 0.0, 1.0);
    float fresnel = f0 + (1.0 - f0) * pow(1.0 - cosTheta, 5.0);
    float activeSpecular = specularStrength * (1.0 + interaction * 0.10);
    float innerRidge = smoothstep(0.07, 0.18, normalizedDepth) *
        (1.0 - smoothstep(0.28, 0.43, normalizedDepth));
    half highlight = half(
        edgeWeight * fresnel * (0.30 + 0.70 * facing) * activeSpecular * 3.2 +
        innerRidge * facing * activeSpecular * 0.34
    );
    half coolReturn = half(edgeWeight * pow(opposite, 2.1) * activeSpecular * 0.26);
    color += highlight * half3(1.0, 0.995, 0.98);
    color += coolReturn * half3(0.80, 1.0, 0.94);
    half depthShadow = half(
        (edgeWeight * 0.65 + innerRidge * 0.48) *
        pow(opposite, 1.8) * activeSpecular * 0.34
    );
    color -= depthShadow * half3(0.24, 0.22, 0.18);
    return half4(clamp(color, half3(0.0), half3(1.0)), center.a);
}
"""
