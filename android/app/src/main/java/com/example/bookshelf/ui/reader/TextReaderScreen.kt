@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.example.bookshelf.ui.reader

import android.app.Activity
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bookshelf.domain.DownloadStatus
import com.example.bookshelf.domain.AppThemeMode
import com.example.bookshelf.domain.ProgressResolution
import com.example.bookshelf.domain.ReaderBackground
import com.example.bookshelf.domain.ReaderFont
import com.example.bookshelf.domain.ReaderPreferences
import com.example.bookshelf.domain.ReaderViewMode
import com.example.bookshelf.domain.ReadingProgress
import com.example.bookshelf.narration.NarrationStatus
import com.example.bookshelf.ui.narration.NarrationSpeedControls
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TextReaderScreen(viewModel: TextReaderViewModel, onBack: () -> Unit, onOpenNarration: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var narrationMenuExpanded by remember { mutableStateOf(false) }
    var visiblePosition by remember(
        state.chapter?.id,
        state.preferences.viewMode,
        state.positionRevision,
    ) { mutableStateOf(VisibleTextPosition(state.chapterIndex, state.charOffset)) }
    val appDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val palette = readerPalette(state.preferences.background, appDark)
    val readerBusy = state.loading || state.chapterLoading || state.layoutLoading
    BackHandler { viewModel.exit(onBack) }
    PersistOnBackground(viewModel::persistNow)
    ImmersiveSystemBars(state.chapter != null && !readerBusy && !showSettings && !showToc, controlsVisible)
    LaunchedEffect(readerBusy) {
        if (readerBusy) {
            controlsVisible = false
            showSettings = false
            showToc = false
            narrationMenuExpanded = false
        }
    }
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) narrationMenuExpanded = false
    }

    Box(
        Modifier.fillMaxSize()
            .background(palette.background),
    ) {
        when {
            state.loading || (state.chapterLoading && state.chapter == null) -> ReaderLoadingScreen(
                message = "正在加载中",
                detail = if (state.preloadTotal > 0) "正在准备当前章节与前后 5 章" else "正在连接书库并读取目录",
                completed = state.preloadCompleted,
                total = state.preloadTotal,
                modifier = Modifier.fillMaxSize(),
            )
            state.error != null && state.chapter == null -> ReaderError(requireNotNull(state.error), viewModel::retry, Modifier.align(Alignment.Center), palette.foreground)
            state.chapter != null -> {
                val chapter = requireNotNull(state.chapter)
                val narrationHighlight = state.narration.takeIf { narration ->
                    narration.isActive &&
                        narration.bookId == state.book?.id &&
                        narration.chapterId == chapter.id &&
                        narration.currentText.isNotBlank()
                }?.let { narration ->
                    NarrationHighlight(
                        start = narration.charOffset.coerceIn(0, chapter.body.length),
                        end = narration.currentTextEndOffset.coerceIn(0, chapter.body.length),
                    )
                }?.takeIf { it.end > it.start }
                if (state.preferences.viewMode == ReaderViewMode.SCROLL) {
                    ContinuousScrollingChapters(
                        chapters = state.chapters,
                        currentChapterIndex = state.chapterIndex,
                        currentOffset = state.charOffset,
                        positionRevision = state.positionRevision,
                        chapterCount = state.toc.size,
                        preferences = state.preferences,
                        colors = palette,
                        narrationHighlight = narrationHighlight,
                        controlsVisible = controlsVisible,
                        onToggleControls = { controlsVisible = !controlsVisible },
                        onPositionChanged = viewModel::onPositionChanged,
                        onVisiblePositionChanged = { chapterIndex, charOffset ->
                            visiblePosition = VisibleTextPosition(chapterIndex, charOffset)
                        },
                        onEnsureChapter = viewModel::ensureChapter,
                    )
                } else {
                    ContinuousPagedChapters(
                        chapters = state.chapters,
                        currentChapterIndex = state.chapterIndex,
                        currentOffset = state.charOffset,
                        positionRevision = state.positionRevision,
                        chapterCount = state.toc.size,
                        preferences = state.preferences,
                        colors = palette,
                        narrationHighlight = narrationHighlight,
                        controlsVisible = controlsVisible,
                        onToggleControls = { controlsVisible = !controlsVisible },
                        onPositionChanged = viewModel::onPositionChanged,
                        onVisiblePositionChanged = { chapterIndex, charOffset ->
                            visiblePosition = VisibleTextPosition(chapterIndex, charOffset)
                        },
                        onEnsureChapter = viewModel::ensureChapter,
                    )
                }
                if (state.chapterLoading) {
                    Box(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && state.chapter != null && !readerBusy,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(
                state = state,
                onBack = { viewModel.exit(onBack) },
                onTheme = { viewModel.setTheme(if (appDark) AppThemeMode.LIGHT else AppThemeMode.DARK) },
                onSave = viewModel::saveOffline,
                onPause = viewModel::pauseDownload,
                onSettings = { showSettings = true },
            )
        }
        AnimatedVisibility(
            visible = controlsVisible && state.chapter != null && !readerBusy,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(
                state = state,
                onPrevious = viewModel::previousChapter,
                onNext = viewModel::nextChapter,
                onSeek = { fraction ->
                    val length = state.chapter?.body?.length ?: 0
                    viewModel.onPositionChanged((length * fraction).roundToInt())
                },
                onOpenToc = { showToc = true },
            )
        }
        AnimatedVisibility(
            visible = controlsVisible && state.chapter != null && !readerBusy,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            NarrationMenu(
                expanded = narrationMenuExpanded,
                active = state.narration.isActive && state.narration.bookId == state.book?.id,
                paused = state.narration.status == NarrationStatus.PAUSED,
                controlsVisible = controlsVisible,
                onToggle = { narrationMenuExpanded = !narrationMenuExpanded },
                onTogglePlayback = viewModel::toggleNarrationPlayback,
                onStop = {
                    narrationMenuExpanded = false
                    viewModel.stopNarration()
                },
                onStart = {
                    narrationMenuExpanded = false
                    viewModel.startNarrationFromCurrentPage(
                        visiblePosition.chapterIndex,
                        visiblePosition.charOffset,
                    )
                },
                onFollow = {
                    narrationMenuExpanded = false
                    controlsVisible = false
                    viewModel.followNarration()
                },
                onOpenListeningMode = {
                    narrationMenuExpanded = false
                    viewModel.prepareNarrationPage(
                        visiblePosition.chapterIndex,
                        visiblePosition.charOffset,
                    )
                    onOpenNarration()
                },
            )
        }
    }

    if (showSettings) {
        ReaderSettingsSheet(
            preferences = state.preferences,
            narrationPlaybackSpeed = state.narration.playbackSpeed,
            onMode = viewModel::setViewMode,
            onFontSize = viewModel::setFontSize,
            onFont = viewModel::setFont,
            onBackground = viewModel::setBackground,
            onLineHeight = viewModel::setLineHeight,
            onNarrationPlaybackSpeed = viewModel::setNarrationPlaybackSpeed,
            onDismiss = { showSettings = false },
        )
    }
    if (showToc) {
        val currentChapterIndex = state.chapterIndex.coerceIn(state.toc.indices)
        val tocListState = rememberLazyListState(initialFirstVisibleItemIndex = currentChapterIndex)
        LaunchedEffect(currentChapterIndex) {
            tocListState.scrollToItem(currentChapterIndex)
        }
        ModalBottomSheet(onDismissRequest = { showToc = false }) {
            Text("目录", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            LazyColumn(state = tocListState) {
                itemsIndexed(state.toc, key = { _, item -> item.id }) { index, item ->
                    val isCurrentChapter = index == currentChapterIndex
                    TextButton(
                        onClick = { showToc = false; viewModel.selectChapter(index) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            item.title,
                            color = if (isCurrentChapter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isCurrentChapter) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            textAlign = TextAlign.Start,
                        )
                    }
                }
            }
        }
    }
    state.conflict?.takeIf { !readerBusy }?.let { conflict ->
        ProgressConflictDialog(
            conflict,
            onLocal = { viewModel.resolveConflict(true) },
            onRemote = { viewModel.resolveConflict(false) },
            onCancel = { viewModel.exit(onBack) },
        )
    }
}

@Composable
private fun NarrationMenu(
    expanded: Boolean,
    active: Boolean,
    paused: Boolean,
    controlsVisible: Boolean,
    onToggle: () -> Unit,
    onTogglePlayback: () -> Unit,
    onStop: () -> Unit,
    onStart: () -> Unit,
    onFollow: () -> Unit,
    onOpenListeningMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .navigationBarsPadding()
            .padding(end = 12.dp, bottom = if (controlsVisible) 126.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + slideInHorizontally { it / 2 },
            exit = fadeOut() + slideOutHorizontally { it / 2 },
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 3.dp,
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    TextButton(onClick = onTogglePlayback, enabled = active) {
                        Icon(
                            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = null,
                        )
                        Text(if (paused) "继续朗读" else "暂停朗读", modifier = Modifier.padding(start = 6.dp))
                    }
                    TextButton(onClick = onStop, enabled = active) { Text("停止朗读") }
                    TextButton(onClick = onStart) { Text("从当前页朗读") }
                    TextButton(onClick = onFollow, enabled = active) { Text("前往朗读页") }
                    TextButton(onClick = onOpenListeningMode) { Text("听书模式") }
                }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 3.dp,
        ) {
            IconButton(onClick = onToggle) {
                Icon(
                    Icons.Outlined.RecordVoiceOver,
                    tint = MaterialTheme.colorScheme.primary,
                    contentDescription = if (expanded) "收起朗读选项" else "展开朗读选项",
                )
            }
        }
    }
}

@Composable
private fun ReaderTopBar(
    state: TextReaderUiState,
    onBack: () -> Unit,
    onTheme: () -> Unit,
    onSave: () -> Unit,
    onPause: () -> Unit,
    onSettings: () -> Unit,
) {
    val download = state.download
    val downloading = download?.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回书架并保存进度")
            }
            Text(
                state.book?.title.orEmpty(),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                style = MaterialTheme.typography.titleMedium,
            )
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
                Icon(Icons.Outlined.Settings, contentDescription = "阅读设置")
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    state: TextReaderUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onOpenToc: () -> Unit,
) {
    var slider by remember(state.chapter?.id, state.charOffset) { mutableFloatStateOf(state.chapterProgress.toFloat()) }
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onOpenToc) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null)
                    Text("目录", modifier = Modifier.padding(start = 6.dp))
                }
                Text(
                    "${state.chapter?.title.orEmpty()} · ${(slider * 100).roundToInt()}%",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPrevious, enabled = state.chapterIndex > 0) { Text("上一章") }
                Slider(
                    value = slider,
                    onValueChange = { slider = it },
                    onValueChangeFinished = { onSeek(slider) },
                    modifier = Modifier.weight(1f).semantics { contentDescription = "当前章节阅读进度" },
                )
                TextButton(onClick = onNext, enabled = state.chapterIndex < state.toc.lastIndex) { Text("下一章") }
            }
        }
    }
}

