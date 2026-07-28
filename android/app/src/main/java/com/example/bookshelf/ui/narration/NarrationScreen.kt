@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.bookshelf.ui.narration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bookshelf.narration.NarrationController
import com.example.bookshelf.narration.NarrationStatus
import com.example.bookshelf.narration.NarrationVoice

@Composable
fun NarrationScreen(
    controller: NarrationController,
    bookId: String,
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val prepared by controller.prepared.collectAsStateWithLifecycle()
    val isThisBook = state.bookId == bookId
    val active = isThisBook && state.isActive
    val bookTitle = state.bookTitle.takeIf { isThisBook && it.isNotBlank() }
        ?: prepared?.takeIf { it.bookId == bookId }?.bookTitle
        ?: "听书模式"
    val chapterTitle = state.chapterTitle.takeIf { isThisBook && it.isNotBlank() }
        ?: prepared?.takeIf { it.bookId == bookId }?.chapterTitle.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("听书模式") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回阅读")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.VolumeUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(54.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(bookTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                buildString {
                    append(chapterTitle)
                    if (isThisBook && state.chapterCount > 0) {
                        append("  ·  ")
                        append(state.chapterIndex + 1)
                        append(" / ")
                        append(state.chapterCount)
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Card(Modifier.fillMaxWidth().padding(top = 28.dp)) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(180.dp).padding(22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        isThisBook && state.status == NarrationStatus.PREPARING -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Text("正在加载端侧语音模型…", modifier = Modifier.padding(top = 14.dp))
                            }
                        }
                        isThisBook && state.currentText.isNotBlank() -> Text(
                            state.currentText,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        isThisBook && state.status == NarrationStatus.ERROR -> Text(
                            state.error ?: "朗读失败",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        isThisBook && state.status == NarrationStatus.COMPLETED -> Text("本书已朗读完毕")
                        else -> Text("从阅读页的当前位置开始")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = controller::previousChapter,
                    enabled = active && state.status != NarrationStatus.PREPARING && state.chapterIndex > 0,
                ) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "上一章")
                }
                Button(
                    onClick = {
                        if (active && state.status == NarrationStatus.PLAYING) controller.pause()
                        else if (active && state.status == NarrationStatus.PAUSED) controller.resume()
                        else controller.startPrepared()
                    },
                    enabled = state.status != NarrationStatus.PREPARING && (active || prepared?.bookId == bookId),
                ) {
                    Icon(
                        if (active && state.status == NarrationStatus.PLAYING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                    )
                    Text(
                        if (active && state.status == NarrationStatus.PLAYING) "暂停" else if (active) "继续" else "开始朗读",
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                IconButton(
                    onClick = controller::nextChapter,
                    enabled = active && state.status != NarrationStatus.PREPARING &&
                        state.chapterCount > 0 && state.chapterIndex < state.chapterCount - 1,
                ) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "下一章")
                }
            }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = controller::stop, enabled = active) {
                    Icon(Icons.Outlined.Stop, contentDescription = null)
                    Text("停止", modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = onBack, enabled = isThisBook) {
                    Text("查看朗读正文")
                }
            }

            Column(Modifier.fillMaxWidth().padding(top = 30.dp)) {
                Text("音色", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NarrationVoice.entries.forEach { voice ->
                        FilterChip(
                            selected = state.voice == voice,
                            onClick = { controller.setVoice(voice) },
                            label = { Text(voice.displayName) },
                        )
                    }
                }
                NarrationSpeedControls(
                    playbackSpeed = state.playbackSpeed,
                    onPlaybackSpeedChange = controller::setPlaybackSpeed,
                    modifier = Modifier.padding(top = 22.dp),
                )
                Text(
                    "朗读引擎与内置女声均在本机运行，朗读时不上传正文。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
