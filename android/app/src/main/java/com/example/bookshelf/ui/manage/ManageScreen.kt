package com.example.bookshelf.ui.manage

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bookshelf.BuildConfig
import com.example.bookshelf.domain.AppThemeMode
import com.example.bookshelf.domain.ReaderBackground
import com.example.bookshelf.domain.ReaderFont
import com.example.bookshelf.ui.ManageViewModel
import com.example.bookshelf.ui.ManagedDownload
import com.example.bookshelf.ui.glass.GlassButton
import com.example.bookshelf.ui.glass.GlassChip
import com.example.bookshelf.ui.glass.GlassDialog
import com.example.bookshelf.ui.glass.GlassSectionTitle
import com.example.bookshelf.ui.glass.GlassSurface
import com.example.bookshelf.ui.glass.FloatingCapsuleContainer
import com.example.bookshelf.ui.glass.GlassTopBar
import com.example.bookshelf.ui.glass.LiquidBackdrop
import com.example.bookshelf.ui.glass.LiquidBackdropStyle
import com.example.bookshelf.ui.glass.StaggeredReveal
import com.example.bookshelf.ui.library.formatBytes
import kotlinx.coroutines.delay

@Composable
fun ManageScreen(viewModel: ManageViewModel, onBack: () -> Unit, onServerSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<ManagedDownload?>(null) }
    var deleteProgress by remember { mutableStateOf(false) }
    var confirmBatch by remember { mutableStateOf(false) }
    LiquidBackdrop(Modifier.fillMaxSize(), style = LiquidBackdropStyle.Library) {
        FloatingCapsuleContainer(
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            topBar = {
                GlassTopBar(
                    title = "管理与设置",
                    subtitle = "下载、外观与服务器",
                    onBack = onBack,
                )
            },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
            item {
                GlassSurface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, shadow = true) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GlassSectionTitle("已下载书籍", "${state.downloads.size} 本 · ${formatBytes(state.storageBytes)}")
                        if (state.downloads.isEmpty()) {
                            Text("还没有保存到本地的书。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            state.downloads.forEachIndexed { index, item ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                DownloadRow(
                                    item = item,
                                    selected = item.book.id in state.selected,
                                    onToggle = { viewModel.toggleSelected(item.book.id) },
                                    onDelete = { deleteProgress = false; deleteTarget = item },
                                )
                            }
                            GlassButton(
                                onClick = { confirmBatch = true },
                                enabled = state.selected.isNotEmpty(),
                                filled = false,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("删除所选下载（${state.selected.size}）")
                            }
                        }
                    }
                }
            }

            item {
                GlassSurface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, shadow = true) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GlassSectionTitle("本地存储", "临时缓存可安全清理，阅读进度不会丢失")
                        TextButton(onClick = viewModel::clearTemporary) { Text("清理临时缓存") }
                        state.cacheMessage?.let { message ->
                            Text(message, color = MaterialTheme.colorScheme.primary)
                            LaunchedEffect(message) { delay(2200); viewModel.clearMessage() }
                        }
                    }
                }
            }

            item {
                GlassSurface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, shadow = true) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        GlassSectionTitle("界面外观", "显示、字体与阅读背景")
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
                        PreferenceChips(
                            title = "阅读字体",
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
                        PreferenceChips(
                            title = "阅读背景",
                            values = ReaderBackground.entries,
                            selected = state.preferences.background,
                            label = { it.chineseName() },
                            onSelect = { selected -> viewModel.updatePreferences { it.copy(background = selected) } },
                        )
                    }
                }
            }

            item {
                GlassSurface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, shadow = true) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GlassSectionTitle("服务器", "修改 IP、端口或管理密码")
                        TextButton(onClick = onServerSettings) { Text("服务器设置") }
                    }
                }
            }

            item {
                GlassSurface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, shadow = true) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GlassSectionTitle("关于", "页架 ${BuildConfig.VERSION_NAME} · 专注在线与离线阅读")
                        Text(
                            "TXT、EPUB 与 MOBI 仅保留当前章前后各 5 章；PDF 使用原始文件按页渲染。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            }
        }
    }

    deleteTarget?.let { target ->
        GlassDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除本地下载", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Text("将从手机删除“${target.book.title}”。服务器上的书籍不会被删除。")
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { deleteProgress = !deleteProgress },
                    ) {
                        Checkbox(
                            checked = deleteProgress,
                            onCheckedChange = { deleteProgress = it },
                            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                        )
                        Text("同时删除本地阅读进度")
                    }
                }
            },
            confirmButton = {
                GlassButton(onClick = {
                    viewModel.delete(target.book.id, deleteProgress)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }

    if (confirmBatch) {
        GlassDialog(
            onDismissRequest = { confirmBatch = false },
            title = { Text("批量删除下载", style = MaterialTheme.typography.titleLarge) },
            text = { Text("将删除 ${state.selected.size} 本书的本地文件，服务器书籍和阅读进度会保留。") },
            confirmButton = {
                GlassButton(onClick = { viewModel.deleteSelected(); confirmBatch = false }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmBatch = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun DownloadRow(item: ManagedDownload, selected: Boolean, onToggle: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
        )
        Column(Modifier.weight(1f)) {
            Text(item.book.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "${item.book.format.uppercase()} · ${formatBytes(item.download.totalBytes)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(onClick = onDelete) { Text("删除") }
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
        Text(title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(values) { value ->
                GlassChip(selected = value == selected, onClick = { onSelect(value) }, label = label(value))
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
