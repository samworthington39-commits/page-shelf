package com.example.bookshelf

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.bookshelf.domain.ProgressResolution
import com.example.bookshelf.domain.ReadingProgress
import com.example.bookshelf.ui.reader.ProgressConflictDialog
import com.example.bookshelf.ui.theme.PageShelfTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProgressConflictDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun letsReaderChooseLocalOrServerPosition() {
        var choice = ""
        val local = textProgress("第 18 章", 0.42, 1_000)
        val remote = textProgress("第 20 章", 0.16, 2_000)
        compose.setContent {
            PageShelfTheme {
                ProgressConflictDialog(
                    ProgressResolution(local, remote, local, hasConflict = true),
                    onLocal = { choice = "local" },
                    onRemote = { choice = "remote" },
                    onCancel = { choice = "cancel" },
                )
            }
        }

        compose.onNodeWithText("发现不同的阅读进度").assertIsDisplayed()
        compose.onNodeWithText("使用服务器进度").performClick()
        assertEquals("remote", choice)
    }

    private fun textProgress(title: String, within: Double, updated: Long) = ReadingProgress(
        bookId = "book",
        bookFormat = "txt",
        chapterId = title,
        chapterTitle = title,
        chapterProgress = within,
        progression = within,
        updatedAtEpochMs = updated,
        deviceId = "device",
    )
}
