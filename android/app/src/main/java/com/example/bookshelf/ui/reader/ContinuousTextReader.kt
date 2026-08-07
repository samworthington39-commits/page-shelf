@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.example.bookshelf.ui.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bookshelf.domain.ReaderFont
import com.example.bookshelf.domain.ReaderPreferences
import com.example.bookshelf.domain.TextChapter
import com.example.bookshelf.domain.contiguousLoadedChapterIndices
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class ReaderPage(
    val chapterIndex: Int,
    val chapter: TextChapter,
    val displayText: String,
    val bodyStart: Int,
    val range: TextPage,
) {
    val key: String = "${chapter.id}:${range.start}"
    val bodyOffset: Int = chapterBodyOffset(range.start, bodyStart, chapter.body.length)
}

/**
 * Immersive-read HUD shown while the system bars are hidden: current chapter title at the
 * top-start and the current time at the top-end. The chapter label animates so the outgoing
 * title slides away horizontally (left when moving to a later chapter, right when going back)
 * and the incoming one arrives from the opposite side.
 */
@Composable
private fun ReaderImmersiveOverlay(
    visible: Boolean,
    chapterTitle: String,
    chapterIndex: Int,
    foreground: Color,
    background: Color,
    modifier: Modifier = Modifier,
    applyWindowInsets: Boolean = true,
) {
    if (!visible) return
    var nowText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            nowText = formatter.format(Date())
            delay(30_000)
        }
    }
    val insetsModifier = if (applyWindowInsets) {
        Modifier
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .windowInsetsPadding(WindowInsets.displayCutout)
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(insetsModifier)
            .background(background)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        AnimatedContent(
            modifier = Modifier.weight(1f, fill = false),
            targetState = chapterIndex to chapterTitle,
            transitionSpec = {
                val goingForward = targetState.first > initialState.first
                val exit = if (goingForward) {
                    fadeOut() + slideOutHorizontally { -it / 2 }
                } else {
                    fadeOut() + slideOutHorizontally { it / 2 }
                }
                val enter = if (goingForward) {
                    fadeIn() + slideInHorizontally { it / 2 }
                } else {
                    fadeIn() + slideInHorizontally { -it / 2 }
                }
                (enter togetherWith exit)
            },
            label = "immersiveChapterTitle",
        ) { (_, title) ->
            Text(
                text = title,
                modifier = Modifier.testTag("immersiveHudChapterTitle"),
                color = foreground.copy(alpha = 0.8f),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = nowText.ifBlank { "00:00" },
            color = foreground.copy(alpha = 0.8f),
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 12.dp).testTag("immersiveHudTime"),
        )
    }
}

