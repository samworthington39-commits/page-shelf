@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.bookshelf.ui.manage

import com.example.bookshelf.BuildConfig
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bookshelf.domain.ReaderBackground
import com.example.bookshelf.domain.ReaderFont
import com.example.bookshelf.domain.ReaderViewMode
import com.example.bookshelf.domain.AppThemeMode
import com.example.bookshelf.ui.ManageViewModel
import com.example.bookshelf.ui.ManagedDownload
import com.example.bookshelf.ui.library.formatBytes
import kotlinx.coroutines.delay

@Composable
fun ManageScreen(viewModel: ManageViewModel, onBack: () -> Unit, onServerSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<ManagedDownload?>(null) }
    var deleteProgress by remember { mutableStateOf(false) }
    var confirmBatch by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("管理与设置") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { SectionTitle("已下载书籍", "${state.downloads.size} 本 · ${formatBytes(state.storageBytes)}") }
            if (state.downloads.isEmpty()) {
                item { Text("还没有保存到本地的书。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(state.downloads, key = { it.book.id }) { item ->
                    DownloadRow(
                        item = item,
                        selected = item.book.id in state.selected,
                        onToggle = { viewModel.toggleSelected(item.book.id) },
                        onDelete = { deleteProgress = false; deleteTarget = item },
                    )
                }
                item {
                    Button(onClick = { confirmBatch = true }, enabled = state.selected.isNotEmpty()) {
                        Text("删除所选下载（${state.selected.size}）")
                    }
                }
            }

            item { SectionTitle("本地存储", "临时缓存可安全清理，阅读进度不会丢失") }
            item { TextButton(onClick = viewModel::clearTemporary) { Text("清理临时缓存") } }
            state.cacheMessage?.let { message ->
                item {
                    Text(message, color = MaterialTheme.colorScheme.primary)
                    LaunchedEffect(message) { delay(2200); viewModel.clearMessage() }
                }
            }

            item { SectionTitle("界面外观", "与后台管理界面一致的纸张与墨绿色调") }
            item {
                PreferenceChips(
                    title = "明暗模式",
                    values = AppThemeMode.entries,
                    selected = state.preferences.themeMode,
                    label = {
                        when (it) {
                            AppThemeMode.SYSTEM -> "跟随系统"
                            AppThemeMode.LIGHT -> "浅色"
                            AppThemeMode.DARK -> "深色"
                        }
                    },
                    onSelect = { selected -> viewModel.updatePreferences { it.copy(themeMode = selected) } },
                )
            }

            item { SectionTitle("默认阅读设置", "新打开的书将使用这些设置") }
            item {
                PreferenceChips(
                    title = "翻页方式",
                    values = ReaderViewMode.entries,
                    selected = state.preferences.viewMode,
                    label = { if (it == ReaderViewMode.PAGED) "左右翻页" else "上下滑动" },
                    onSelect = { selected -> viewModel.updatePreferences { it.copy(viewMode = selected) } },
                )
            }
            item {
                Text("字体大小  ${state.preferences.fontSizeSp.toInt()}sp", fontWeight = FontWeight.Medium)
                Slider(
                    value = state.preferences.fontSizeSp,
                    onValueChange = { value -> viewModel.updatePreferences { it.copy(fontSizeSp = value) } },
                    valueRange = 14f..30f,
                    steps = 15,
                )
            }
            item {
                PreferenceChips(
                    title = "字体样式",
                    values = ReaderFont.entries,
                    selected = state.preferences.font,
                    label = {
                        when (it) {
                            ReaderFont.SANS -> "无衬线"
                            ReaderFont.SERIF -> "衬线"
                            ReaderFont.SONG -> "宋体"
                            ReaderFont.HEI -> "黑体"
                        }
                    },
                    onSelect = { selected -> viewModel.updatePreferences { it.copy(font = selected) } },
                )
            }
            item {
                PreferenceChips(
                    title = "阅读背景",
                    values = ReaderBackground.entries,
                    selected = state.preferences.background,
                    label = { it.chineseName() },
                    onSelect = { selected -> viewModel.updatePreferences { it.copy(background = selected) } },
                )
            }

            item { SectionTitle("服务器", "修改 IP、端口或管理密码") }
            item { TextButton(onClick = onServerSettings) { Text("服务器设置") } }
            item { SectionTitle("关于", "页架 ${BuildConfig.VERSION_NAME} · 专注在线与离线阅读") }
            item {
                Text(
                    "TXT、EPUB 与 MOBI 仅保留当前章前后各 5 章；PDF 使用原始文件按页渲染。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除本地下载") },
            text = {
                Column {
                    Text("将从手机删除“${target.book.title}”。服务器上的书籍不会被删除。")
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { deleteProgress = !deleteProgress }) {
                        Checkbox(checked = deleteProgress, onCheckedChange = { deleteProgress = it })
                        Text("同时删除本地阅读进度")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.delete(target.book.id, deleteProgress)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }

    if (confirmBatch) {
        AlertDialog(
            onDismissRequest = { confirmBatch = false },
            title = { Text("批量删除下载") },
            text = { Text("将删除 ${state.selected.size} 本书的本地文件，服务器书籍和阅读进度会保留。") },
            confirmButton = { Button(onClick = { viewModel.deleteSelected(); confirmBatch = false }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirmBatch = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun DownloadRow(item: ManagedDownload, selected: Boolean, onToggle: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(item.book.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${item.book.format.uppercase()} · ${formatBytes(item.download.totalBytes)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun <T> PreferenceChips(
    title: String,
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(values) { value ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label(value)) })
            }
        }
    }
}

private fun ReaderBackground.chineseName(): String = when (this) {
    ReaderBackground.AUTO -> "跟随明暗"
    ReaderBackground.PAPER -> "纸张白"
    ReaderBackground.WARM -> "暖白"
    ReaderBackground.WHITE -> "纯白"
    ReaderBackground.GRAY -> "浅灰"
    ReaderBackground.GREEN -> "护眼绿"
    ReaderBackground.DARK_GRAY -> "深灰"
    ReaderBackground.BLACK -> "近黑"
}