@Composable
private fun PagedChapter(
    key: String,
    body: String,
    initialOffset: Int,
    preferences: ReaderPreferences,
    colors: ReaderPalette,
    narrationHighlight: NarrationHighlight?,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onPositionChanged: (Int) -> Unit,
    onVisibleStartChanged: (Int) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onLayoutStarted: () -> Unit,
    onLayoutReady: () -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxSize()
            // Keep prose below the physical cutout even while immersive mode hides system bars.
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 24.dp),
    ) {
        val density = LocalDensity.current
        val style = readerTextStyle(preferences, colors.foreground)
        val narrationColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }
        val textSizePx = with(density) { preferences.fontSizeSp.sp.toPx() }
        val paginationSpec = remember(textSizePx, preferences.lineHeightMultiplier, preferences.font) {
            TextPaginationSpec(
                textSizePx = textSizePx,
                lineHeightMultiplier = preferences.lineHeightMultiplier,
                serif = preferences.font in setOf(ReaderFont.SERIF, ReaderFont.SONG),
            )
        }
        var pages by remember { mutableStateOf<List<TextPage>?>(null) }
        var displayedBody by remember { mutableStateOf("") }
        var displayedKey by remember { mutableStateOf<String?>(null) }
        var displayedInitialOffset by remember { mutableIntStateOf(0) }
        var displayedStyle by remember { mutableStateOf(style) }
        var layoutError by remember { mutableStateOf<String?>(null) }
        var layoutAttempt by remember { mutableIntStateOf(0) }
        LaunchedEffect(key, body, style, widthPx, heightPx, layoutAttempt) {
            layoutError = null
            onLayoutStarted()
            if (pages == null) {
                // Let the initial loading surface render before the first pagination pass.
                withFrameNanos { }
            }
            try {
                val measuredPages = withContext(Dispatchers.Default) {
                    paginateText(body, paginationSpec, widthPx, heightPx)
                }
                displayedBody = body
                displayedKey = key
                displayedInitialOffset = initialOffset
                displayedStyle = style
                pages = measuredPages
                onLayoutReady()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                layoutError = "正文分页失败，请重试"
                onLayoutReady()
            }
        }
        layoutError?.let { message ->
            ReaderError(
                message = message,
                retry = { layoutAttempt += 1 },
                modifier = Modifier.align(Alignment.Center),
                color = colors.foreground,
            )
            return@BoxWithConstraints
        }
        val visiblePages = pages
        if (visiblePages == null) {
            ReaderLoadingScreen(
                message = "正在加载中",
                detail = "正在排版正文",
                modifier = Modifier.fillMaxSize(),
            )
            return@BoxWithConstraints
        }
        val visibleBody = displayedBody
        val visibleKey = requireNotNull(displayedKey)
        val visibleHighlight = narrationHighlight.takeIf { visibleKey == key }
        key(visibleKey, visiblePages) {
            val initialPage = remember(visibleKey, displayedInitialOffset) {
                visiblePages.indexOfLast { it.start <= displayedInitialOffset }.coerceAtLeast(0)
            }
            val pager = rememberPagerState(initialPage = initialPage, pageCount = { visiblePages.size })
            val scope = rememberCoroutineScope()
            LaunchedEffect(pager, visiblePages, visibleHighlight != null) {
                snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
                    visiblePages.getOrNull(page)?.let { visiblePage ->
                        onVisibleStartChanged(visiblePage.start)
                        if (visibleHighlight == null) onPositionChanged(visiblePage.start)
                    }
                }
            }
            val followOffset = visibleHighlight?.start ?: displayedInitialOffset
            LaunchedEffect(followOffset, visiblePages, visibleHighlight != null) {
                val target = visiblePages.indexOfLast { it.start <= followOffset }.coerceAtLeast(0)
                if (pager.currentPage != target) {
                    if (visibleHighlight != null) pager.animateScrollToPage(target)
                    else pager.scrollToPage(target)
                }
            }
            HorizontalPager(
                state = pager,
                beyondViewportPageCount = 2,
                key = { page -> visiblePages[page].start },
                modifier = Modifier.fillMaxSize().pointerInput(visibleKey, controlsVisible) {
                    detectTapGestures { point ->
                        when {
                            point.x < size.width * 0.25f -> {
                                if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
                                else if (hasPreviousChapter) onPreviousChapter()
                            }
                            point.x > size.width * 0.75f -> {
                                if (pager.currentPage < visiblePages.lastIndex) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                                else if (hasNextChapter) onNextChapter()
                            }
                            else -> onToggleControls()
                        }
                    }
                },
            ) { page ->
                val range = visiblePages[page]
                val pageText = remember(visibleBody, range) { visibleBody.substring(range.start, range.end) }
                val displayedText = remember(pageText, range.start, visibleHighlight, narrationColor) {
                    highlightedText(
                        text = pageText,
                        baseOffset = range.start,
                        highlight = visibleHighlight,
                        color = narrationColor,
                    )
                }
                Text(displayedText, style = displayedStyle, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun ScrollingChapter(
    key: String,
    body: String,
    initialOffset: Int,
    preferences: ReaderPreferences,
    colors: ReaderPalette,
    narrationHighlight: NarrationHighlight?,
    hasNext: Boolean,
    onPositionChanged: (Int) -> Unit,
    onVisibleStartChanged: (Int) -> Unit,
    onNextChapter: () -> Unit,
) {
    val scroll = rememberScrollState()
    val style = readerTextStyle(preferences, colors.foreground)
    val narrationColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val density = LocalDensity.current
    val topPaddingPx = with(density) { 16.dp.toPx() }
    var initialized by remember(key) { mutableStateOf(false) }
    var previousScroll by remember(key) { mutableFloatStateOf(0f) }
    var textLayout by remember(key, body, style) { mutableStateOf<TextLayoutResult?>(null) }
    var viewportHeight by remember(key) { mutableIntStateOf(0) }
    var autoFollowing by remember(key) { mutableStateOf(false) }
    val displayedText = remember(body, narrationHighlight, narrationColor) {
        highlightedText(
            text = body,
            baseOffset = 0,
            highlight = narrationHighlight,
            color = narrationColor,
        )
    }
    LaunchedEffect(key, textLayout, viewportHeight, scroll.maxValue) {
        if (!initialized && textLayout != null && viewportHeight > 0) {
            val target = textScrollTarget(
                layout = requireNotNull(textLayout),
                offset = initialOffset,
                textLength = body.length,
                topPaddingPx = topPaddingPx,
                viewportHeight = viewportHeight,
                maxScroll = scroll.maxValue,
            )
            scroll.scrollTo(target)
            initialized = true
        }
    }
    LaunchedEffect(
        narrationHighlight?.start,
        textLayout,
        viewportHeight,
        scroll.maxValue,
        initialized,
    ) {
        val highlight = narrationHighlight ?: return@LaunchedEffect
        val layout = textLayout ?: return@LaunchedEffect
        if (!initialized || viewportHeight <= 0) return@LaunchedEffect
        val target = textScrollTarget(
            layout = layout,
            offset = highlight.start,
            textLength = body.length,
            topPaddingPx = topPaddingPx,
            viewportHeight = viewportHeight,
            maxScroll = scroll.maxValue,
        )
        autoFollowing = true
        try {
            scroll.animateScrollTo(target)
        } finally {
            autoFollowing = false
        }
    }
    LaunchedEffect(key, scroll, textLayout, narrationHighlight != null) {
        snapshotFlow { scroll.value to scroll.maxValue }.collect { (value, max) ->
            val visibleOffset = textLayout?.let { layout ->
                visibleTextOffset(
                    layout = layout,
                    scrollY = value,
                    topPaddingPx = topPaddingPx,
                    textLength = body.length,
                )
            } ?: 0
            onVisibleStartChanged(visibleOffset)
            if (max > 0) {
                if (narrationHighlight == null && !autoFollowing) {
                    onPositionChanged(visibleOffset)
                }
                if (narrationHighlight == null && hasNext && initialized && previousScroll < max && value == max) {
                    delay(180)
                    onNextChapter()
                }
                previousScroll = value.toFloat()
            }
        }
    }
    Text(
        displayedText,
        style = style,
        onTextLayout = { textLayout = it },
        modifier = Modifier.fillMaxSize()
            // Ignoring visibility prevents hidden status bars from collapsing the safe top inset.
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .onSizeChanged { viewportHeight = it.height }
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 32.dp),
    )
}

@Composable
private fun ReaderSettingsSheet(
    preferences: ReaderPreferences,
    narrationPlaybackSpeed: Float,
    onMode: (ReaderViewMode) -> Unit,
    onFontSize: (Float) -> Unit,
    onFont: (ReaderFont) -> Unit,
    onBackground: (ReaderBackground) -> Unit,
    onLineHeight: (Float) -> Unit,
    onNarrationPlaybackSpeed: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("阅读设置", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderViewMode.entries.forEach { mode ->
                    FilterChip(
                        selected = preferences.viewMode == mode,
                        onClick = { onMode(mode) },
                        label = { Text(if (mode == ReaderViewMode.PAGED) "左右翻页" else "上下滑动") },
                    )
                }
            }
            Column {
                Text("字体大小  ${preferences.fontSizeSp.roundToInt()}sp", fontWeight = FontWeight.Medium)
                Slider(value = preferences.fontSizeSp, onValueChange = onFontSize, valueRange = 14f..30f, steps = 15)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ReaderFont.entries) { font ->
                    FilterChip(selected = preferences.font == font, onClick = { onFont(font) }, label = { Text(font.label()) })
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ReaderBackground.entries) { background ->
                    FilterChip(
                        selected = preferences.background == background,
                        onClick = { onBackground(background) },
                        label = { Text(background.label()) },
                    )
                }
            }
            Column {
                Text("行距", fontWeight = FontWeight.Medium)
                Slider(value = preferences.lineHeightMultiplier, onValueChange = onLineHeight, valueRange = 1.55f..1.8f, steps = 4)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("语音朗读", style = MaterialTheme.typography.titleMedium)
                NarrationSpeedControls(
                    playbackSpeed = narrationPlaybackSpeed,
                    onPlaybackSpeedChange = onNarrationPlaybackSpeed,
                )
            }
        }
    }
}