@Composable
internal fun ContinuousPagedChapters(
    chapters: Map<Int, TextChapter>,
    currentChapterIndex: Int,
    currentOffset: Int,
    positionRevision: Int,
    chapterCount: Int,
    preferences: ReaderPreferences,
    colors: ReaderPalette,
    narrationHighlight: NarrationHighlight?,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onPositionChanged: (Int, Int) -> Unit,
    onVisiblePositionChanged: (Int, Int) -> Unit,
    onEnsureChapter: (Int) -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 24.dp),
    ) {
        val density = LocalDensity.current
        val style = readerTextStyle(preferences, colors.foreground)
        val narrationColor = Color(0xFF6E9B72).copy(alpha = 0.28f)
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
        val indices = remember(chapters, currentChapterIndex) {
            contiguousLoadedChapterIndices(chapters.keys, currentChapterIndex)
        }
        val layoutChapters = remember(chapters, indices) { indices.mapNotNull { index -> chapters[index]?.let { index to it } } }
        var pages by remember { mutableStateOf<List<ReaderPage>>(emptyList()) }
        var displayedStyle by remember { mutableStateOf(style) }
        var layoutError by remember { mutableStateOf<String?>(null) }
        var layoutAttempt by remember { mutableIntStateOf(0) }

        LaunchedEffect(layoutChapters, paginationSpec, widthPx, heightPx, layoutAttempt) {
            layoutError = null
            if (pages.isEmpty()) withFrameNanos { }
            try {
                val measured = withContext(Dispatchers.Default) {
                    layoutChapters.flatMap { (chapterIndex, chapter) ->
                        val display = chapterDisplayText(chapter.title, chapter.body, "第 ${chapterIndex + 1} 章")
                        paginateText(display.text, paginationSpec, widthPx, heightPx).map { range ->
                            ReaderPage(chapterIndex, chapter, display.text, display.bodyStart, range)
                        }
                    }
                }
                pages = measured
                displayedStyle = style
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                layoutError = "正文分页失败，请重试"
            }
        }

        layoutError?.let { message ->
            ReaderError(message, { layoutAttempt += 1 }, Modifier.fillMaxSize(), colors.foreground)
            return@BoxWithConstraints
        }
        if (pages.isEmpty()) {
            ReaderLoadingScreen("正在加载中", "正在排版正文", Modifier.fillMaxSize())
            return@BoxWithConstraints
        }

        val initialPage = remember(pages) {
            findReaderPage(pages, currentChapterIndex, currentOffset).coerceAtLeast(0)
        }
        val pager = rememberPagerState(initialPage = initialPage, pageCount = { pages.size })
        val scope = rememberCoroutineScope()
        var pendingForwardPageKey by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(pager, pages, positionRevision, narrationHighlight != null) {
            // Page numbers are relative to the loaded chapter window. Re-anchor before observing
            // the pager whenever that window is repaginated, otherwise the old page number can
            // point at a different chapter after the leading edge of the window moves.
            val target = findReaderPage(pages, currentChapterIndex, currentOffset)
            if (target >= 0) {
                pager.scrollToPage(target)
            }
            snapshotFlow { pager.settledPage to pager.isScrollInProgress }
                .distinctUntilChanged()
                .collect { (pageIndex, scrolling) ->
                    if (scrolling) return@collect
                    val page = pages.getOrNull(pageIndex) ?: return@collect
                    onVisiblePositionChanged(page.chapterIndex, page.bodyOffset)
                    if (narrationHighlight == null) onPositionChanged(page.chapterIndex, page.bodyOffset)
                    if (pageIndex >= pages.lastIndex - 1 && page.chapterIndex < chapterCount - 1) {
                        onEnsureChapter(page.chapterIndex + 1)
                    }
                }
        }
        LaunchedEffect(narrationHighlight?.start, currentChapterIndex, pages) {
            val highlight = narrationHighlight ?: return@LaunchedEffect
            val target = findReaderPage(pages, currentChapterIndex, highlight.start)
            if (target >= 0 && pager.currentPage != target) pager.animateScrollToPage(target)
        }
        LaunchedEffect(pages, pendingForwardPageKey) {
            val anchorKey = pendingForwardPageKey ?: return@LaunchedEffect
            val anchor = pages.indexOfFirst { it.key == anchorKey }
            when {
                anchor in 0 until pages.lastIndex -> {
                    // The requested chapter has arrived. Complete the tap that originally hit
                    // the edge instead of making the reader tap a second time.
                    pager.animateScrollToPage(anchor + 1)
                    pendingForwardPageKey = null
                }
                anchor < 0 -> pendingForwardPageKey = null
            }
        }

        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 2,
            key = { page -> pages[page].key },
            modifier = Modifier.fillMaxSize().pointerInput(controlsVisible, pages) {
                detectTapGestures { point ->
                    val settled = pager.settledPage
                    when {
                        point.x < size.width * 0.22f -> {
                            if (settled > 0) scope.launch { pager.animateScrollToPage(settled - 1) }
                            else pages.firstOrNull()?.chapterIndex?.minus(1)?.let(onEnsureChapter)
                        }
                        point.x > size.width * 0.78f -> {
                            if (settled < pages.lastIndex) scope.launch { pager.animateScrollToPage(settled + 1) }
                            else pages.lastOrNull()?.let { page ->
                                page.chapterIndex.plus(1).takeIf { it < chapterCount }?.let { nextChapter ->
                                    pendingForwardPageKey = page.key
                                    onEnsureChapter(nextChapter)
                                }
                            }
                        }
                        else -> onToggleControls()
                    }
                }
            },
        ) { pageIndex ->
            val page = pages[pageIndex]
            val pageText = remember(page) { page.displayText.substring(page.range.start, page.range.end) }
            val highlight = narrationHighlight.takeIf { page.chapterIndex == currentChapterIndex }
            val annotated = remember(pageText, page, highlight, narrationColor, displayedStyle.fontSize) {
                readerPageText(pageText, page, highlight, narrationColor, (displayedStyle.fontSize.value + 4f).sp)
            }
            Text(annotated, style = displayedStyle, modifier = Modifier.fillMaxSize())
        }

        val currentPage = pages.getOrNull(pager.currentPage)
        val currentPageChapterIndex = currentPage?.chapterIndex ?: currentChapterIndex
        val currentPageChapterTitle = remember(pages, currentPageChapterIndex) {
            val chapter = pages.firstOrNull { it.chapterIndex == currentPageChapterIndex }?.chapter
            val title = chapter?.title?.takeIf { it.isNotBlank() } ?: "第 ${currentPageChapterIndex + 1} 章"
            title
        }
        // The paged reader's BoxWithConstraints already applies the system-bar insets.
        ReaderImmersiveOverlay(
            visible = !controlsVisible,
            chapterTitle = currentPageChapterTitle,
            chapterIndex = currentPageChapterIndex,
            foreground = colors.foreground,
            background = colors.background,
            applyWindowInsets = false,
        )
    }
}

