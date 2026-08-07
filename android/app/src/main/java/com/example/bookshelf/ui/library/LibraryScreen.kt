@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.example.bookshelf.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.example.bookshelf.ui.glass.GlassButton
import com.example.bookshelf.ui.glass.GlassDialog
import com.example.bookshelf.ui.glass.GlassIconButton
import com.example.bookshelf.ui.glass.GlassSectionTitle
import com.example.bookshelf.ui.glass.GlassSurface
import com.example.bookshelf.ui.glass.GlassTextField
import com.example.bookshelf.ui.glass.GlassTopBar
import com.example.bookshelf.ui.glass.LiquidBackdrop
import com.example.bookshelf.ui.glass.StaggeredReveal
import com.example.bookshelf.ui.glass.glassPalette
import com.example.bookshelf.ui.glass.rememberPressScale
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
    LiquidBackdrop(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            GlassTopBar(
                title = "书架",
                subtitle = "Page Shelf",
                actions = {
                    TextButton(onClick = { searchVisible = !searchVisible; if (!searchVisible) query = "" }) {
                        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("搜索", modifier = Modifier.padding(start = 4.dp))
                    }
                    TextButton(onClick = viewModel::refresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("刷新", modifier = Modifier.padding(start = 4.dp))
                    }
                    TextButton(onClick = onManage) {
                        Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("设置", modifier = Modifier.padding(start = 4.dp))
                    }
                },
            )
            AnimatedVisibility(
                visible = searchVisible,
                enter = fadeIn(tween(240)) + slideInVertically(tween(280, easing = FastOutSlowInEasing)) { -it / 2 },
                exit = fadeOut(tween(180)) + slideOutVertically(tween(200)) { -it / 2 },
            ) {
                GlassTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索书名或作者",
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
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
        GlassDialog(
            onDismissRequest = { largeBook = null },
            title = { Text("打开大文件", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text("“${book.title}”为 ${formatBytes(book.fileSize)}，首次打开可能需要更长时间。")
            },
            confirmButton = {
                GlassButton(onClick = { largeBook = null; onBookClick(book) }) { Text("继续打开") }
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
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(
            items = books,
            key = { _, book -> book.id },
            contentType = { _, _ -> "book-row" },
        ) { index, book ->
            StaggeredReveal(index = index) {
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
}

@Composable
private fun ShelfHeader(shelf: LibraryShelf, page: Int, count: Int, offline: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
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
        if (count > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(count) { i ->
                    val selected = i == page
                    val scale by animateFloatAsState(
                        targetValue = if (selected) 1f else 0.72f,
                        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
                        label = "shelf-dot-$i",
                    )
                    Box(
                        Modifier
                            .size(if (selected) 8.dp else 6.dp)
                            .graphicsLayer { scaleX = scale; scaleY = scale }
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            ),
                    )
                }
            }
        }
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
    val interaction = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interaction)
    val coverModel = remember(book.id, book.coverStatus, bookRepository, context) {
        if (book.coverStatus == "ready") bookRepository.coverRequest(context, book.id) else null
    }
    val progressFraction by animateFloatAsState(
        targetValue = (progress?.progression ?: 0f).toFloat().coerceIn(0f, 1f),
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "book-progress",
    )
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale },
        shape = MaterialTheme.shapes.medium,
        shadow = true,
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AsyncImage(
                model = coverModel,
                contentDescription = "${book.title}封面",
                error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                fallback = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .size(width = 72.dp, height = 100.dp)
                    .clip(MaterialTheme.shapes.extraSmall),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.weight(1f).height(100.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (download?.isPermanent == true && download.status in setOf(DownloadStatus.DOWNLOADED, DownloadStatus.OUTDATED)) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "已下载",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    book.author ?: "未知作者",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    progressSummary(book, progress),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (progress != null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progressFraction)
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
    val transition = rememberInfiniteTransition(label = "skeleton")
    val shimmer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "skeleton-shimmer",
    )
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(5) {
            GlassSurface(
                Modifier.fillMaxWidth().height(124.dp),
                shape = MaterialTheme.shapes.medium,
                specular = false,
                shadow = true,
            ) {
                Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SkeletonBlock(Modifier.size(72.dp, 100.dp), RoundedCornerShape(10.dp), shimmer)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SkeletonBlock(Modifier.fillMaxWidth(0.72f).height(18.dp), RoundedCornerShape(6.dp), shimmer)
                        SkeletonBlock(Modifier.fillMaxWidth(0.4f).height(14.dp), RoundedCornerShape(6.dp), shimmer)
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonBlock(modifier: Modifier = Modifier, shape: RoundedCornerShape, phase: Float) {
    val base = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        base.copy(alpha = 0.55f),
                        base.copy(alpha = 0.9f),
                        base.copy(alpha = 0.55f),
                    ),
                    start = androidx.compose.ui.geometry.Offset(phase * 400f, 0f),
                    end = androidx.compose.ui.geometry.Offset(phase * 400f + 300f, 100f),
                ),
            ),
    )
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
        StaggeredReveal(index = 0) {
            GlassSurface(
                Modifier.size(84.dp),
                shape = CircleShape,
                specular = true,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }
        StaggeredReveal(index = 1) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Text("请输入密码", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${shelf.name} · ${shelf.bookCount} 本",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        StaggeredReveal(index = 2) {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                GlassTextField(
                    value = pin,
                    onValueChange = onPinChange,
                    label = "四位访问密码",
                    singleLine = true,
                    isError = error != null,
                    supportingText = error,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (pin.length == 4 && !busy) onUnlock() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                GlassButton(
                    onClick = onUnlock,
                    enabled = pin.length == 4 && !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("进入书架")
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, retry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlassSurface(Modifier.size(72.dp), shape = CircleShape, specular = true) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.WifiOff, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("书架暂时无法加载", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        GlassButton(onClick = retry) { Text("重试") }
    }
}

@Composable
private fun EmptyShelf(message: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlassSurface(Modifier.size(72.dp), shape = CircleShape, specular = true) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyLibrary(modifier: Modifier, offline: Boolean) {
    Column(
        modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlassSurface(Modifier.size(84.dp), shape = CircleShape, specular = true) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    if (offline) Icons.Outlined.WifiOff else Icons.Outlined.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(if (offline) "没有可离线阅读的书" else "书架还是空的", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            if (offline) "联网后可返回完整书架。" else "服务器尚未提供书籍。",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
