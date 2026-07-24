package com.example.bookshelf.data.settings

import android.content.Context
import androidx.core.content.edit
import com.example.bookshelf.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ServerConfigStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val defaultConfig = ServerConfig.fromBaseUrl(BuildConfig.API_BASE_URL)

    @Volatile
    private var currentConfig = load()
    private val _config = MutableStateFlow(currentConfig)
    val config: StateFlow<ServerConfig> = _config.asStateFlow()

    val isConfigured: Boolean get() = preferences.getBoolean(KEY_CONFIGURED, false)

    fun current(): ServerConfig = currentConfig

    fun save(config: ServerConfig) {
        currentConfig = config
        _config.value = config
        preferences.edit {
            putString(KEY_SCHEME, config.scheme)
            putString(KEY_HOST, config.host)
            putInt(KEY_PORT, config.port)
            putBoolean(KEY_CONFIGURED, true)
        }
    }

    private fun load(): ServerConfig {
        if (!preferences.getBoolean(KEY_CONFIGURED, false)) return defaultConfig
        return runCatching {
            ServerConfig.parse(
                preferences.getString(KEY_SCHEME, defaultConfig.scheme).orEmpty(),
                preferences.getString(KEY_HOST, defaultConfig.host).orEmpty(),
                preferences.getInt(KEY_PORT, defaultConfig.port).toString(),
            )
        }.getOrDefault(defaultConfig)
    }

    private companion object {
        const val PREFERENCES_NAME = "server_connection"
        const val KEY_SCHEME = "scheme"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_CONFIGURED = "configured"
    }
}