@Composable
fun ProgressConflictDialog(
    conflict: ProgressResolution,
    onLocal: () -> Unit,
    onRemote: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("发现不同的阅读进度") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProgressDescription("本机进度", conflict.local)
                ProgressDescription("服务器进度", conflict.remote)
            }
        },
        confirmButton = { Button(onClick = onLocal) { Text("使用本机进度") } },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel) { Text("取消") }
                TextButton(onClick = onRemote) { Text("使用服务器进度") }
            }
        },
    )
}

@Composable
private fun ProgressDescription(label: String, progress: ReadingProgress?) {
    val value = progress ?: return
    val position = if (value.bookFormat == "pdf") "第 ${value.pdfPage + 1} 页" else "${value.chapterTitle ?: "第 ${value.chapterIndex + 1} 章"} · ${(value.chapterProgress * 100).roundToInt()}%"
    Text("$label：\n$position\n${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value.updatedAtEpochMs))}")
}

internal data class ReaderPalette(val background: Color, val foreground: Color)

internal data class NarrationHighlight(val start: Int, val end: Int)

internal data class VisibleTextPosition(val chapterIndex: Int, val charOffset: Int)

internal fun highlightedText(
    text: String,
    baseOffset: Int,
    highlight: NarrationHighlight?,
    color: Color,
): AnnotatedString {
    if (highlight == null || text.isEmpty()) return AnnotatedString(text)
    val localStart = (highlight.start - baseOffset).coerceIn(0, text.length)
    val localEnd = (highlight.end - baseOffset).coerceIn(0, text.length)
    if (localEnd <= localStart) return AnnotatedString(text)
    return AnnotatedString.Builder(text).apply {
        addStyle(SpanStyle(background = color), localStart, localEnd)
    }.toAnnotatedString()
}

