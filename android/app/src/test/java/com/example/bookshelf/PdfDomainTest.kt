package com.example.bookshelf

import com.example.bookshelf.domain.DownloadState
import com.example.bookshelf.domain.DownloadStatus
import com.example.bookshelf.domain.calculateProgression
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfDomainTest {
    @Test
    fun pageIndexIsZeroBasedAndDisplayProgressReachesOne() {
        assertEquals(0.1, calculateProgression(pageIndex = 0, pageCount = 10), 0.0001)
        assertEquals(0.5, calculateProgression(pageIndex = 4, pageCount = 10), 0.0001)
        assertEquals(1.0, calculateProgression(pageIndex = 9, pageCount = 10), 0.0001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun pageIndexOutsideDocumentIsRejected() {
        calculateProgression(pageIndex = 10, pageCount = 10)
    }

    @Test
    fun downloadedFileBecomesOutdatedWhenServerFingerprintChanges() {
        val download = DownloadState(
            bookId = "book",
            status = DownloadStatus.DOWNLOADED,
            fingerprint = "old",
            localPath = "/offline/book.pdf",
        )

        assertEquals(DownloadStatus.OUTDATED, download.comparedWith("new").status)
        assertEquals(DownloadStatus.DOWNLOADED, download.comparedWith("old").status)
    }
}
