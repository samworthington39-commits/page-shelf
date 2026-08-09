package com.example.bookshelf

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.bookshelf.data.local.AppDatabase
import com.example.bookshelf.data.remote.ApiFactory
import com.example.bookshelf.data.remote.BooksApi
import com.example.bookshelf.data.remote.ServerConnectionTester
import com.example.bookshelf.data.repository.BookRepository
import com.example.bookshelf.data.repository.AuthRepository
import com.example.bookshelf.data.repository.DownloadRepository
import com.example.bookshelf.data.repository.ProgressRepository
import com.example.bookshelf.data.settings.ServerConfigStore
import com.example.bookshelf.data.settings.SecureCredentialStore
import com.example.bookshelf.data.settings.AppSettingsStore
import com.example.bookshelf.data.settings.ShelfAccessStore
import com.example.bookshelf.narration.NarrationController
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PageShelfApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Reading progress must reach the server as soon as the app returns to the
        // foreground: WorkManager alone can leave ready items stuck for hours (delayed
        // retries + KEEP policy used to drop immediate requests).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                applicationScope.launch {
                    if (container.credentials.bearerToken() == null) {
                        runCatching { container.auth.autoLogin() }.getOrNull() ?: return@launch
                    }
                    container.progress.syncNow()
                }
            }
        })
    }
}

class AppContainer(application: Application) {
    val narration = NarrationController(application)
    val database = AppDatabase.create(application)
    val serverConfig = ServerConfigStore(application)
    val credentials = SecureCredentialStore(application)
    val settings = AppSettingsStore(application)
    val shelfAccess = ShelfAccessStore()
    val connectionTester = ServerConnectionTester()
    val authApi = ApiFactory.createAuth(serverConfig)
    val auth = AuthRepository(authApi, connectionTester, serverConfig, credentials)
    val api: BooksApi = ApiFactory.create(serverConfig, shelfAccess, credentials)
    val books = BookRepository(api, database.bookDao(), serverConfig, shelfAccess, database.downloadDao(), credentials)
    val downloads = DownloadRepository(application, database.downloadDao(), database.chapterCacheDao())
    private val deviceId = installationId(application)
    val progress = ProgressRepository(
        application,
        api,
        database.readingProgressDao(),
        database.progressSyncDao(),
        deviceId,
        settings,
    )
    val textReader = com.example.bookshelf.data.repository.TextReaderRepository(
        api,
        database.chapterCacheDao(),
        progress,
    )
}

private fun installationId(application: Application): String {
    val preferences = application.getSharedPreferences("installation", Application.MODE_PRIVATE)
    return preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        preferences.edit { putString("device_id", it) }
    }
}
