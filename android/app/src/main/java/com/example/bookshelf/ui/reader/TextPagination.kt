package com.example.bookshelf.ui.reader

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

data class TextPage(val start: Int, val end: Int)

data class TextPaginationSpec(
    val textSizePx: Float,
    val lineHeightMultiplier: Float,
    val serif: Boolean,
)

data class ChapterDisplayText(
    val text: String,
    val bodyStart: Int,
)

fun chapterDisplayText(title: String, body: String, fallbackTitle: String): ChapterDisplayText {
    val heading = title.trim().ifEmpty { fallbackTitle }
    val prefix = "$heading\n\n"
    return ChapterDisplayText(text = prefix + body, bodyStart = prefix.length)
}

fun chapterBodyOffset(displayOffset: Int, bodyStart: Int, bodyLength: Int): Int =
    (displayOffset - bodyStart).coerceIn(0, bodyLength)

private const val MIN_PAGE_MEASURE_WINDOW_CHARS = 2_048
private const val MAX_PAGE_MEASURE_WINDOW_CHARS = 16_384

/**
 * Paginate with Android's native text engine. This function contains no Compose state and is safe
 * to run on Dispatchers.Default, keeping rendering and the loading animation on the main thread.
 */
fun paginateText(
    text: String,
    spec: TextPaginationSpec,
    widthPx: Int,
    heightPx: Int,
): List<TextPage> {
    if (text.isEmpty()) return listOf(TextPage(0, 0))
    val width = widthPx.coerceAtLeast(1)
    val height = heightPx.coerceAtLeast(1)
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = spec.textSizePx.coerceAtLeast(1f)
        typeface = if (spec.serif) Typeface.SERIF else Typeface.SANS_SERIF
    }
    val naturalLineHeight = with(paint.fontMetricsInt) { (descent - ascent).toFloat() }.coerceAtLeast(1f)
    val requestedLineHeight = (spec.textSizePx * spec.lineHeightMultiplier).coerceAtLeast(naturalLineHeight)
    val lineSpacingExtra = (requestedLineHeight - naturalLineHeight).coerceAtLeast(0f)
    val maxLines = (height / requestedLineHeight).toInt().coerceAtLeast(1)
    val estimatedCharsPerLine = (width / spec.textSizePx.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
    val measureWindowChars = (estimatedCharsPerLine * maxLines * 4)
        .coerceIn(MIN_PAGE_MEASURE_WINDOW_CHARS, MAX_PAGE_MEASURE_WINDOW_CHARS)
    val pages = ArrayList<TextPage>((text.length / 600).coerceIn(1, 4_096))
    var start = 0
    while (start < text.length) {
        val candidateEnd = minOf(
            text.length.toLong(),
            start.toLong() + measureWindowChars,
        ).toInt()
        val layout = StaticLayout.Builder.obtain(text, start, candidateEnd, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(lineSpacingExtra, 1f)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setEllipsizedWidth(width)
            .setMaxLines(maxLines)
            .build()
        val lastLine = layout.lineCount - 1
        val measuredEnd = when {
            lastLine < 0 -> start + 1
            layout.getEllipsisCount(lastLine) > 0 -> {
                layout.getLineStart(lastLine) + layout.getEllipsisStart(lastLine)
            }
            else -> layout.getLineEnd(lastLine)
        }
        val end = measuredEnd.coerceIn(start + 1, candidateEnd)
        pages += TextPage(start, end)
        start = end
    }
    return pages
}