private fun findReaderPage(pages: List<ReaderPage>, chapterIndex: Int, offset: Int): Int {
    val candidates = pages.indices.filter { pages[it].chapterIndex == chapterIndex }
    if (candidates.isEmpty()) return -1
    if (offset <= 0) return candidates.first()
    return candidates.lastOrNull { pages[it].bodyOffset <= offset } ?: candidates.first()
}

private fun readerPageText(
    pageText: String,
    page: ReaderPage,
    highlight: NarrationHighlight?,
    narrationColor: Color,
    headingFontSize: TextUnit,
): AnnotatedString = AnnotatedString.Builder(pageText).apply {
    val headingEnd = minOf(page.range.end, page.bodyStart)
    if (headingEnd > page.range.start) {
        addStyle(
            SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = headingFontSize),
            0,
            headingEnd - page.range.start,
        )
    }
    if (highlight != null) {
        val displayStart = page.bodyStart + highlight.start
        val displayEnd = page.bodyStart + highlight.end
        val localStart = (displayStart - page.range.start).coerceIn(0, pageText.length)
        val localEnd = (displayEnd - page.range.start).coerceIn(0, pageText.length)
        if (localEnd > localStart) addStyle(SpanStyle(background = narrationColor), localStart, localEnd)
    }
}.toAnnotatedString()

private data class ChapterMeasurement(
    val layout: TextLayoutResult,
    val bodyTopPx: Int,
)

private data class VisibleChapter(
    val chapterIndex: Int,
    val charOffset: Int,
)

