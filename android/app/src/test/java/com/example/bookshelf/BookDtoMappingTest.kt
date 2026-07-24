package com.example.bookshelf

import com.example.bookshelf.data.remote.BookDto
import com.example.bookshelf.data.remote.CapabilitiesDto
import org.junit.Assert.assertEquals
import org.junit.Test

class BookDtoMappingTest {
    @Test
    fun fileFingerprintRemainsSeparateFromContentVersion() {
        val sha256 = "a".repeat(64)
        val book = BookDto(
            id = "pdf-book",
            format = "pdf",
            title = "PDF",
            fileSize = 1024,
            fingerprint = sha256,
            contentVersion = "$sha256:0",
            mimeType = "application/pdf",
            coverStatus = "ready",
            parseStatus = "ready",
            passwordRequired = false,
            canOpen = true,
            capabilities = CapabilitiesDto(offlineDownload = true),
        ).toDomain()

        assertEquals(sha256, book.fileFingerprint)
        assertEquals("$sha256:0", book.fingerprint)
    }
}
