package com.example.bookshelf.ui.reader

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class FitMode { PAGE, WIDTH }

private data class RenderKey(val pageIndex: Int, val width: Int, val height: Int, val fitMode: FitMode)

class PdfRendererSession(file: File) : Closeable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val renderLock = Mutex()
    private val cache = LinkedHashMap<RenderKey, Bitmap>(16, 0.75f, true)

    val pageCount: Int get() = renderer.pageCount

    suspend fun render(pageIndex: Int, availableWidthPx: Int, availableHeightPx: Int, fitMode: FitMode): Bitmap =
        withContext(Dispatchers.IO) {
            val key = RenderKey(pageIndex, availableWidthPx, availableHeightPx, fitMode)
            renderLock.withLock {
                cache[key]?.let { return@withLock it }
                val bitmap = renderer.openPage(pageIndex).use { page ->
                    val widthScale = availableWidthPx.toFloat() / page.width
                    val heightScale = availableHeightPx.toFloat() / page.height
                    val scale = when (fitMode) {
                        FitMode.PAGE -> min(widthScale, heightScale)
                        FitMode.WIDTH -> widthScale
                    }.coerceIn(0.1f, 3f)
                    Bitmap.createBitmap(
                        max(1, (page.width * scale).toInt()),
                        max(1, (page.height * scale).toInt()),
                        Bitmap.Config.ARGB_8888,
                    ).also { output ->
                        output.eraseColor(android.graphics.Color.WHITE)
                        page.render(output, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
                cache[key] = bitmap
                trimToMaximum()
                bitmap
            }
        }

    suspend fun retainWindow(currentPage: Int) = withContext(Dispatchers.IO) {
        renderLock.withLock {
            val allowed = (currentPage - 5).coerceAtLeast(0)..(currentPage + 5).coerceAtMost(pageCount - 1)
            val iterator = cache.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.pageIndex !in allowed) {
                    entry.value.recycle()
                    iterator.remove()
                }
            }
        }
    }

    private fun trimToMaximum() {
        while (cache.size > 11) {
            val first = cache.entries.first()
            first.value.recycle()
            cache.remove(first.key)
        }
    }

    override fun close() {
        cache.values.forEach { if (!it.isRecycled) it.recycle() }
        cache.clear()
        renderer.close()
        descriptor.close()
    }
}
