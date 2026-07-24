@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.example.bookshelf.ui.reader

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bookshelf.domain.DownloadState
import com.example.bookshelf.domain.AppThemeMode
import com.example.bookshelf.domain.DownloadStatus
import com.example.bookshelf.domain.PdfNavigationItem
import com.example.bookshelf.ui.ReaderUiState
import com.example.bookshelf.ui.ReaderViewModel
import com.example.bookshelf.ui.library.formatBytes
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

enum class PdfReaderMode { CONTINUOUS, SINGLE_PAGE }

@Composable
fun PdfReaderScreen(viewModel: ReaderViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val localPath = state.download?.localPath
    val appDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    BackHandler { viewModel.exit(onBack) }
    PersistOnBackground(viewModel::persistNow)
    when {
        state.error != null -> ReaderMessage(state.error ?: "无法打开 PDF", onBack)
        localPath != null && File(localPath).isFile -> {
            PdfDocument(
                key = "$localPath-${state.positionRevision}",
                path = localPath,
                title = state.book?.title.orEmpty(),
                initialPageIndex = state.initialPageIndex,
                navigation = state.navigation,
                download = state.download,
                onPageChanged = viewModel::onPageChanged,
                onBack = { viewModel.exit(onBack) },
                onTheme = { viewModel.setTheme(if (appDark) AppThemeMode.LIGHT else AppThemeMode.DARK) },
                onSave = viewModel::saveOffline,
                onPause = viewModel::pauseDownload,
            )
        }
        else -> DownloadGate(state.download, onBack = { viewModel.exit(onBack) }, onRetry = viewModel::retryDownload)
    }
    state.conflict?.let { conflict ->
        ProgressConflictDialog(
            conflict,
            onLocal = { viewModel.resolveConflict(true) },
            onRemote = { viewModel.resolveConflict(false) },
            onCancel = { viewModel.exit(onBack) },
        )
    }
}

