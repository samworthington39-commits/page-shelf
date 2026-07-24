package com.example.bookshelf.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.bookshelf.AppContainer
import com.example.bookshelf.data.repository.AuthException
import com.example.bookshelf.data.settings.ServerConfig
import com.example.bookshelf.domain.Book
import com.example.bookshelf.domain.AppThemeMode
import com.example.bookshelf.domain.DownloadState
import com.example.bookshelf.domain.DownloadStatus
import com.example.bookshelf.domain.LibraryShelf
import com.example.bookshelf.domain.PdfNavigationItem
import com.example.bookshelf.domain.ProgressResolution
import com.example.bookshelf.domain.ReaderPreferences
import com.example.bookshelf.domain.ReadingProgress
import com.example.bookshelf.ui.reader.TextReaderViewModel
import java.util.concurrent.CancellationException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

sealed interface StartupState {
    data object Checking : StartupState
    data object Connected : StartupState
    data class NeedsLogin(val message: String? = null) : StartupState
}

class StartupViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<StartupState>(StartupState.Checking)
    val state: StateFlow<StartupState> = _state.asStateFlow()

    init { connect() }

    fun connect() {
        if (!container.auth.hasSavedCredentials()) {
            _state.value = StartupState.NeedsLogin()
            return
        }
        viewModelScope.launch {
            _state.value = StartupState.Checking
            _state.value = runCatching { container.auth.autoLogin() }
                .fold(
                    onSuccess = { StartupState.Connected },
                    onFailure = { StartupState.NeedsLogin(it.safeMessage("自动连接失败")) },
                )
        }
    }
}

sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus
    data object Testing : ConnectionStatus
    data class Success(val latencyMillis: Long, val apiVersion: String) : ConnectionStatus
    data class Error(val message: String) : ConnectionStatus
}

data class SettingsUiState(
    val address: String,
    val password: String = "",
    val passwordVisible: Boolean = false,
    val configured: Boolean,
    val hasActiveSession: Boolean,
    val progressSyncEnabled: Boolean,
    val status: ConnectionStatus = ConnectionStatus.Idle,
) {
    val candidate: ServerConfig? get() = runCatching { ServerConfig.parseAddress(address) }.getOrNull()
    val canConnect: Boolean get() = candidate != null && (password.isNotBlank() || hasActiveSession)
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val initial = container.serverConfig.current()
    private val _state = MutableStateFlow(
        SettingsUiState(
            address = initial.origin,
            configured = container.serverConfig.isConfigured,
            hasActiveSession = container.auth.hasSavedCredentials(),
            progressSyncEnabled = container.settings.state.value.progressSyncEnabled,
        )
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            container.settings.state.collect { preferences ->
                _state.value = _state.value.copy(progressSyncEnabled = preferences.progressSyncEnabled)
            }
        }
    }

    fun setAddress(value: String) = edit { copy(address = value) }
    fun setPassword(value: String) = edit { copy(password = value.take(512)) }
    fun togglePasswordVisibility() { _state.value = _state.value.copy(passwordVisible = !_state.value.passwordVisible) }
    fun setProgressSyncEnabled(enabled: Boolean) {
        container.settings.update { it.copy(progressSyncEnabled = enabled) }
        viewModelScope.launch { container.progress.applySyncPreference(enabled) }
    }

    fun connect() {
        val value = _state.value
        val config = try {
            ServerConfig.parseAddress(value.address)
        } catch (error: IllegalArgumentException) {
            _state.value = value.copy(status = ConnectionStatus.Error(error.message ?: "服务器地址格式错误"))
            return
        }
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = _state.value.copy(status = ConnectionStatus.Testing)
            runCatching {
                if (value.password.isNotBlank()) {
                    container.auth.login(config, value.password)
                } else if (config == container.serverConfig.current()) {
                    container.auth.autoLogin()
                } else {
                    throw AuthException("修改服务器地址后需要重新输入管理密码")
                }
            }.onSuccess { result ->
                _state.value = _state.value.copy(
                    configured = true,
                    hasActiveSession = true,
                    password = "",
                    status = ConnectionStatus.Success(result.latencyMillis, result.apiVersion),
                )
            }.onFailure { error ->
                if (error !is CancellationException) {
                    _state.value = _state.value.copy(status = ConnectionStatus.Error(error.safeMessage("连接失败")))
                }
            }
        }
    }

    private fun edit(transform: SettingsUiState.() -> SettingsUiState) {
        job?.cancel()
        _state.value = _state.value.transform().copy(status = ConnectionStatus.Idle)
    }
}

sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data class Content(
        val shelves: List<LibraryShelf>,
        val progress: Map<String, ReadingProgress> = emptyMap(),
        val downloads: Map<String, DownloadState> = emptyMap(),
        val isRefreshing: Boolean = false,
        val offline: Boolean = false,
        val unlockingShelfId: String? = null,
        val unlockErrorShelfId: String? = null,
        val unlockError: String? = null,
    ) : LibraryUiState
    data class Error(val message: String) : LibraryUiState
}

class LibraryViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()
    private var progress = emptyMap<String, ReadingProgress>()
    private var downloads = emptyMap<String, DownloadState>()

    init {
        viewModelScope.launch {
            combine(container.progress.observeAll(), container.downloads.observeAll()) { progressList, downloadList ->
                progressList.associateBy(ReadingProgress::bookId) to downloadList.associateBy(DownloadState::bookId)
            }.collect { (newProgress, newDownloads) ->
                progress = newProgress
                downloads = newDownloads
                val current = _state.value as? LibraryUiState.Content ?: return@collect
                _state.value = current.copy(progress = progress, downloads = downloads)
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val current = _state.value as? LibraryUiState.Content
            _state.value = current?.copy(isRefreshing = true) ?: LibraryUiState.Loading
            _state.value = runCatching { container.books.shelves() }
                .fold(
                    onSuccess = { shelves ->
                        LibraryUiState.Content(
                            shelves = shelves,
                            progress = progress,
                            downloads = downloads,
                            offline = shelves.singleOrNull()?.id == "__offline__",
                        )
                    },
                    onFailure = { error -> current?.copy(isRefreshing = false) ?: LibraryUiState.Error(error.safeMessage("无法加载书架")) },
                )
        }
    }

    fun unlockShelf(shelfId: String, pin: String) {
        if (pin.length != 4 || !pin.all(Char::isDigit)) return
        val current = _state.value as? LibraryUiState.Content ?: return
        viewModelScope.launch {
            _state.value = current.copy(unlockingShelfId = shelfId, unlockError = null)
            runCatching { container.books.unlockShelf(shelfId, pin) }
                .onSuccess { unlocked ->
                    val latest = _state.value as? LibraryUiState.Content ?: current
                    _state.value = latest.copy(
                        shelves = latest.shelves.map { if (it.id == unlocked.id) unlocked else it },
                        unlockingShelfId = null,
                    )
                }
                .onFailure { error ->
                    val latest = _state.value as? LibraryUiState.Content ?: current
                    _state.value = latest.copy(
                        unlockingShelfId = null,
                        unlockErrorShelfId = shelfId,
                        unlockError = if (error is HttpException && error.code() == 401) "密码错误，请重新输入"
                        else error.safeMessage("无法解锁书架"),
                    )
                }
        }
    }
}

data class ReaderUiState(
    val loading: Boolean = true,
    val book: Book? = null,
    val download: DownloadState? = null,
    val initialPageIndex: Int = 0,
    val positionRevision: Int = 0,
    val navigation: List<PdfNavigationItem> = emptyList(),
    val conflict: ProgressResolution? = null,
    val error: String? = null,
)

class ReaderViewModel(
    private val container: AppContainer,
    private val bookId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()
    private var saveJob: Job? = null
    private var lastPage = 0
    private var lastPageCount = 1
    private var lastPageOffset = 0.0
    private var lastViewMode = "single_page"

    init { load() }

    private fun load() = viewModelScope.launch {
        val book = runCatching { container.books.book(bookId) }.getOrElse {
            _state.value = ReaderUiState(loading = false, error = it.safeMessage("无法打开书籍")); return@launch
        }
        if (!book.capabilities.pageNavigation || book.pageCount == null) {
            _state.value = ReaderUiState(loading = false, book = book, error = "该格式不支持 PDF 按页阅读"); return@launch
        }
        val resolution = container.progress.restore(bookId, "pdf", book.fingerprint)
        lastPage = resolution.selected?.pdfPage?.coerceIn(0, book.pageCount - 1) ?: 0
        lastPageCount = book.pageCount
        _state.value = ReaderUiState(
            loading = false,
            book = book,
            initialPageIndex = lastPage,
            conflict = resolution.takeIf { it.hasConflict },
        )
        if (book.hasPdfNavigation) {
            runCatching { container.books.pdfNavigation(bookId) }
                .onSuccess { _state.value = _state.value.copy(navigation = it) }
        }
        launch {
            container.downloads.observe(bookId).collect { download ->
                _state.value = _state.value.copy(download = download.comparedWith(book.fingerprint))
            }
        }
        val download = container.downloads.state(bookId)
        if (download.localPath?.let { java.io.File(it) }?.isFile != true || download.fingerprint != book.fingerprint) {
            container.downloads.ensureReadableCopy(book)
        }
    }

    fun onPageChanged(pageIndex: Int, pageCount: Int, pageOffset: Double, viewMode: String) {
        lastPage = pageIndex
        lastPageCount = pageCount
        lastPageOffset = pageOffset
        lastViewMode = viewMode
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(700); persist() }
    }

    fun saveOffline() {
        val book = _state.value.book ?: return
        viewModelScope.launch { container.downloads.enqueue(book, permanent = true) }
    }

    fun pauseDownload() { viewModelScope.launch { container.downloads.pause(bookId) } }

    fun retryDownload() {
        val book = _state.value.book ?: return
        viewModelScope.launch { container.downloads.redownload(book, _state.value.download?.isPermanent == true) }
    }

    fun setTheme(mode: AppThemeMode) = container.settings.update { it.copy(themeMode = mode) }

    fun resolveConflict(useLocal: Boolean) {
        val conflict = _state.value.conflict ?: return
        viewModelScope.launch {
            val selected = container.progress.resolve(conflict, useLocal)
            selected?.let { lastPage = it.pdfPage.coerceAtLeast(0) }
            _state.value = _state.value.copy(
                conflict = null,
                initialPageIndex = lastPage,
                positionRevision = _state.value.positionRevision + 1,
            )
        }
    }

    fun exit(onSaved: () -> Unit) {
        viewModelScope.launch {
            saveJob?.cancel()
            persist()
            onSaved()
        }
    }

    fun persistNow() { viewModelScope.launch { persist() } }

    private suspend fun persist() {
        val book = _state.value.book ?: return
        container.progress.savePdf(book.id, lastPage, lastPageCount, lastPageOffset, lastViewMode, book.fingerprint)
    }
}