private fun textScrollTarget(
    layout: TextLayoutResult,
    offset: Int,
    textLength: Int,
    topPaddingPx: Float,
    viewportHeight: Int,
    maxScroll: Int,
): Int {
    val availableLength = minOf(textLength, layout.layoutInput.text.length)
    if (availableLength <= 0 || maxScroll <= 0) return 0
    val safeOffset = offset.coerceIn(0, availableLength - 1)
    val lineTop = layout.getBoundingBox(safeOffset).top
    return (lineTop + topPaddingPx - viewportHeight * 0.3f)
        .roundToInt()
        .coerceIn(0, maxScroll)
}

internal fun visibleTextOffset(
    layout: TextLayoutResult,
    scrollY: Int,
    topPaddingPx: Float,
    textLength: Int,
): Int {
    val availableLength = minOf(textLength, layout.layoutInput.text.length)
    if (availableLength <= 0 || layout.lineCount <= 0) return 0
    val textY = (scrollY - topPaddingPx).coerceAtLeast(0f)
    return layout.getOffsetForPosition(Offset(x = 0f, y = textY))
        .coerceIn(0, availableLength - 1)
}

private fun readerPalette(background: ReaderBackground, appDark: Boolean): ReaderPalette = when (background) {
    ReaderBackground.AUTO -> if (appDark) ReaderPalette(Color(0xFF111315), Color(0xFFECEDEA))
    else ReaderPalette(Color(0xFFFAF8F2), Color(0xFF292B2D))
    ReaderBackground.PAPER -> ReaderPalette(Color(0xFFFAF8F2), Color(0xFF292B2D))
    ReaderBackground.WARM -> ReaderPalette(Color(0xFFF4EEDF), Color(0xFF302D28))
    ReaderBackground.WHITE -> ReaderPalette(Color.White, Color(0xFF202225))
    ReaderBackground.GRAY -> ReaderPalette(Color(0xFFECEDEB), Color(0xFF252729))
    ReaderBackground.GREEN -> ReaderPalette(Color(0xFFE7EEE3), Color(0xFF253026))
    ReaderBackground.DARK_GRAY -> ReaderPalette(Color(0xFF202326), Color(0xFFE5E7E3))
    ReaderBackground.BLACK -> ReaderPalette(Color(0xFF111315), Color(0xFFECEDEA))
}