@Composable
internal fun ContinuousScrollingChapters(
    chapters: Map<Int, TextChapter>,
    currentChapterIndex: Int,
    currentOffset: Int,
    positionRevision: Int,
    chapterCount: Int,
    preferences: ReaderPreferences,
    colors: ReaderPalette,
    narrationHighlight: NarrationHighlight?,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onPositionChanged: (Int, Int) -> Unit,
    onVisiblePositionChanged: (Int, Int) -> Unit,
    onEnsureChapter: (Int) -> Unit,
) {
    val indices = remember(chapters, currentChapterIndex) {
        contiguousLoadedChapterIndices(chapters.keys, currentChapterIndex)
    }
    val entries = remember(chapters, indices) { indices.mapNotNull { index -> chapters[index]?.let { index to it } } }
    val initialIndex = entries.indexOfFirst { it.first == currentChapterIndex }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val scope = rememberCoroutineScope()
    val measurements = remember { mutableStateMapOf<String, ChapterMeasurement>() }
    val style = readerTextStyle(preferences, colors.foreground)
    val narrationColor = Color(0xFF6E9B72).copy(alpha = 0.28f)
    var viewportHeight by remember { mutableIntStateOf(0) }
    var handledRevision by remember { mutableIntStateOf(positionRevision - 1) }
    val currentChapter = chapters[currentChapterIndex]
    val currentMeasurement = currentChapter?.let { measurements[it.id] }
    val density = LocalDensity.current
    val collapseStartPx = with(density) { 16.dp.toPx() }
    val collapseRangePx = with(density) { 60.dp.toPx() }
    val collapseLiftPx = with(density) { 18.dp.toPx() }
    val collapseShiftPx = with(density) { 10.dp.toPx() }
    val collapseState = remember { mutableFloatStateOf(0f) }
    var collapseTargetId by remember { mutableStateOf<String?>(null) }
    var pinnedChapterIndex by remember { mutableIntStateOf(currentChapterIndex) }

    // As a chapter heading scrolls up past the top of the viewport it shrinks and pins to the
    // top-start HUD corner; the pinned title tracks the chapter whose heading currently owns
    // the top of the viewport (the first visible chapter item, ignoring padding spacers).
    LaunchedEffect(listState, entries) {
        snapshotFlow {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                entries.any { it.second.id == item.key }
            }
            val entry = first?.key?.let { key -> entries.firstOrNull { it.second.id == key } }
            val headingTop = first?.offset?.toFloat() ?: collapseStartPx
            val progress = ((collapseStartPx - headingTop) / collapseRangePx).coerceIn(0f, 1f)
            Triple(progress, entry?.second?.id, entry?.first)
        }.distinctUntilChanged().collect { (progress, targetId, index) ->
            collapseState.floatValue = progress
            collapseTargetId = targetId
            index?.let { pinnedChapterIndex = it }
        }
    }

    LaunchedEffect(positionRevision, entries, currentMeasurement, viewportHeight) {
        if (handledRevision == positionRevision || entries.isEmpty()) return@LaunchedEffect
        val itemIndex = entries.indexOfFirst { it.first == currentChapterIndex }
        if (itemIndex < 0) return@LaunchedEffect
        if (currentMeasurement == null || viewportHeight <= 0) {
            listState.scrollToItem(itemIndex)
        } else {
            val offset = scrollOffsetFor(currentMeasurement, currentOffset, currentChapter.body.length, viewportHeight)
            listState.scrollToItem(itemIndex, offset)
            handledRevision = positionRevision
        }
    }

    LaunchedEffect(
        narrationHighlight?.start,
        currentChapterIndex,
        currentMeasurement,
        viewportHeight,
    ) {
        val highlight = narrationHighlight ?: return@LaunchedEffect
        val chapter = currentChapter ?: return@LaunchedEffect
        val measurement = currentMeasurement ?: return@LaunchedEffect
        val itemIndex = entries.indexOfFirst { it.first == currentChapterIndex }
        if (itemIndex < 0 || viewportHeight <= 0) return@LaunchedEffect
        val offset = scrollOffsetFor(measurement, highlight.start, chapter.body.length, viewportHeight)
        listState.animateScrollToItem(itemIndex, offset)
    }

    LaunchedEffect(listState, entries, measurements, viewportHeight, narrationHighlight != null) {
        snapshotFlow {
            val info = listState.layoutInfo
            val probe = (info.viewportEndOffset * 0.2f).roundToInt()
            val item = info.visibleItemsInfo.lastOrNull { it.offset <= probe }
                ?: info.visibleItemsInfo.firstOrNull()
            val entry = item?.key?.let { key -> entries.firstOrNull { it.second.id == key } }
            val measurement = entry?.second?.let { measurements[it.id] }
            if (item == null || entry == null || measurement == null) null else {
                val y = (probe - item.offset - measurement.bodyTopPx).coerceAtLeast(0)
                val layout = measurement.layout
                val bodyLength = entry.second.body.length
                val charOffset = if (bodyLength <= 0 || layout.lineCount <= 0) 0 else {
                    layout.getOffsetForPosition(Offset(0f, y.toFloat())).coerceIn(0, bodyLength)
                }
                VisibleChapter(entry.first, charOffset)
            }
        }
            .distinctUntilChanged()
            .collect { visible ->
                visible ?: return@collect
                onVisiblePositionChanged(visible.chapterIndex, visible.charOffset)
                if (narrationHighlight == null) onPositionChanged(visible.chapterIndex, visible.charOffset)
            }
    }

    LaunchedEffect(listState, entries, chapterCount) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            visible.firstOrNull()?.key to visible.lastOrNull()?.key
        }.distinctUntilChanged().collect { (firstKey, lastKey) ->
            if (entries.isEmpty()) return@collect
            val first = entries.indexOfFirst { it.second.id == firstKey }
            val last = entries.indexOfFirst { it.second.id == lastKey }
            if (first in 0..1 && entries.first().first > 0) onEnsureChapter(entries.first().first - 1)
            if (last >= entries.lastIndex - 1 && entries.last().first < chapterCount - 1) {
                onEnsureChapter(entries.last().first + 1)
            }
        }
    }

    val pinnedTitle = remember(entries, pinnedChapterIndex) {
        val entry = entries.firstOrNull { it.first == pinnedChapterIndex } ?: entries.firstOrNull()
        entry?.let { (index, chapter) ->
            chapter.title.takeIf { it.isNotBlank() } ?: "第 ${index + 1} 章"
        }.orEmpty()
    }

    Box(
        Modifier.fillMaxSize().pointerInput(listState, viewportHeight) {
            detectTapGestures { point ->
                when (scrollReaderTapAction(point.y, size.height)) {
                    ScrollReaderTapAction.PAGE_UP -> scope.launch {
                        listState.animateScrollBy(-scrollPageDistance(viewportHeight))
                    }
                    ScrollReaderTapAction.TOGGLE_CONTROLS -> onToggleControls()
                    ScrollReaderTapAction.PAGE_DOWN -> scope.launch {
                        listState.animateScrollBy(scrollPageDistance(viewportHeight))
                    }
                }
            }
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .onSizeChanged { viewportHeight = it.height },
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            itemsIndexed(entries, key = { _, entry -> entry.second.id }) { _, (chapterIndex, chapter) ->
                ChapterStreamItem(
                    chapter = chapter,
                    chapterIndex = chapterIndex,
                    style = style,
                    highlight = narrationHighlight.takeIf { chapterIndex == currentChapterIndex },
                    narrationColor = narrationColor,
                    collapseState = collapseState,
                    collapseTargetId = collapseTargetId,
                    collapseLiftPx = collapseLiftPx,
                    collapseShiftPx = collapseShiftPx,
                    onMeasurement = { measurements[chapter.id] = it },
                    onDispose = { measurements.remove(chapter.id) },
                )
            }
        }
        ReaderImmersiveOverlay(
            visible = !controlsVisible,
            chapterTitle = pinnedTitle,
            chapterIndex = pinnedChapterIndex,
            foreground = colors.foreground,
            background = colors.background,
        )
    }
}