@Composable
private fun PdfDocument(
    key: String,
    path: String,
    title: String,
    initialPageIndex: Int,
    navigation: List<PdfNavigationItem>,
    download: DownloadState?,
    onPageChanged: (Int, Int, Double, String) -> Unit,
    onBack: () -> Unit,
    onTheme: () -> Unit,
    onSave: () -> Unit,
    onPause: () -> Unit,
) {
    val sessionResult = remember(key) { runCatching { PdfRendererSession(File(path)) } }
    val session = sessionResult.getOrNull()
    if (session == null) {
        ReaderMessage(sessionResult.exceptionOrNull()?.message ?: "PDF 无法打开", onBack)
        return
    }
    DisposableEffect(session) { onDispose(session::close) }
    val pageCount = session.pageCount
    if (pageCount <= 0) {
        ReaderMessage("PDF 没有可显示的页面", onBack)
        return
    }

    var controlsVisible by rememberSaveable(key) { mutableStateOf(false) }
    var showSettings by rememberSaveable(key) { mutableStateOf(false) }
    var showBookmarks by rememberSaveable(key) { mutableStateOf(false) }
    var currentPage by rememberSaveable(key) { mutableIntStateOf(initialPageIndex.coerceIn(0, pageCount - 1)) }
    var mode by rememberSaveable { mutableStateOf(PdfReaderMode.SINGLE_PAGE) }
    var fitMode by rememberSaveable { mutableStateOf(FitMode.PAGE) }
    val continuousState = rememberLazyListState(initialFirstVisibleItemIndex = currentPage)
    val pager = rememberPagerState(initialPage = currentPage, pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    ImmersiveSystemBars(enabled = !showSettings && !showBookmarks, showBars = controlsVisible)

    fun jumpTo(index: Int) {
        val target = index.coerceIn(0, pageCount - 1)
        scope.launch {
            if (mode == PdfReaderMode.CONTINUOUS) {
                currentPage = target
                continuousState.scrollToItem(target)
            } else {
                pager.scrollToPage(target)
            }
        }
    }

    LaunchedEffect(mode, pager, continuousState) {
        if (mode == PdfReaderMode.CONTINUOUS) {
            snapshotFlow { continuousState.firstVisibleItemIndex }.distinctUntilChanged().collect { currentPage = it }
        } else {
            snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { currentPage = it }
        }
    }
    LaunchedEffect(currentPage, mode) {
        session.retainWindow(currentPage)
        onPageChanged(currentPage, pageCount, 0.0, mode.name.lowercase())
    }

    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(mode) {
            if (mode == PdfReaderMode.CONTINUOUS) {
                detectTapGestures { point ->
                    if (point.x in size.width * 0.25f..size.width * 0.75f) controlsVisible = !controlsVisible
                }
            }
        },
    ) {
        if (mode == PdfReaderMode.CONTINUOUS) {
            LazyColumn(
                state = continuousState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(pageCount, key = { it }) { index ->
                    ZoomablePdfPage(session, index, fitMode, Modifier.fillMaxWidth())
                }
            }
        } else {
            HorizontalPager(
                state = pager,
                beyondViewportPageCount = 1,
                key = { page -> page },
                modifier = Modifier.fillMaxSize().pointerInput(key, controlsVisible) {
                    detectTapGestures { point ->
                        when {
                            point.x < size.width * 0.25f && currentPage > 0 -> jumpTo(currentPage - 1)
                            point.x > size.width * 0.75f && currentPage < pageCount - 1 -> jumpTo(currentPage + 1)
                            point.x in size.width * 0.25f..size.width * 0.75f -> controlsVisible = !controlsVisible
                        }
                    }
                },
            ) { index -> ZoomablePdfPage(session, index, fitMode, Modifier.fillMaxSize()) }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            PdfTopBar(title, download, onBack, onTheme, onSave, onPause, { showSettings = true })
        }
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PdfBottomBar(
                currentPage = currentPage,
                pageCount = pageCount,
                hasToc = navigation.isNotEmpty(),
                onOpenToc = { showBookmarks = true },
                onPrevious = { jumpTo(currentPage - 1) },
                onNext = { jumpTo(currentPage + 1) },
                onSeek = ::jumpTo,
            )
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("PDF 阅读设置", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PdfReaderMode.entries.forEach { value ->
                        FilterChip(
                            selected = mode == value,
                            onClick = { mode = value; jumpTo(currentPage) },
                            label = { Text(if (value == PdfReaderMode.SINGLE_PAGE) "左右翻页" else "上下滑动") },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FitMode.entries.forEach { value ->
                        FilterChip(selected = fitMode == value, onClick = { fitMode = value }, label = { Text(if (value == FitMode.PAGE) "适合页面" else "适合宽度") })
                    }
                }
            }
        }
    }
    if (showBookmarks) {
        BookmarksDialog(navigation, onDismiss = { showBookmarks = false }) { page -> showBookmarks = false; jumpTo(page - 1) }
    }
}

@Composable
private fun PdfTopBar(
    title: String,
    download: DownloadState?,
    onBack: () -> Unit,
    onTheme: () -> Unit,
    onSave: () -> Unit,
    onPause: () -> Unit,
    onSettings: () -> Unit,
) {
    val downloading = download?.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回书架并保存进度")
        }
        Text(title, modifier = Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onTheme) {
            Icon(Icons.Outlined.Lightbulb, contentDescription = "切换明暗模式")
        }
        IconButton(
            onClick = if (downloading) onPause else onSave,
            enabled = download?.status != DownloadStatus.DOWNLOADED || download?.isPermanent != true,
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (downloading) {
                    CircularProgressIndicator(
                        progress = { download?.fraction ?: 0f },
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Icon(
                    Icons.Outlined.Download,
                    contentDescription = when {
                        download?.status == DownloadStatus.DOWNLOADED && download?.isPermanent == true -> "已下载到本地"
                        downloading -> "暂停下载，已完成 ${((download?.fraction ?: 0f) * 100).roundToInt()}%"
                        else -> "保存整本书到本地"
                    },
                )
            }
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Outlined.Settings, contentDescription = "PDF 阅读设置")
        }
    }
}

