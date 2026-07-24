package com.example.bookshelf

import android.app.Application
import androidx.core.content.edit
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

class PageShelfApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
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