internal enum class ScrollReaderTapAction {
    PAGE_UP,
    TOGGLE_CONTROLS,
    PAGE_DOWN,
}

internal fun scrollReaderTapAction(y: Float, height: Int): ScrollReaderTapAction {
    if (height <= 0) return ScrollReaderTapAction.TOGGLE_CONTROLS
    return when {
        y < height / 3f -> ScrollReaderTapAction.PAGE_UP
        y > height * 2f / 3f -> ScrollReaderTapAction.PAGE_DOWN
        else -> ScrollReaderTapAction.TOGGLE_CONTROLS
    }
}

internal fun scrollPageDistance(viewportHeight: Int): Float =
    viewportHeight.coerceAtLeast(0) * SCROLL_PAGE_DISTANCE_FRACTION

private const val SCROLL_PAGE_DISTANCE_FRACTION = 0.88f

@Composable
private fun ChapterStreamItem(
    chapter: TextChapter,
    chapterIndex: Int,
    style: TextStyle,
    highlight: NarrationHighlight?,
    narrationColor: Color,
    collapseState: State<Float>,
    collapseTargetId: String?,
    collapseLiftPx: Float,
    collapseShiftPx: Float,
    onMeasurement: (ChapterMeasurement) -> Unit,
    onDispose: () -> Unit,
) {
    var layout by remember(chapter.id, style) { mutableStateOf<TextLayoutResult?>(null) }
    var bodyTopPx by remember(chapter.id, style) { mutableIntStateOf(-1) }
    val displayedText = remember(chapter.body, highlight, narrationColor) {
        highlightedText(chapter.body, 0, highlight, narrationColor)
    }
    LaunchedEffect(layout, bodyTopPx) {
        layout?.takeIf { bodyTopPx >= 0 }?.let { onMeasurement(ChapterMeasurement(it, bodyTopPx)) }
    }
    DisposableEffect(chapter.id) { onDispose { onDispose() } }

    val isCollapseTarget = remember(chapter.id, collapseTargetId) { chapter.id == collapseTargetId }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(
            text = chapter.title.ifBlank { "第 ${chapterIndex + 1} 章" },
            style = style.copy(
                fontSize = (style.fontSize.value + 4f).sp,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                if (isCollapseTarget) {
                    // Scrolls up: shrink toward the top-start corner. Scrolls down: grow back
                    // to the in-body position (从哪来回哪去). Fades out only once fully pinned,
                    // where the HUD label takes over.
                    val p = collapseState.value
                    val scale = 1f - 0.38f * p
                    scaleX = scale
                    scaleY = scale
                    translationY = -p * collapseLiftPx
                    translationX = -p * collapseShiftPx
                    alpha = 1f - 0.85f * p
                }
            },
        )
        Text(
            text = displayedText,
            style = style,
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth().onGloballyPositioned {
                bodyTopPx = it.positionInParent().y.roundToInt()
            },
        )
    }
}

private fun scrollOffsetFor(
    measurement: ChapterMeasurement,
    charOffset: Int,
    bodyLength: Int,
    viewportHeight: Int,
): Int {
    if (bodyLength <= 0 || measurement.layout.lineCount <= 0) return 0
    val safeOffset = charOffset.coerceIn(0, bodyLength - 1)
    val lineTop = measurement.layout.getBoundingBox(safeOffset).top.roundToInt()
    return (measurement.bodyTopPx + lineTop - viewportHeight * 0.2f).roundToInt().coerceAtLeast(0)
}