data class ManagedDownload(val book: Book, val download: DownloadState)

data class ManageUiState(
    val downloads: List<ManagedDownload> = emptyList(),
    val selected: Set<String> = emptySet(),
    val preferences: ReaderPreferences = ReaderPreferences(),
    val cacheMessage: String? = null,
) {
    val storageBytes: Long get() = downloads.sumOf { it.download.totalBytes }
}

class ManageViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ManageUiState(preferences = container.settings.state.value))
    val state: StateFlow<ManageUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(container.downloads.observeAll(), container.settings.state) { downloads, prefs -> downloads to prefs }
                .collect { (downloads, prefs) ->
                    val permanent = downloads.filter { it.isPermanent && it.status in setOf(DownloadStatus.DOWNLOADED, DownloadStatus.OUTDATED) }
                    val managed = permanent.mapNotNull { download ->
                        runCatching { container.books.book(download.bookId) }.getOrNull()?.let { ManagedDownload(it, download) }
                    }
                    _state.value = _state.value.copy(downloads = managed, preferences = prefs)
                }
        }
    }

    fun toggleSelected(bookId: String) {
        val selected = _state.value.selected.toMutableSet()
        if (!selected.add(bookId)) selected.remove(bookId)
        _state.value = _state.value.copy(selected = selected)
    }

    fun delete(bookId: String, deleteProgress: Boolean) = viewModelScope.launch {
        container.downloads.delete(bookId)
        if (deleteProgress) container.progress.deleteLocal(bookId)
        _state.value = _state.value.copy(selected = _state.value.selected - bookId)
    }

    fun deleteSelected() = viewModelScope.launch {
        _state.value.selected.forEach { container.downloads.delete(it) }
        _state.value = _state.value.copy(selected = emptySet())
    }

    fun clearTemporary() = viewModelScope.launch {
        val freed = container.downloads.clearTemporary()
        _state.value = _state.value.copy(cacheMessage = if (freed > 0) "已释放临时文件" else "没有可清理的临时缓存")
    }

    fun updatePreferences(transform: (ReaderPreferences) -> ReaderPreferences) = container.settings.update(transform)
    fun clearMessage() { _state.value = _state.value.copy(cacheMessage = null) }
}

class PageShelfViewModelFactory(
    private val container: AppContainer,
    private val bookId: String? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(StartupViewModel::class.java) -> StartupViewModel(container) as T
        modelClass.isAssignableFrom(LibraryViewModel::class.java) -> LibraryViewModel(container) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(container) as T
        modelClass.isAssignableFrom(ManageViewModel::class.java) -> ManageViewModel(container) as T
        modelClass.isAssignableFrom(TextReaderViewModel::class.java) -> TextReaderViewModel(container, requireNotNull(bookId)) as T
        modelClass.isAssignableFrom(ReaderViewModel::class.java) -> ReaderViewModel(container, requireNotNull(bookId)) as T
        else -> error("Unknown ViewModel: ${modelClass.name}")
    }
}

private fun Throwable.safeMessage(fallback: String): String = when (this) {
    is AuthException -> message ?: fallback
    is UnknownHostException -> "找不到服务器，请检查地址和网络"
    is ConnectException -> "无法连接服务器，请检查网络和端口"
    is SocketTimeoutException -> "请求超时，请稍后重试"
    is SSLException -> "HTTPS 证书验证失败"
    is HttpException -> when (code()) {
        401 -> "登录已失效，请重新连接服务器"
        404 -> "服务器上找不到请求的内容"
        in 500..599 -> "服务器暂时异常"
        else -> "$fallback（HTTP ${code()}）"
    }
    is IOException -> "网络不可用，请检查连接后重试"
    else -> message?.takeIf { it.isNotBlank() && it.length <= 160 } ?: fallback
}
