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
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A recorded background layer shared by every glass surface inside one screen backdrop. */
@Stable
internal class GlassBackdrop internal constructor(val layer: GraphicsLayer) {
    var coordinates: LayoutCoordinates? by mutableStateOf(null)
}

internal val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

@Composable
internal fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/** Records only the environmental layer, keeping glass controls out of their own sample. */
internal fun Modifier.captureGlassBackdrop(backdrop: GlassBackdrop): Modifier =
    onGloballyPositioned { coordinates ->
        if (coordinates.isAttached) backdrop.coordinates = coordinates
    }.drawWithCache {
        onDrawWithContent {
            backdrop.layer.record(size = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())) {
                this@onDrawWithContent.drawContent()
            }
            drawLayer(backdrop.layer)
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
): Modifier {
    val sampleLayer = rememberGraphicsLayer()
    val runtimeShader = remember(palette.liquidOptics) {
        if (palette.liquidOptics && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RuntimeShader(LIQUID_GLASS_SHADER)
        }
        else null
    }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var softwareBlurredSample by remember { mutableStateOf<ImageBitmap?>(null) }
    val density = LocalDensity.current
    val useSoftwareBlur = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    val softwareBlurRadius = with(density) {
        val configured = when (material) {
            GlassMaterial.Standard -> if (palette.liquidOptics) 4.5.dp else 10.dp
            GlassMaterial.ReaderChrome -> 28.dp
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

    return onGloballyPositioned { current ->
        if (current.isAttached) coordinates = current
    }.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val cornerRadius = outline.cornerRadiusPx(size.minDimension)
        val blurRadius = if (palette.liquidOptics) {
            when (material) {
                GlassMaterial.Standard -> 4.5.dp.toPx()
                GlassMaterial.ReaderChrome -> 28.dp.toPx()
            }
        } else {
            when (material) {
                GlassMaterial.Standard -> 10.dp.toPx()
                GlassMaterial.ReaderChrome -> 28.dp.toPx()
            }
        }
        val refractionBand = min(
            when (material) {
                GlassMaterial.Standard -> 10.dp.toPx()
                GlassMaterial.ReaderChrome -> 13.dp.toPx()
            },
            size.minDimension * 0.30f,
        )
        val refractionStrength = min(
            when (material) {
                GlassMaterial.Standard -> 4.5.dp.toPx()
                GlassMaterial.ReaderChrome -> 7.dp.toPx()
            },
            refractionBand * 0.58f,
        )
        val dispersion = min(
            when (material) {
                GlassMaterial.Standard -> 0.18.dp.toPx()
                GlassMaterial.ReaderChrome -> 0.38.dp.toPx()
            },
            refractionStrength * 0.06f,
        )
        val edgeWidth = borderWidth.toPx().coerceAtLeast(0.75f)
        if (!useSoftwareBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            sampleLayer.compositingStrategy = CompositingStrategy.Offscreen
            sampleLayer.renderEffect = if (palette.liquidOptics) {
                createGlassRenderEffect(
                    runtimeShader = runtimeShader,
                    width = size.width,
                    height = size.height,
                    radius = cornerRadius,
                    blurRadius = blurRadius,
                    refractionBand = refractionBand,
                    refractionStrength = refractionStrength,
                    dispersion = dispersion,
                    ior = 1.47f,
                    interactionProgress = interactionProgress,
                ).asComposeRenderEffect()
            } else {
                AndroidRenderEffect.createBlurEffect(
                    blurRadius,
                    blurRadius,
                    AndroidShader.TileMode.CLAMP,
                ).asComposeRenderEffect()
            }
        } else {
            sampleLayer.renderEffect = null
        }

        val surfaceSheen = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to palette.highlight.copy(alpha = palette.highlight.alpha * 0.32f),
                0.16f to palette.highlight.copy(alpha = palette.highlight.alpha * 0.10f),
                0.58f to Color.Transparent,
                1f to palette.stroke.copy(alpha = palette.stroke.alpha * 0.14f),
            ),
        )
        val ambientRim = Brush.linearGradient(
            colorStops = arrayOf(
                0f to palette.rimCool.copy(alpha = palette.rimCool.alpha * 0.72f),
                0.30f to palette.rimCool,
                0.58f to Color.Transparent,
                0.82f to palette.rimWarm,
                1f to palette.rimWarm.copy(alpha = palette.rimWarm.alpha * 0.68f),
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        val contourRim = Brush.linearGradient(
            colorStops = arrayOf(
                0f to palette.highlight.copy(alpha = palette.highlight.alpha * 0.72f),
                0.20f to palette.strokeStrong,
                0.52f to palette.stroke,
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
            val softwareBlur = useSoftwareBlur
            val layerSize = if (softwareBlur) {
                IntSize(
                    ceil(size.width / SOFTWARE_BLUR_DOWNSAMPLE).toInt().coerceAtLeast(1),
                    ceil(size.height / SOFTWARE_BLUR_DOWNSAMPLE).toInt().coerceAtLeast(1),
                )
            } else {
                IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())
            }
            sampleLayer.record(size = layerSize) {
                if (softwareBlur) {
                    scale(
                        scaleX = 1f / SOFTWARE_BLUR_DOWNSAMPLE,
                        scaleY = 1f / SOFTWARE_BLUR_DOWNSAMPLE,
                        pivot = Offset.Zero,
                    ) {
                        translate(-sampleOffset.x, -sampleOffset.y) {
                            drawLayer(backdrop.layer)
                        }
                    }
                } else {
                    translate(-sampleOffset.x, -sampleOffset.y) {
                        drawLayer(backdrop.layer)
                    }
                }
            }
            if (softwareBlur) {
                softwareBlurredSample?.let { blurred ->
                    drawImage(
                        image = blurred,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(blurred.width, blurred.height),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            } else {
                drawLayer(sampleLayer)
            }
            drawRect(fill)
            if (interactionProgress > 0f) {
                drawRect(palette.rimCool.copy(alpha = 0.055f * interactionProgress))
            }
            if (palette.liquidOptics) {
                if (specular) drawRect(surfaceSheen)
                drawOutline(
                    outline = outline,
                    brush = ambientRim,
                    style = Stroke(width = edgeWidth * 4.4f),
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
    width: Float,
    height: Float,
    radius: Float,
    blurRadius: Float,
    refractionBand: Float,
    refractionStrength: Float,
    dispersion: Float,
    ior: Float,
    interactionProgress: Float,
): AndroidRenderEffect {
    val blur = AndroidRenderEffect.createBlurEffect(
        blurRadius,
        blurRadius,
        AndroidShader.TileMode.CLAMP,
    )
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || runtimeShader == null) return blur

    runtimeShader.setFloatUniform("bounds", width, height)
    runtimeShader.setFloatUniform("cornerRadius", radius)
    runtimeShader.setFloatUniform("edgeBand", refractionBand)
    runtimeShader.setFloatUniform("bend", refractionStrength)
    runtimeShader.setFloatUniform("dispersion", dispersion)
    runtimeShader.setFloatUniform("ior", ior)
    runtimeShader.setFloatUniform("interaction", interactionProgress.coerceIn(0f, 1f))
    val refraction = AndroidRenderEffect.createRuntimeShaderEffect(runtimeShader, "backdrop")
    return AndroidRenderEffect.createChainEffect(refraction, blur)
}

private const val LIQUID_GLASS_SHADER = """
uniform shader backdrop;
uniform float2 bounds;
uniform float cornerRadius;
uniform float edgeBand;
uniform float bend;
uniform float dispersion;
uniform float ior;
uniform float interaction;

float roundedRectDistance(float2 p, float2 halfSize, float radius) {
    float2 q = abs(p) - (halfSize - float2(radius));
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

float2 roundedRectNormal(float2 p, float2 halfSize, float radius) {
    float2 q = abs(p) - (halfSize - float2(radius));
    float2 outside = max(q, 0.0);
    if (dot(outside, outside) > 0.0001) {
        return normalize(outside) * sign(p);
    }
    return q.x > q.y ? float2(sign(p.x), 0.0) : float2(0.0, sign(p.y));
}

half4 main(float2 coord) {
    float2 halfSize = bounds * 0.5;
    float2 centered = coord - halfSize;
    float distance = roundedRectDistance(centered, halfSize, cornerRadius);
    float rim = 1.0 - smoothstep(0.0, edgeBand, -distance);
    if (rim <= 0.001) return backdrop.eval(coord);

    float2 normal = roundedRectNormal(centered, halfSize, cornerRadius);
    float curvedRim = rim * rim * (3.0 - 2.0 * rim);
    // The edge is treated as the circular cross-section of a thin glass slab. Its optical
    // thickness increases toward the contour instead of applying a flat displacement.
    float opticalThickness = sqrt(max(0.0, 1.0 - (1.0 - curvedRim) * (1.0 - curvedRim)));
    float greenPower = (ior - 1.0) / ior;
    float redIor = ior - 0.006;
    float blueIor = ior + 0.010;
    float redPower = (redIor - 1.0) / redIor;
    float bluePower = (blueIor - 1.0) / blueIor;
    float activeBend = bend * (1.0 + interaction * 0.18);
    float activeDispersion = dispersion * (1.0 + interaction * 0.24);
    float2 refracted = coord - normal * activeBend * opticalThickness;
    float2 redSplit = normal * activeDispersion * opticalThickness * (redPower / greenPower - 1.0) * 72.0;
    float2 blueSplit = normal * activeDispersion * opticalThickness * (bluePower / greenPower - 1.0) * 72.0;

    half4 center = backdrop.eval(refracted);
    // Crown-glass dispersion: red has the lowest IOR and blue the highest, so blue bends
    // farther into the edge normal while red stays closer to the incident sample.
    half red = backdrop.eval(refracted - redSplit).r;
    half blue = backdrop.eval(refracted - blueSplit).b;
    half3 color = half3(red, center.g, blue);

    float2 lightDirection = normalize(float2(-0.72, -0.69));
    float facing = max(dot(normal, lightDirection), 0.0);
    float opposite = max(dot(normal, -lightDirection), 0.0);
    // Schlick Fresnel reflectance gives the contour a strong grazing-angle return while
    // keeping head-on reflections restrained for readable controls.
    float f0 = ((ior - 1.0) / (ior + 1.0));
    f0 *= f0;
    float cosTheta = clamp(abs(dot(normal, lightDirection)), 0.0, 1.0);
    float fresnel = f0 + (1.0 - f0) * pow(1.0 - cosTheta, 5.0);
    half highlight = half(curvedRim * fresnel * (0.30 + 0.70 * facing) * (0.34 + interaction * 0.08));
    half coolReturn = half(curvedRim * pow(opposite, 2.1) * (0.035 + interaction * 0.018));
    color += highlight * half3(1.0, 0.995, 0.98);
    color += coolReturn * half3(0.80, 1.0, 0.94);
    return half4(color, center.a);
}
"""
