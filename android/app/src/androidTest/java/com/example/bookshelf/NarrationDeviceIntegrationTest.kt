package com.example.bookshelf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bookshelf.narration.NarrationRequest
import com.example.bookshelf.narration.NarrationStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NarrationDeviceIntegrationTest {
    @Test
    fun changingPlaybackSpeedKeepsTheCurrentQueueAndPosition() = runBlocking {
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as PageShelfApplication
        val container = application.container
        val book = runCatching {
            container.books.books().firstOrNull { it.capabilities.reflowableText }
        }.getOrNull()
        assumeNotNull(book)
        val readableBook = requireNotNull(book)
        val toc = runCatching {
            container.textReader.toc(readableBook.id, readableBook.fingerprint)
        }.getOrNull()
        assumeNotNull(toc?.takeIf(List<*>::isNotEmpty))
        val chapters = requireNotNull(toc)
        val saved = container.progress.local(readableBook.id)
        val chapterIndex = saved?.chapterId?.let { savedChapterId ->
            chapters.indexOfFirst { it.id == savedChapterId }.takeIf { it >= 0 }
        } ?: saved?.chapterIndex?.coerceIn(chapters.indices) ?: 0
        val chapter = chapters[chapterIndex]
        val originalSpeed = container.narration.state.value.playbackSpeed

        try {
            container.narration.start(
                NarrationRequest(
                    bookId = readableBook.id,
                    bookTitle = readableBook.title,
                    bookFormat = readableBook.format,
                    contentVersion = readableBook.fingerprint,
                    chapterId = chapter.id,
                    chapterIndex = chapterIndex,
                    chapterTitle = chapter.title,
                    charOffset = saved?.textOffset ?: 0,
                ),
            )
            val before = withTimeout(120_000) {
                container.narration.state.first { state ->
                    state.status == NarrationStatus.PLAYING && state.currentText.isNotBlank()
                }
            }

            container.narration.setPlaybackSpeed(1.75f)
            delay(600)
            val after = container.narration.state.value

            assertEquals(1.75f, after.playbackSpeed, 0.001f)
            assertNotEquals(NarrationStatus.PREPARING, after.status)
            assertTrue(after.currentText.isNotBlank())
            assertTrue(
                after.chapterIndex > before.chapterIndex ||
                    after.chapterIndex == before.chapterIndex && after.charOffset >= before.charOffset,
            )
        } finally {
            container.narration.setPlaybackSpeed(originalSpeed)
            delay(300)
            container.narration.stop()
        }
    }
}
