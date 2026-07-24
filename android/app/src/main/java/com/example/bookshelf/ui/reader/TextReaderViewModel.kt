package com.example.bookshelf.ui.reader

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bookshelf.AppContainer
import com.example.bookshelf.data.repository.TextRestore
import com.example.bookshelf.domain.Book
import com.example.bookshelf.domain.AppThemeMode
import com.example.bookshelf.domain.DownloadState
import com.example.bookshelf.domain.ProgressResolution
import com.example.bookshelf.domain.ReaderBackground
import com.example.bookshelf.domain.ReaderFont
import com.example.bookshelf.domain.ReaderPreferences
import com.example.bookshelf.domain.ReaderViewMode
import com.example.bookshelf.domain.TextChapter
import com.example.bookshelf.domain.TextChapterSummary
import com.example.bookshelf.domain.TextReadingPosition
import com.example.bookshelf.domain.calculateTextProgression
import com.example.bookshelf.domain.chapterWindowRange
import com.example.bookshelf.narration.NarrationRequest
import com.example.bookshelf.narration.NarrationState
import com.example.bookshelf.narration.NarrationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class TextReaderUiState(
    val loading: Boolean = true,
    val chapterLoading: Boolean = false,
    val layoutLoading: Boolean = false,
    val preloadCompleted: Int = 0,
    val preloadTotal: Int = 0,
    val book: Book? = null,
    val toc: List<TextChapterSummary> = emptyList(),
    val chapter: TextChapter? = null,
    val chapters: Map<Int, TextChapter> = emptyMap(),
    val chapterIndex: Int = 0,
    val charOffset: Int = 0,
    val chapterProgress: Double = 0.0,
    val progression: Double = 0.0,
    val preferences: ReaderPreferences = ReaderPreferences(),
    val download: DownloadState? = null,
    val conflict: ProgressResolution? = null,
    val positionRevision: Int = 0,
    val narration: NarrationState = NarrationState(),
    val error: String? = null,
)

