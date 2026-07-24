package com.example.bookshelf

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.bookshelf.ui.StartupState
import com.example.bookshelf.ui.auth.StartupScreen
import com.example.bookshelf.ui.library.EmptyLibrary
import com.example.bookshelf.ui.theme.PageShelfTheme
import org.junit.Rule
import org.junit.Test

class StartupAndEmptyStateTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun startupShowsRetryableConnectionError() {
        compose.setContent {
            PageShelfTheme {
                StartupScreen(StartupState.NeedsLogin("无法连接服务器"), {}, {})
            }
        }

        compose.onNodeWithText("无法连接服务器").assertIsDisplayed()
        compose.onNodeWithText("重试").assertIsDisplayed()
        compose.onNodeWithText("修改服务器设置").assertIsDisplayed()
    }

    @Test
    fun offlineEmptyStateExplainsWhatIsAvailable() {
        compose.setContent { PageShelfTheme { EmptyLibrary(Modifier, offline = true) } }

        compose.onNodeWithText("没有可离线阅读的书").assertIsDisplayed()
        compose.onNodeWithText("联网后可返回完整书架。").assertIsDisplayed()
    }
}
