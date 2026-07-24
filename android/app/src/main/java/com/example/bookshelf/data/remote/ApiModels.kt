package com.example.bookshelf.data.remote

import com.example.bookshelf.domain.Book
import com.example.bookshelf.domain.Capabilities
import com.example.bookshelf.domain.PdfNavigationItem

data class CapabilitiesDto(
    val chapters: Boolean = false,
    val reflowableText: Boolean = false,
    val fontSettings: Boolean = false,
    val pageNavigation: Boolean = false,
    val zoom: Boolean = false,
    val offlineDownload: Boolean = false,
    val progressSync: Boolean = false,
) {
    fun toDomain() = Capabilities(chapters, reflowableText, fontSettings, pageNavigation, zoom, offlineDownload, progressSync)
}

data class BookDto(
    val id: String,
    val shelfId: String? = null,
    val format: String,
    val title: String,
    val author: String? = null,
    val subject: String? = null,
    val pageCount: Int? = null,
    val chapterCount: Int? = null,
    val fileSize: Long,
    val fingerprint: String,
    val contentVersion: String? = null,
    val mimeType: String,
    val coverStatus: String,
    val parseStatus: String,
    val parseWarningsJson: List<String>? = null,
    val passwordRequired: Boolean,
    val canOpen: Boolean,
    val hasPdfNavigation: Boolean = false,
    val capabilities: CapabilitiesDto,
) {
    fun toDomain() = Book(
        id = id,
        shelfId = shelfId,
        format = format,
        title = title,
        author = author,
        subject = subject,
        pageCount = pageCount,
        chapterCount = chapterCount,
        fileSize = fileSize,
        fileFingerprint = fingerprint,
        fingerprint = contentVersion ?: fingerprint,
        mimeType = mimeType,
        coverStatus = coverStatus,
        parseStatus = parseStatus,
        parseWarnings = parseWarningsJson.orEmpty(),
        passwordRequired = passwordRequired,
        canOpen = canOpen,
        hasPdfNavigation = hasPdfNavigation,
        capabilities = capabilities.toDomain(),
    )
}

data class PublicShelfDto(
    val id: String,
    val name: String,
    val isHidden: Boolean,
    val locked: Boolean,
    val bookCount: Int,
    val totalBytes: Long,
    val books: List<BookDto>,
)

data class ShelfUnlockRequest(val pin: String)

data class PdfNavigationItemDto(
    val title: String,
    val page: Int,
    val children: List<PdfNavigationItemDto> = emptyList(),
) {
    fun toDomain(): PdfNavigationItem = PdfNavigationItem(title, page, children.map { it.toDomain() })
}

data class PdfNavigationResponseDto(
    val bookId: String,
    val pageCount: Int?,
    val items: List<PdfNavigationItemDto>,
)

data class TocItemDto(
    val id: String,
    val title: String,
    val position: Int,
)

data class TocResponseDto(
    val bookId: String,
    val format: String,
    val chapterSupported: Boolean,
    val items: List<TocItemDto>,
)

data class ChapterResponseDto(
    val id: String,
    val bookId: String,
    val title: String,
    val position: Int,
    val body: String,
)

data class ProgressRequest(
    val pageIndex: Int,
    val pageCount: Int,
    val progression: Double,
    val locatorJson: Map<String, Any>,
)

data class TextProgressRequest(
    val progression: Double,
    val locatorJson: Map<String, Any>,
)

data class ProgressResponseDto(
    val bookId: String,
    val deviceId: String,
    val pageIndex: Int?,
    val pageCount: Int?,
    val progression: Double,
    val locatorJson: Map<String, Any>? = null,
    val updatedAt: String,
)
