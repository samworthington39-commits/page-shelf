package com.example.bookshelf.domain

import kotlin.math.abs

enum class AppThemeMode { SYSTEM, LIGHT, DARK }
enum class ReaderViewMode { PAGED, SCROLL }
enum class ReaderFont { SANS, SERIF, SONG, HEI }
enum class ReaderBackground { AUTO, PAPER, WARM, WHITE, GRAY, GREEN, DARK_GRAY, BLACK }

data class ReaderPreferences(
    val viewMode: ReaderViewMode = ReaderViewMode.PAGED,
    val fontSizeSp: Float = 18f,
    val lineHeightMultiplier: Float = 1.7f,
    val font: ReaderFont = ReaderFont.SERIF,
    val background: ReaderBackground = ReaderBackground.AUTO,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val progressSyncEnabled: Boolean = true,
)

data class ReadingProgress(
    val bookId: String,
    val bookFormat: String,
    val chapterId: String? = null,
    val chapterIndex: Int = 0,
    val chapterTitle: String? = null,
    val chapterProgress: Double = 0.0,
    val textOffset: Int = 0,
    val paragraphIndex: Int = 0,
    val pageIndex: Int = 0,
    val pdfPage: Int = 0,
    val pdfPageOffset: Double = 0.0,
    val pageCount: Int? = null,
    val progression: Double = 0.0,
    val updatedAtEpochMs: Long,
    val deviceId: String,
    val contentVersion: String? = null,
    val viewMode: String = "paged",
)

data class ProgressResolution(
    val local: ReadingProgress?,
    val remote: ReadingProgress?,
    val selected: ReadingProgress?,
    val hasConflict: Boolean,
)

/** A small reflow or scroll drift should never interrupt reading with a dialog. */
fun hasMeaningfulProgressConflict(local: ReadingProgress, remote: ReadingProgress): Boolean {
    if (local.bookId != remote.bookId || local.bookFormat != remote.bookFormat) return true
    if (local.contentVersion != null && remote.contentVersion != null && local.contentVersion != remote.contentVersion) {
        return true
    }
    return if (local.bookFormat == "pdf") {
        abs(local.pdfPage - remote.pdfPage) > 2 || abs(local.pdfPageOffset - remote.pdfPageOffset) > 0.15
    } else {
        local.chapterId != remote.chapterId ||
            abs(local.chapterProgress - remote.chapterProgress) > 0.01 &&
            abs(local.textOffset - remote.textOffset) > 160
    }
}

fun chapterWindowRange(currentIndex: Int, chapterCount: Int, radius: Int = 5): IntRange {
    if (chapterCount <= 0) return IntRange.EMPTY
    val safe = currentIndex.coerceIn(0, chapterCount - 1)
    return (safe - radius).coerceAtLeast(0)..(safe + radius).coerceAtMost(chapterCount - 1)
}

/**
 * Orders a chapter cache window from the current chapter outwards. The current chapter is first so
 * it gets a chance to enter the cache immediately even though the whole window is loaded in
 * parallel.
 */
fun chapterWindowLoadOrder(currentIndex: Int, chapterCount: Int, radius: Int = 5): List<Int> {
    if (chapterCount <= 0) return emptyList()
    val safe = currentIndex.coerceIn(0, chapterCount - 1)
    val range = chapterWindowRange(safe, chapterCount, radius)
    return buildList(range.count()) {
        add(safe)
        for (distance in 1..radius) {
            (safe + distance).takeIf { it in range }?.let(::add)
            (safe - distance).takeIf { it in range }?.let(::add)
        }
    }
}

/** Returns the loaded run around the active chapter without skipping a missing chapter. */
fun contiguousLoadedChapterIndices(available: Set<Int>, currentIndex: Int): List<Int> {
    if (currentIndex !in available) return emptyList()
    var first = currentIndex
    var last = currentIndex
    while (first - 1 in available) first -= 1
    while (last + 1 in available) last += 1
    return (first..last).toList()
}
