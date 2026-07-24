package com.example.bookshelf

import com.example.bookshelf.data.repository.toDomain
import com.example.bookshelf.data.repository.toEntity
import com.example.bookshelf.domain.ReadingProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressMappingTest {
    @Test
    fun `txt and epub stable anchors survive database round trip`() {
        val source = ReadingProgress(
            bookId = "epub-book",
            bookFormat = "epub",
            chapterId = "chapter-12",
            chapterIndex = 11,
            chapterTitle = "第十二章",
            chapterProgress = 0.63,
            textOffset = 4280,
            paragraphIndex = 86,
            progression = 0.41,
            updatedAtEpochMs = 1234,
            deviceId = "phone",
            contentVersion = "fingerprint",
            viewMode = "paged",
        )

        assertEquals(source, source.toEntity().toDomain())
    }

    @Test
    fun `pdf page and intra page offset survive database round trip`() {
        val source = ReadingProgress(
            bookId = "pdf-book",
            bookFormat = "pdf",
            pageIndex = 34,
            pdfPage = 34,
            pdfPageOffset = 0.28,
            pageCount = 280,
            progression = 35.0 / 280,
            updatedAtEpochMs = 5678,
            deviceId = "tablet",
            contentVersion = "fingerprint",
            viewMode = "continuous",
        )

        assertEquals(source, source.toEntity().toDomain())
    }
}
