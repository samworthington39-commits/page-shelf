package com.example.bookshelf

import com.example.bookshelf.domain.ReadingProgress
import com.example.bookshelf.domain.chapterWindowLoadOrder
import com.example.bookshelf.domain.chapterWindowRange
import com.example.bookshelf.domain.contiguousLoadedChapterIndices
import com.example.bookshelf.domain.hasMeaningfulProgressConflict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderCoreDomainTest {
    @Test
    fun `chapter cache window is current chapter plus or minus five`() {
        assertEquals(15..25, chapterWindowRange(20, 40))
        assertEquals(16..26, chapterWindowRange(21, 40))
        assertEquals(0..5, chapterWindowRange(0, 40))
        assertEquals(34..39, chapterWindowRange(39, 40))
        assertEquals(IntRange.EMPTY, chapterWindowRange(0, 0))
    }

    @Test
    fun `chapter load order includes the complete window from current outwards`() {
        assertEquals(
            listOf(20, 21, 19, 22, 18, 23, 17, 24, 16, 25, 15),
            chapterWindowLoadOrder(20, 40),
        )
        assertEquals(listOf(0, 1, 2, 3, 4, 5), chapterWindowLoadOrder(0, 40))
        assertEquals(emptyList<Int>(), chapterWindowLoadOrder(0, 0))
    }

    @Test
    fun `continuous reader never skips a missing chapter`() {
        assertEquals(listOf(3, 4, 5), contiguousLoadedChapterIndices(setOf(1, 3, 4, 5, 7), 4))
        assertEquals(emptyList<Int>(), contiguousLoadedChapterIndices(setOf(1, 2, 4), 3))
    }

    @Test
    fun `small text drift is merged without conflict`() {
        val local = textProgress(chapter = "c1", offset = 500, chapterProgress = 0.42)
        val remote = textProgress(chapter = "c1", offset = 540, chapterProgress = 0.425)

        assertFalse(hasMeaningfulProgressConflict(local, remote))
    }

    @Test
    fun `cross chapter text and distant pdf positions conflict`() {
        assertTrue(hasMeaningfulProgressConflict(textProgress("c1", 100, 0.1), textProgress("c2", 10, 0.01)))
        assertTrue(hasMeaningfulProgressConflict(pdfProgress(2), pdfProgress(8)))
        assertFalse(hasMeaningfulProgressConflict(pdfProgress(2), pdfProgress(4)))
    }

    @Test
    fun `content version change conflicts instead of silently resetting`() {
        val local = textProgress("c1", 100, 0.1).copy(contentVersion = "old")
        val remote = local.copy(contentVersion = "new")
        assertTrue(hasMeaningfulProgressConflict(local, remote))
    }

    private fun textProgress(chapter: String, offset: Int, chapterProgress: Double) = ReadingProgress(
        bookId = "book",
        bookFormat = "epub",
        chapterId = chapter,
        chapterIndex = if (chapter == "c1") 0 else 1,
        chapterProgress = chapterProgress,
        textOffset = offset,
        progression = chapterProgress,
        updatedAtEpochMs = 1,
        deviceId = "device",
    )

    private fun pdfProgress(page: Int) = ReadingProgress(
        bookId = "pdf",
        bookFormat = "pdf",
        pdfPage = page,
        pageIndex = page,
        pageCount = 20,
        progression = (page + 1) / 20.0,
        updatedAtEpochMs = 1,
        deviceId = "device",
    )
}