class TextReaderViewModel(
    private val container: AppContainer,
    private val bookId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(TextReaderUiState(preferences = container.settings.state.value))
    val state: StateFlow<TextReaderUiState> = _state.asStateFlow()
    private var saveJob: Job? = null
    private var openingJob: Job? = null
    private var chapterJob: Job? = null
    private var prefetchJob: Job? = null
    private var windowRepairJob: Job? = null
    private var windowRepairIndex: Int? = null
    private var exitStarted = false

    init {
        viewModelScope.launch {
            container.settings.state.collect { prefs -> _state.value = _state.value.copy(preferences = prefs) }
        }
        viewModelScope.launch {
            container.narration.state.collect(::onNarrationChanged)
        }
        load()
    }

    fun selectChapter(index: Int, offset: Int = 0) {
        val current = _state.value
        if (
            current.loading || current.chapterLoading || current.layoutLoading ||
            index !in current.toc.indices ||
            (index == current.chapterIndex && offset == current.charOffset)
        ) return
        Log.i(READER_LOG_TAG, "selectChapter from=${current.chapterIndex} to=$index cached=${current.chapters[index] != null}")
        chapterJob?.cancel()
        chapterJob = viewModelScope.launch(Dispatchers.IO) {
            persistCurrent()
            val cached = _state.value.chapters[index]
            if (cached != null) {
                showChapter(cached, index, offset, navigation = true)
                scheduleWindowPrefetch(index)
            } else {
                loadChapter(index, offset, navigation = true)
            }
        }
    }

    fun previousChapter() = selectChapter(_state.value.chapterIndex - 1)
    fun nextChapter() = selectChapter(_state.value.chapterIndex + 1)

    fun onPositionChanged(charOffset: Int) = onPositionChanged(_state.value.chapterIndex, charOffset)

    fun onPositionChanged(chapterIndex: Int, charOffset: Int) {
        val current = _state.value
        val chapter = current.chapters[chapterIndex] ?: current.chapter?.takeIf { current.chapterIndex == chapterIndex } ?: return
        val offset = charOffset.coerceIn(0, chapter.body.length)
        val within = if (chapter.body.isNotEmpty()) offset.toDouble() / chapter.body.length else 0.0
        val chapterChanged = chapterIndex != current.chapterIndex
        if (!chapterChanged && offset == current.charOffset) return
        _state.value = current.copy(
            chapter = chapter,
            chapterIndex = chapterIndex,
            charOffset = offset,
            chapterProgress = within,
            progression = calculateTextProgression(chapterIndex, current.toc.size, offset, chapter.body.length),
        )
        if (chapterChanged) scheduleWindowPrefetch(chapterIndex)
        scheduleSave()
    }

    fun ensureChapter(index: Int) {
        val current = _state.value
        if (index !in current.toc.indices || current.chapters[index] != null) return
        if (windowRepairJob?.isActive == true && windowRepairIndex == index) return
        val book = current.book ?: return
        val summary = current.toc[index]
        windowRepairJob?.cancel()
        windowRepairIndex = index
        Log.i(READER_LOG_TAG, "ensureChapter index=$index")
        windowRepairJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                withTimeout(CHAPTER_ENSURE_TIMEOUT_MILLIS) {
                    container.textReader.chapter(bookId, summary.id, book.fingerprint)
                }
            }
                .onSuccess { chapter ->
                    Log.i(READER_LOG_TAG, "ensureChapter ready index=$index")
                    mergeChapterWindow(mapOf(index to chapter))
                }
                .onFailure { error ->
                    if (error !is CancellationException) {
                        Log.w(READER_LOG_TAG, "ensureChapter failed index=$index type=${error.javaClass.simpleName}")
                    }
                }
            if (windowRepairIndex == index) {
                windowRepairIndex = null
                windowRepairJob = null
            }
        }
    }

    fun setViewMode(mode: ReaderViewMode) = container.settings.update { it.copy(viewMode = mode) }
    fun setFontSize(value: Float) = container.settings.update { it.copy(fontSizeSp = value) }
    fun setLineHeight(value: Float) = container.settings.update { it.copy(lineHeightMultiplier = value) }
    fun setFont(font: ReaderFont) = container.settings.update { it.copy(font = font) }
    fun setBackground(background: ReaderBackground) = container.settings.update { it.copy(background = background) }
    fun setTheme(mode: AppThemeMode) = container.settings.update { it.copy(themeMode = mode) }

    fun prepareNarrationPage(visibleChapterIndex: Int, visibleStartOffset: Int) {
        currentNarrationRequest(visibleChapterIndex, visibleStartOffset)?.let(container.narration::prepare)
    }

    fun startNarrationFromCurrentPage(visibleChapterIndex: Int, visibleStartOffset: Int) {
        currentNarrationRequest(visibleChapterIndex, visibleStartOffset)?.let(container.narration::start)
    }

    fun stopNarration() = container.narration.stop()

    fun setNarrationPlaybackSpeed(speed: Float) = container.narration.setPlaybackSpeed(speed)

    fun toggleNarrationPlayback() {
        when (_state.value.narration.status) {
            NarrationStatus.PLAYING, NarrationStatus.PREPARING -> container.narration.pause()
            NarrationStatus.PAUSED -> container.narration.resume()
            else -> Unit
        }
    }

    fun followNarration() {
        val current = _state.value
        val narration = current.narration
        if (!narration.isActive || narration.bookId != bookId) return
        val targetChapterId = narration.chapterId ?: return
        val targetIndex = current.toc.indexOfFirst { it.id == targetChapterId }
        if (targetIndex < 0) return
        if (current.chapter?.id == targetChapterId) {
            val offset = narration.charOffset.coerceIn(0, current.chapter.body.length)
            val within = if (current.chapter.body.isNotEmpty()) {
                offset.toDouble() / current.chapter.body.length
            } else {
                0.0
            }
            _state.value = current.copy(
                charOffset = offset,
                chapterProgress = within,
                progression = calculateTextProgression(
                    current.chapterIndex,
                    current.toc.size,
                    offset,
                    current.chapter.body.length,
                ),
                positionRevision = current.positionRevision + 1,
            )
            return
        }
        if (current.chapterLoading || current.layoutLoading) return
        chapterJob?.cancel()
        chapterJob = viewModelScope.launch(Dispatchers.IO) {
            loadChapter(targetIndex, narration.charOffset, navigation = true)
        }
    }

    fun saveOffline() {
        val book = _state.value.book ?: return
        viewModelScope.launch(Dispatchers.IO) { container.downloads.enqueue(book, permanent = true) }
    }

    fun pauseDownload() { viewModelScope.launch(Dispatchers.IO) { container.downloads.pause(bookId) } }

    fun resolveConflict(useLocal: Boolean) {
        val resolution = _state.value.conflict ?: return
        if (_state.value.loading || _state.value.chapterLoading || _state.value.layoutLoading) return
        viewModelScope.launch(Dispatchers.IO) {
            val selected = container.textReader.resolve(resolution, useLocal)
            _state.value = _state.value.copy(conflict = null)
            if (selected != null) {
                val index = _state.value.toc.indexOfFirst { it.id == selected.chapterId }
                    .takeIf { it >= 0 } ?: selected.chapterIndex
                loadChapter(index.coerceIn(_state.value.toc.indices), selected.charOffset, navigation = true)
            }
        }
    }

    fun retry() {
        if (_state.value.book == null) load() else {
            chapterJob?.cancel()
            chapterJob = viewModelScope.launch(Dispatchers.IO) {
                val current = _state.value
                if (current.chapter == null) loadOpeningWindow(current.chapterIndex, current.charOffset)
                else loadChapter(current.chapterIndex, current.charOffset, navigation = true)
            }
        }
    }

    fun exit(onSaved: () -> Unit) {
        if (exitStarted) return
        exitStarted = true
        viewModelScope.launch {
            saveJob?.cancel()
            withContext(Dispatchers.IO) { runCatching { persistCurrent() } }
            onSaved()
        }
    }

    fun persistNow() { viewModelScope.launch(Dispatchers.IO) { persistCurrent() } }

    fun onPageLayoutStarted(chapterId: String) {
        val current = _state.value
        if (current.chapter?.id == chapterId && !current.layoutLoading) {
            _state.value = current.copy(layoutLoading = true)
        }
    }

    fun onPageLayoutReady(chapterId: String) {
        val current = _state.value
        if (current.chapter?.id == chapterId && current.layoutLoading) {
            _state.value = current.copy(layoutLoading = false)
        }
    }

    private fun load() {
        openingJob?.cancel()
        chapterJob?.cancel()
        prefetchJob?.cancel()
        windowRepairJob?.cancel()
        windowRepairIndex = null
        openingJob = viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(
                loading = true,
                chapterLoading = false,
                layoutLoading = false,
                preloadCompleted = 0,
                preloadTotal = 0,
                chapter = null,
                chapters = emptyMap(),
                error = null,
            )
            val book = runCatching { container.books.book(bookId) }.getOrElse { error ->
                _state.value = _state.value.copy(loading = false, error = error.readerMessage("无法打开书籍")); return@launch
            }
            if (!book.capabilities.reflowableText) {
                _state.value = _state.value.copy(loading = false, book = book, error = "该格式不支持文字重排"); return@launch
            }
            val tocResult = async { runCatching { container.textReader.toc(bookId, book.fingerprint) } }
            val restoreResult = async {
                runCatching { container.textReader.restore(bookId, book.format, book.fingerprint) }
            }
            val toc = tocResult.await().getOrElse { error ->
                _state.value = _state.value.copy(loading = false, book = book, error = error.readerMessage("无法加载目录")); return@launch
            }
            if (toc.isEmpty()) {
                _state.value = _state.value.copy(loading = false, book = book, error = "书籍没有可阅读的正文"); return@launch
            }
            val restored: TextRestore = restoreResult.await().getOrElse {
                TextRestore(
                    position = null,
                    resolution = ProgressResolution(null, null, null, false),
                )
            }
            val position = restored.position
            val chapterIndex = position?.let { saved ->
                toc.indexOfFirst { it.id == saved.chapterId }.takeIf { it >= 0 } ?: saved.chapterIndex
            }?.coerceIn(toc.indices) ?: 0
            position?.viewMode?.let { mode ->
                val value = runCatching { ReaderViewMode.valueOf(mode.uppercase()) }.getOrNull()
                if (value != null) container.settings.update { it.copy(viewMode = value) }
            }
            _state.value = _state.value.copy(
                loading = true,
                chapterLoading = true,
                layoutLoading = false,
                preloadCompleted = 0,
                preloadTotal = 0,
                book = book,
                toc = toc,
                chapterIndex = chapterIndex,
                charOffset = position?.charOffset ?: 0,
                progression = position?.progression ?: 0.0,
                conflict = restored.resolution.takeIf { it.hasConflict },
            )
            launch {
                container.downloads.observe(bookId).collect { download ->
                    _state.value = _state.value.copy(download = download.comparedWith(book.fingerprint))
                }
            }
            loadOpeningWindow(chapterIndex, position?.charOffset ?: 0)
        }
    }

    private suspend fun loadOpeningWindow(index: Int, offset: Int) {
        val current = _state.value
        val book = current.book ?: return
        val summary = current.toc.getOrNull(index) ?: return
        _state.value = current.copy(
            loading = true,
            chapterLoading = true,
            layoutLoading = false,
            preloadCompleted = 0,
            preloadTotal = chapterWindowRange(index, current.toc.size).count(),
            chapter = null,
            error = null,
        )
        val preloaded = container.textReader.prefetchAround(
            bookId = bookId,
            toc = current.toc,
            currentIndex = index,
            contentVersion = book.fingerprint,
            onProgress = { completed, total ->
                val latest = _state.value
                if (latest.loading && latest.chapterIndex == index) {
                    _state.value = latest.copy(
                        preloadCompleted = maxOf(latest.preloadCompleted, completed),
                        preloadTotal = total,
                    )
                }
            },
        )
        val chapter = preloaded.chapters[summary.id]
        if (chapter == null) {
            val error = preloaded.failures[summary.id]
            _state.value = _state.value.copy(
                loading = false,
                chapterLoading = false,
                layoutLoading = false,
                error = error?.readerMessage("无法加载章节正文") ?: "无法加载章节正文，请重试",
            )
            return
        }
        val chapters = current.toc.mapIndexedNotNull { chapterIndex, item ->
            preloaded.chapters[item.id]?.let { chapterIndex to it }
        }.toMap()
        showChapter(chapter, index, offset, chapters = chapters)
    }

    private suspend fun loadChapter(index: Int, offset: Int, navigation: Boolean) {
        val current = _state.value
        val book = current.book ?: return
        val summary = current.toc.getOrNull(index) ?: return
        _state.value = current.copy(chapterLoading = true, layoutLoading = false, error = null)
        runCatching { container.textReader.chapter(bookId, summary.id, book.fingerprint) }
            .onSuccess { chapter ->
                showChapter(chapter, index, offset, navigation = navigation)
                scheduleWindowPrefetch(index)
            }
            .onFailure { error ->
                _state.value = _state.value.copy(
                    loading = false,
                    chapterLoading = false,
                    layoutLoading = false,
                    error = error.readerMessage("无法加载章节正文"),
                )
            }
    }

    private fun showChapter(
        chapter: TextChapter,
        index: Int,
        offset: Int,
        navigation: Boolean = false,
        chapters: Map<Int, TextChapter>? = null,
    ) {
        val current = _state.value
        val safeOffset = offset.coerceIn(0, chapter.body.length)
        val within = if (chapter.body.isNotEmpty()) safeOffset.toDouble() / chapter.body.length else 0.0
        val keep = chapterWindowRange(index, current.toc.size)
        val window = (chapters ?: (current.chapters + (index to chapter)))
            .filterKeys { it in keep }
            .toSortedMap()
        Log.i(
            READER_LOG_TAG,
            "showChapter index=$index offset=$safeOffset navigation=$navigation window=${window.keys.firstOrNull()}..${window.keys.lastOrNull()}",
        )
        _state.value = current.copy(
            loading = false,
            chapterLoading = false,
            layoutLoading = false,
            chapter = chapter,
            chapters = window,
            chapterIndex = index,
            charOffset = safeOffset,
            chapterProgress = within,
            progression = calculateTextProgression(index, current.toc.size, safeOffset, chapter.body.length),
            positionRevision = current.positionRevision + if (navigation) 1 else 0,
            error = null,
        )
    }

    private fun scheduleWindowPrefetch(index: Int) {
        val current = _state.value
        val book = current.book ?: return
        if (index !in current.toc.indices) return
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            val preloaded = container.textReader.prefetchAround(bookId, current.toc, index, book.fingerprint)
            val indexed = current.toc.mapIndexedNotNull { chapterIndex, item ->
                preloaded.chapters[item.id]?.let { chapterIndex to it }
            }.toMap()
            mergeChapterWindow(indexed)
        }
    }

    private fun mergeChapterWindow(loaded: Map<Int, TextChapter>) {
        val current = _state.value
        val keep = chapterWindowRange(current.chapterIndex, current.toc.size)
        _state.value = current.copy(
            chapters = (current.chapters + loaded)
                .filterKeys { it in keep }
                .toSortedMap(),
        )
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) { delay(700); persistCurrent() }
    }

    private fun currentNarrationRequest(visibleChapterIndex: Int, visibleStartOffset: Int): NarrationRequest? {
        val current = _state.value
        val book = current.book ?: return null
        val chapter = current.chapters[visibleChapterIndex]
            ?: current.chapter?.takeIf { visibleChapterIndex == current.chapterIndex }
            ?: return null
        return NarrationRequest(
            bookId = book.id,
            bookTitle = book.title,
            bookFormat = book.format,
            contentVersion = book.fingerprint,
            chapterId = chapter.id,
            chapterIndex = visibleChapterIndex,
            chapterTitle = chapter.title,
            charOffset = visibleStartOffset.coerceIn(0, chapter.body.length),
        )
    }

    private fun onNarrationChanged(narration: NarrationState) {
        val current = _state.value
        _state.value = current.copy(narration = narration)
        if (!narration.isActive || narration.bookId != bookId || current.chapter == null) return
        val targetChapterId = narration.chapterId ?: return
        if (current.chapter.id == targetChapterId) {
            val offset = narration.charOffset.coerceIn(0, current.chapter.body.length)
            val within = if (current.chapter.body.isNotEmpty()) offset.toDouble() / current.chapter.body.length else 0.0
            _state.value = _state.value.copy(
                charOffset = offset,
                chapterProgress = within,
                progression = calculateTextProgression(current.chapterIndex, current.toc.size, offset, current.chapter.body.length),
            )
            return
        }
        val targetIndex = current.toc.indexOfFirst { it.id == targetChapterId }
        if (targetIndex < 0 || current.chapterLoading || current.layoutLoading) return
        chapterJob?.cancel()
        chapterJob = viewModelScope.launch(Dispatchers.IO) {
            loadChapter(targetIndex, narration.charOffset, navigation = true)
        }
    }

    private suspend fun persistCurrent() {
        val current = _state.value
        val chapter = current.chapter ?: return
        val book = current.book ?: return
        val paragraph = chapter.body.take(current.charOffset).count { it == '\n' }
        container.textReader.save(
            bookId = bookId,
            format = book.format,
            contentVersion = book.fingerprint,
            chapterTitle = chapter.title,
            position = TextReadingPosition(
                chapterId = chapter.id,
                chapterIndex = current.chapterIndex,
                charOffset = current.charOffset,
                progression = current.progression,
                viewMode = current.preferences.viewMode.name.lowercase(),
                fontSizeSp = current.preferences.fontSizeSp,
                lineHeightMultiplier = current.preferences.lineHeightMultiplier,
                paragraphIndex = paragraph,
                chapterProgress = current.chapterProgress,
            ),
        )
    }
}

private fun Throwable.readerMessage(fallback: String): String = when (this) {
    is TimeoutCancellationException -> "章节加载超时，请重试"
    is UnknownHostException -> "找不到服务器；如已下载本书，可从离线书架重新打开"
    is ConnectException -> "服务器暂时无法连接"
    is SocketTimeoutException -> "章节加载超时，请重试"
    is SSLException -> "HTTPS 证书验证失败"
    is IOException -> "网络不可用且本地没有这个章节"
    else -> message?.takeIf { it.isNotBlank() && it.length <= 160 } ?: fallback
}

private const val READER_LOG_TAG = "PageShelfReader"
private const val CHAPTER_ENSURE_TIMEOUT_MILLIS = 6_000L
