package com.example.bookshelf

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import com.example.bookshelf.domain.ReaderPreferences
import com.example.bookshelf.domain.TextChapter
import com.example.bookshelf.ui.reader.ContinuousPagedChapters
import com.example.bookshelf.ui.reader.ContinuousScrollingChapters
import com.example.bookshelf.ui.reader.ReaderPalette
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ContinuousTextReaderDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pagedReaderSwipesDirectlyIntoNextChapterHeading() {
        val chapters = chapters()
        composeRule.setContent {
            MaterialTheme {
                ContinuousPagedChapters(
                    chapters = chapters,
                    currentChapterIndex = 0,
                    currentOffset = 0,
                    positionRevision = 0,
                    chapterCount = chapters.size,
                    preferences = ReaderPreferences(),
                    colors = palette,
                    narrationHighlight = null,
                    controlsVisible = false,
                    onToggleControls = {},
                    onPositionChanged = { _, _ -> },
                    onVisiblePositionChanged = { _, _ -> },
                    onEnsureChapter = {},
                )
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("第一章", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodes(hasText("第一章", substring = true))[0].assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeLeft() }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("第二章", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodes(hasText("第二章", substring = true))[0].assertIsDisplayed()
    }

    @Test
    fun oneRightEdgeTapAdvancesOnePageWithoutSkippingTheChapter() {
        val longBody = List(100) { "第一章正文第 ${it + 1} 行，这一行用于验证单次翻页。" }.joinToString("\n")
        val chapters = chapters(firstBody = longBody)
        val position = AtomicReference(0 to 0)
        composeRule.setContent {
            MaterialTheme {
                ContinuousPagedChapters(
                    chapters = chapters,
                    currentChapterIndex = 0,
                    currentOffset = 0,
                    positionRevision = 0,
                    chapterCount = chapters.size,
                    preferences = ReaderPreferences(),
                    colors = palette,
                    narrationHighlight = null,
                    controlsVisible = false,
                    onToggleControls = {},
                    onPositionChanged = { chapterIndex, offset -> position.set(chapterIndex to offset) },
                    onVisiblePositionChanged = { _, _ -> },
                    onEnsureChapter = {},
                )
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("第一章", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasScrollAction()).performTouchInput {
            click(Offset(900f, 500f))
        }
        composeRule.waitUntil(5_000) { position.get().second > 0 }
        assertEquals(0, position.get().first)
    }

    @Test
    fun pagedReaderKeepsItsChapterWhenTheLoadedWindowMoves() {
        val loadedWindow = mutableStateOf(chapterWindow(5..15))
        val visiblePosition = AtomicReference(-1 to -1)
        composeRule.setContent {
            MaterialTheme {
                ContinuousPagedChapters(
                    chapters = loadedWindow.value,
                    currentChapterIndex = 10,
                    currentOffset = 0,
                    positionRevision = 0,
                    chapterCount = 30,
                    preferences = ReaderPreferences(),
                    colors = palette,
                    narrationHighlight = null,
                    controlsVisible = false,
                    onToggleControls = {},
                    onPositionChanged = { _, _ -> },
                    onVisiblePositionChanged = { chapterIndex, offset ->
                        visiblePosition.set(chapterIndex to offset)
                    },
                    onEnsureChapter = {},
                )
            }
        }

        composeRule.waitUntil(5_000) { visiblePosition.get().first == 10 }
        composeRule.runOnIdle {
            visiblePosition.set(-1 to -1)
            loadedWindow.value = chapterWindow(6..16)
        }
        composeRule.waitUntil(5_000) { visiblePosition.get().first >= 0 }

        assertEquals(10, visiblePosition.get().first)
        composeRule.onAllNodes(hasText("窗口章节 10", substring = true))[0].assertIsDisplayed()
    }

    @Test
    fun scrollingReaderKeepsTheNextChapterInTheSameFeed() {
        val chapters = chapters(firstBody = List(80) { "第一章正文第 ${it + 1} 行" }.joinToString("\n"))
        composeRule.setContent {
            MaterialTheme {
                ContinuousScrollingChapters(
                    chapters = chapters,
                    currentChapterIndex = 0,
                    currentOffset = 0,
                    positionRevision = 0,
                    chapterCount = chapters.size,
                    preferences = ReaderPreferences(),
                    colors = palette,
                    narrationHighlight = null,
                    controlsVisible = false,
                    onToggleControls = {},
                    onPositionChanged = { _, _ -> },
                    onVisiblePositionChanged = { _, _ -> },
                    onEnsureChapter = {},
                )
            }
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("第二章"))
        composeRule.onAllNodes(hasText("第二章"))[0].assertIsDisplayed()
    }

    @Test
    fun scrollingReaderUsesVerticalTapZonesForPagingAndControls() {
        val longBody = List(160) { "连续阅读第 ${it + 1} 行，用于验证点按翻页。" }.joinToString("\n")
        val position = AtomicReference(0 to 0)
        val controlToggles = AtomicInteger(0)
        composeRule.setContent {
            MaterialTheme {
                ContinuousScrollingChapters(
                    chapters = chapters(firstBody = longBody),
                    currentChapterIndex = 0,
                    currentOffset = 0,
                    positionRevision = 0,
                    chapterCount = 2,
                    preferences = ReaderPreferences(),
                    colors = palette,
                    narrationHighlight = null,
                    controlsVisible = false,
                    onToggleControls = { controlToggles.incrementAndGet() },
                    onPositionChanged = { chapterIndex, offset ->
                        position.set(chapterIndex to offset)
                    },
                    onVisiblePositionChanged = { _, _ -> },
                    onEnsureChapter = {},
                )
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("第一章", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        val scrollingFeed = composeRule.onNode(hasScrollAction())
        val feedBounds = scrollingFeed.fetchSemanticsNode().boundsInRoot
        scrollingFeed.performTouchInput {
            click(Offset(feedBounds.width / 2f, feedBounds.height / 2f))
        }
        composeRule.waitUntil(5_000) { controlToggles.get() == 1 }

        val startOffset = position.get().second
        scrollingFeed.performTouchInput {
            click(Offset(feedBounds.width / 2f, feedBounds.height - 10f))
        }
        composeRule.waitUntil(5_000) { position.get().second > startOffset }
        val lowerOffset = position.get().second

        scrollingFeed.performTouchInput {
            click(Offset(feedBounds.width / 2f, 10f))
        }
        composeRule.waitUntil(5_000) { position.get().second < lowerOffset }

        val beforeSwipeOffset = position.get().second
        scrollingFeed.performTouchInput { swipeUp() }
        composeRule.waitUntil(5_000) { position.get().second > beforeSwipeOffset }
        assertEquals(1, controlToggles.get())
    }

    private fun chapters(firstBody: String = "第一章正文") = mapOf(
        0 to TextChapter("c1", "book", "第一章", 0, firstBody),
        1 to TextChapter("c2", "book", "第二章", 1, "第二章正文"),
    )

    private fun chapterWindow(indices: IntRange) = indices.associateWith { index ->
        TextChapter(
            id = "chapter-$index",
            bookId = "book",
            title = "窗口章节 $index",
            position = index,
            body = "窗口章节 $index 的正文。",
        )
    }

    private companion object {
        val palette = ReaderPalette(background = Color.White, foreground = Color.Black)
    }
}
