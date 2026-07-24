@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.example.bookshelf.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.example.bookshelf.data.repository.BookRepository
import com.example.bookshelf.domain.Book
import com.example.bookshelf.domain.DownloadState
import com.example.bookshelf.domain.DownloadStatus
import com.example.bookshelf.domain.LibraryShelf
import com.example.bookshelf.domain.ReadingProgress
import com.example.bookshelf.domain.shouldWarnBeforeOpen
import com.example.bookshelf.ui.LibraryUiState
import com.example.bookshelf.ui.LibraryViewModel
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    bookRepository: BookRepository,
    onManage: () -> Unit,
    onBookClick: (Book) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var largeBook by remember { mutableStateOf<Book?>(null) }
    var searchVisible by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书架", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    TextButton(onClick = { searchVisible = !searchVisible; if (!searchVisible) query = "" }) { Text("搜索") }
                    TextButton(onClick = viewModel::refresh) { Text("刷新") }
                    TextButton(onClick = onManage) { Text("设置") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (searchVisible) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索书名或作者") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            when (val value = state) {
                LibraryUiState.Loading -> LibrarySkeleton(Modifier.fillMaxSize())
                is LibraryUiState.Error -> ErrorState(value.message, viewModel::refresh, Modifier.fillMaxSize())
                is LibraryUiState.Content -> PullToRefreshBox(
                    isRefreshing = value.isRefreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    ShelfPager(
                        state = value,
                        query = query,
                        bookRepository = bookRepository,
                        onUnlock = viewModel::unlockShelf,
                        onBookClick = { book -> if (book.shouldWarnBeforeOpen()) largeBook = book else onBookClick(book) },
                    )
                }
            }
        }
    }

    largeBook?.let { book ->
        AlertDialog(
            onDismissRequest = { largeBook = null },
            title = { Text("打开大文件") },
            text = { Text("“${book.title}”为 ${formatBytes(book.fileSize)}，首次打开可能需要更长时间。") },
            confirmButton = {
                Button(onClick = { largeBook = null; onBookClick(book) }) { Text("继续打开") }
            },
            dismissButton = { TextButton(onClick = { largeBook = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ShelfPager(
    state: LibraryUiState.Content,
    query: String,
    bookRepository: BookRepository,
    onUnlock: (String, String) -> Unit,
    onBookClick: (Book) -> Unit,
) {
    if (state.shelves.isEmpty()) {
        EmptyLibrary(Modifier.fillMaxSize(), state.offline)
        return
    }
    val pager = rememberPagerState(pageCount = { state.shelves.size })
    val pins = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(state.shelves.size) {
        if (pager.currentPage > state.shelves.lastIndex) pager.scrollToPage(state.shelves.lastIndex)
    }
    val headerPage by remember(pager, state.shelves.size) {
        derivedStateOf { pager.settledPage.coerceIn(state.shelves.indices) }
    }
    Column(Modifier.fillMaxSize()) {
        val current = state.shelves[headerPage]
        ShelfHeader(current, headerPage, state.shelves.size, state.offline)
        HorizontalPager(
            state = pager,
            userScrollEnabled = state.shelves.size > 1,
            // Pager already prefetches in the scroll direction. Keeping another complete
            // LazyColumn on each side composed can start cover work halfway through a drag.
            beyondViewportPageCount = 0,
            key = { page -> state.shelves[page].id },
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { page ->
            val shelf = state.shelves[page]
            ShelfPage(
                shelf = shelf,
                query = query,
                progress = state.progress,
                downloads = state.downloads,
                bookRepository = bookRepository,
                pin = pins[shelf.id].orEmpty(),
                unlockBusy = state.unlockingShelfId == shelf.id,
                unlockError = state.unlockError.takeIf { state.unlockErrorShelfId == shelf.id },
                onPinChange = { pins[shelf.id] = it.filter(Char::isDigit).take(4) },
                onUnlock = { onUnlock(shelf.id, pins[shelf.id].orEmpty()) },
                onBookClick = onBookClick,
            )
        }
    }
}

@Composable
private fun ShelfPage(
    shelf: LibraryShelf,
    query: String,
    progress: Map<String, ReadingProgress>,
    downloads: Map<String, DownloadState>,
    bookRepository: BookRepository,
    pin: String,
    unlockBusy: Boolean,
    unlockError: String?,
    onPinChange: (String) -> Unit,
    onUnlock: () -> Unit,
    onBookClick: (Book) -> Unit,
) {
    if (shelf.locked) {
        LockedShelf(shelf, pin, unlockBusy, unlockError, onPinChange, onUnlock)
        return
    }

    val books = remember(shelf.books, query, progress) {
        shelf.books
            .asSequence()
            .filter { query.isBlank() || it.title.contains(query, true) || it.author?.contains(query, true) == true }
            .sortedWith(compareByDescending<Book> { progress[it.id]?.updatedAtEpochMs ?: Long.MIN_VALUE }.thenBy { it.title })
            .toList()
    }
    if (books.isEmpty()) {
        EmptyShelf(if (query.isBlank()) "这个书架还没有书" else "没有找到匹配的书")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(
            items = books,
            key = Book::id,
            contentType = { "book-row" },
        ) { book ->
            BookRow(
                book = book,
                bookRepository = bookRepository,
                progress = progress[book.id],
                download = downloads[book.id],
                onClick = { onBookClick(book) },
            )
        }
    }
}

@Composable
private fun ShelfHeader(shelf: LibraryShelf, page: Int, count: Int, offline: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(shelf.name, style = MaterialTheme.typography.titleLarge)
            Text(
                if (offline) "离线可读 · ${shelf.bookCount} 本" else "${shelf.bookCount} 本 · ${formatBytes(shelf.totalBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (count > 1) Text("${page + 1} / $count", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BookRow(
    book: Book,
    bookRepository: BookRepository,
    progress: ReadingProgress?,
    download: DownloadState?,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val coverModel = remember(book.id, book.coverStatus, bookRepository, context) {
        if (book.coverStatus == "ready") bookRepository.coverRequest(context, book.id) else null
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 1.dp),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AsyncImage(
                model = coverModel,
                contentDescription = "${book.title}封面",
                error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                fallback = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.size(width = 72.dp, height = 100.dp).clip(MaterialTheme.shapes.extraSmall),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.weight(1f).height(100.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (download?.isPermanent == true && download.status in setOf(DownloadStatus.DOWNLOADED, DownloadStatus.OUTDATED)) {
                        Spacer(Modifier.width(8.dp))
                        Text("已下载", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    book.author ?: "未知作者",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(progressSummary(book, progress), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (progress != null) {
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(
                            Modifier.fillMaxWidth(progress.progression.toFloat().coerceIn(0f, 1f))
                                .height(3.dp)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }
    }
}

private fun progressSummary(book: Book, progress: ReadingProgress?): String {
    if (progress == null) return if (book.format == "pdf") "PDF · ${book.pageCount ?: 0} 页" else "${book.format.uppercase()} · ${book.chapterCount ?: 0} 章"
    val percent = (progress.progression * 100).roundToInt().coerceIn(0, 100)
    return if (book.format == "pdf") "第 ${progress.pdfPage + 1} / ${book.pageCount ?: progress.pageCount ?: 0} 页 · $percent%"
    else "${progress.chapterTitle ?: "第 ${progress.chapterIndex + 1} 章"} · ${(progress.chapterProgress * 100).roundToInt()}%"
}

@Composable
private fun LibrarySkeleton(modifier: Modifier = Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(5) {
            Row(
                Modifier.fillMaxWidth().height(124.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(72.dp, 100.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.fillMaxWidth(0.72f).height(18.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                    Box(Modifier.fillMaxWidth(0.4f).height(14.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                }
            }
        }
    }
}

@Composable
private fun LockedShelf(
    shelf: LibraryShelf,
    pin: String,
    busy: Boolean,
    error: String?,
    onPinChange: (String) -> Unit,
    onUnlock: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("请输入密码", style = MaterialTheme.typography.titleLarge)
        Text(
            "${shelf.name} · ${shelf.bookCount} 本",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = onPinChange,
            label = { Text("四位访问密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (pin.length == 4 && !busy) onUnlock() }),
            isError = error != null,
            supportingText = error?.let { message -> { Text(message) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onUnlock, enabled = pin.length == 4 && !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "正在验证" else "进入书架")
        }
    }
}

@Composable
private fun ErrorState(message: String, retry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text("书架暂时无法加载", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = retry) { Text("重试") }
    }
}

@Composable
private fun EmptyShelf(message: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyLibrary(modifier: Modifier, offline: Boolean) {
    Column(modifier.padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text(if (offline) "没有可离线阅读的书" else "书架还是空的", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(if (offline) "联网后可返回完整书架。" else "服务器尚未提供书籍。", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
