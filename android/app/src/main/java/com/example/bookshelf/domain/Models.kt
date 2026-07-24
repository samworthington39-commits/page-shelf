package com.example.bookshelf.domain

data class Capabilities(
    val chapters: Boolean = false,
    val reflowableText: Boolean = false,
    val fontSettings: Boolean = false,
    val pageNavigation: Boolean = false,
    val zoom: Boolean = false,
    val offlineDownload: Boolean = false,
    val progressSync: Boolean = false,
)

data class Book(
    val id: String,
    val shelfId: String?,
    val format: String,
    val title: String,
    val author: String?,
    val subject: String?,
    val pageCount: Int?,
    val chapterCount: Int?,
    val fileSize: Long,
    // Raw file SHA-256 used by downloads, ETags, and integrity checks.
    val fileFingerprint: String,
    // Server content_version used to invalidate chapters and reading progress.
    val fingerprint: String,
    val mimeType: String,
    val coverStatus: String,
    val parseStatus: String,
    val parseWarnings: List<String>,
    val passwordRequired: Boolean,
    val canOpen: Boolean,
    val hasPdfNavigation: Boolean,
    val capabilities: Capabilities,
)

data class LibraryShelf(
    val id: String,
    val name: String,
    val isHidden: Boolean,
    val locked: Boolean,
    val bookCount: Int,
    val totalBytes: Long,
    val books: List<Book>,
)

private const val LARGE_REFLOWABLE_BYTES = 8L * 1024 * 1024
private const val LARGE_FIXED_LAYOUT_BYTES = 80L * 1024 * 1024

fun Book.shouldWarnBeforeOpen(): Boolean =
    fileSize >= if (capabilities.reflowableText) LARGE_REFLOWABLE_BYTES else LARGE_FIXED_LAYOUT_BYTES

data class PdfNavigationItem(
    val title: String,
    val page: Int,
    val children: List<PdfNavigationItem> = emptyList(),
)

data class TextChapterSummary(val id: String, val title: String, val position: Int)

data class TextChapter(
    val id: String,
    val bookId: String,
    val title: String,
    val position: Int,
    val body: String,
)

data class TextReadingPosition(
    val chapterId: String,
    val chapterIndex: Int,
    val charOffset: Int,
    val progression: Double,
    val viewMode: String = "paged",
    val fontSizeSp: Float = 19f,
    val lineHeightMultiplier: Float = 1.7f,
    val paragraphIndex: Int = 0,
    val chapterProgress: Double = 0.0,
)

fun calculateTextProgression(
    chapterIndex: Int,
    chapterCount: Int,
    charOffset: Int,
    chapterLength: Int,
): Double {
    if (chapterCount <= 0) return 0.0
    val withinChapter = if (chapterLength > 0) {
        charOffset.coerceIn(0, chapterLength).toDouble() / chapterLength
    } else 0.0
    return ((chapterIndex.coerceIn(0, chapterCount - 1) + withinChapter) / chapterCount).coerceIn(0.0, 1.0)
}

enum class DownloadStatus { NOT_DOWNLOADED, QUEUED, DOWNLOADING, PAUSED, DOWNLOADED, OUTDATED, FAILED }

data class DownloadState(
    val bookId: String,
    val status: DownloadStatus,
    val format: String = "",
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val localPath: String? = null,
    val fingerprint: String? = null,
    val error: String? = null,
    val isPermanent: Boolean = false,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (bytesDownloaded.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else 0f

    fun comparedWith(currentFingerprint: String): DownloadState =
        if (status == DownloadStatus.DOWNLOADED && fingerprint != currentFingerprint) copy(status = DownloadStatus.OUTDATED)
        else this
}

data class PdfProgress(
    val bookId: String,
    val deviceId: String,
    val pageIndex: Int,
    val pageCount: Int,
    val progression: Double,
    val updatedAtEpochMs: Long,
)

fun calculateProgression(pageIndex: Int, pageCount: Int): Double {
    require(pageCount > 0) { "pageCount must be positive" }
    require(pageIndex in 0 until pageCount) { "pageIndex must be zero-based and within the document" }
    return (pageIndex + 1).toDouble() / pageCount
}