internal fun readerTextStyle(preferences: ReaderPreferences, color: Color) = TextStyle(
    color = color,
    fontSize = preferences.fontSizeSp.sp,
    lineHeight = (preferences.fontSizeSp * preferences.lineHeightMultiplier).sp,
    fontFamily = when (preferences.font) {
        ReaderFont.SANS, ReaderFont.HEI -> FontFamily.SansSerif
        ReaderFont.SERIF, ReaderFont.SONG -> FontFamily.Serif
    },
)

private fun ReaderFont.label() = when (this) {
    ReaderFont.SANS -> "无衬线"
    ReaderFont.SERIF -> "衬线"
    ReaderFont.SONG -> "宋体"
    ReaderFont.HEI -> "黑体"
}

private fun ReaderBackground.label() = when (this) {
    ReaderBackground.AUTO -> "跟随明暗"
    ReaderBackground.PAPER -> "纸张白"
    ReaderBackground.WARM -> "暖白"
    ReaderBackground.WHITE -> "纯白"
    ReaderBackground.GRAY -> "浅灰"
    ReaderBackground.GREEN -> "护眼绿"
    ReaderBackground.DARK_GRAY -> "深灰"
    ReaderBackground.BLACK -> "近黑"
}

@Composable
internal fun ReaderError(message: String, retry: () -> Unit, modifier: Modifier, color: Color) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, color = color, textAlign = TextAlign.Center)
        Button(onClick = retry) { Text("重试") }
    }
}

@Composable
fun PersistOnBackground(onPersist: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) onPersist() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

@Composable
fun ImmersiveSystemBars(enabled: Boolean, showBars: Boolean) {
    val view = LocalView.current
    val context = LocalContext.current
    val controller = remember(context, view) {
        context.findActivity()?.window?.let { WindowCompat.getInsetsController(it, view) }
    }
    DisposableEffect(enabled, controller) {
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(enabled, showBars, controller) {
        if (!enabled || controller == null) return@LaunchedEffect
        if (showBars) controller.show(WindowInsetsCompat.Type.systemBars())
        else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