@Composable
private fun PdfBottomBar(
    currentPage: Int,
    pageCount: Int,
    hasToc: Boolean,
    onOpenToc: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Int) -> Unit,
) {
    var slider by remember(currentPage) { mutableFloatStateOf(currentPage.toFloat()) }
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onOpenToc, enabled = hasToc) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null)
                Text("目录", modifier = Modifier.padding(start = 6.dp))
            }
            Text(
                "第 ${slider.roundToInt() + 1} / $pageCount 页",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPrevious, enabled = currentPage > 0) { Text("上一页") }
            Slider(
                value = slider,
                onValueChange = { slider = it },
                onValueChangeFinished = { onSeek(slider.roundToInt()) },
                valueRange = 0f..(pageCount - 1).toFloat().coerceAtLeast(1f),
                modifier = Modifier.weight(1f).semantics { contentDescription = "PDF 总阅读进度" },
            )
            TextButton(onClick = onNext, enabled = currentPage < pageCount - 1) { Text("下一页") }
        }
    }
}

@Composable
private fun ZoomablePdfPage(session: PdfRendererSession, pageIndex: Int, fitMode: FitMode, modifier: Modifier = Modifier) {
    var scale by remember(pageIndex, fitMode) { mutableFloatStateOf(1f) }
    var offset by remember(pageIndex, fitMode) { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val heightPx = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
        var bitmap by remember(pageIndex, fitMode, widthPx, heightPx) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(pageIndex, fitMode, widthPx, heightPx) { bitmap = session.render(pageIndex, widthPx, heightPx, fitMode) }
        val rendered = bitmap
        if (rendered == null || rendered.isRecycled) {
            CircularProgressIndicator(Modifier.semantics { contentDescription = "正在渲染第${pageIndex + 1}页" })
        } else {
            Image(
                bitmap = rendered.asImageBitmap(),
                contentDescription = "PDF 第${pageIndex + 1}页",
                contentScale = ContentScale.Fit,
                modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                    .pointerInput(pageIndex) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(pageIndex) {
                        detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2f })
                    },
            )
        }
    }
}

@Composable
private fun BookmarksDialog(items: List<PdfNavigationItem>, onDismiss: () -> Unit, onJump: (Int) -> Unit) {
    val flattened = remember(items) { flattenNavigation(items) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PDF 书签") },
        text = {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(flattened) { (item, depth) ->
                    TextButton(onClick = { onJump(item.page) }, modifier = Modifier.fillMaxWidth().padding(start = (depth * 16).dp)) {
                        Text("${item.title} · ${item.page}", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun flattenNavigation(items: List<PdfNavigationItem>, depth: Int = 0): List<Pair<PdfNavigationItem, Int>> =
    items.flatMap { listOf(it to depth) + flattenNavigation(it.children, depth + 1) }

@Composable
private fun DownloadGate(download: DownloadState?, onBack: () -> Unit, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        Arrangement.Center,
        Alignment.CenterHorizontally,
    ) {
        when (download?.status) {
            DownloadStatus.FAILED -> {
                Text(download.error ?: "PDF 准备失败", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) { Text("重试") }
            }
            else -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("正在准备 PDF 页面缓存")
                if (download != null) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { download.fraction }, modifier = Modifier.fillMaxWidth())
                    Text("${formatBytes(download.bytesDownloaded)} / ${formatBytes(download.totalBytes)}")
                }
            }
        }
        TextButton(onClick = onBack) { Text("返回") }
    }
}

@Composable
private fun ReaderMessage(message: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onBack) { Text("返回") }
    }
}
