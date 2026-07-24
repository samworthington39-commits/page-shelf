package com.example.bookshelf.data.settings

import android.content.Context
import androidx.core.content.edit
import com.example.bookshelf.domain.AppThemeMode
import com.example.bookshelf.domain.ReaderBackground
import com.example.bookshelf.domain.ReaderFont
import com.example.bookshelf.domain.ReaderPreferences
import com.example.bookshelf.domain.ReaderViewMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("reader_preferences", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<ReaderPreferences> = _state.asStateFlow()

    fun update(transform: (ReaderPreferences) -> ReaderPreferences) {
        val value = transform(_state.value).normalized()
        _state.value = value
        preferences.edit {
            putString("view_mode", value.viewMode.name)
            putFloat("font_size", value.fontSizeSp)
            putFloat("line_height", value.lineHeightMultiplier)
            putString("font", value.font.name)
            putString("background", value.background.name)
            putString("theme", value.themeMode.name)
            putBoolean("progress_sync_enabled", value.progressSyncEnabled)
        }
    }

    fun toggleTheme() = update {
        it.copy(themeMode = if (it.themeMode == AppThemeMode.DARK) AppThemeMode.LIGHT else AppThemeMode.DARK)
    }

    private fun load() = ReaderPreferences(
        viewMode = enumValue(preferences.getString("view_mode", null), ReaderViewMode.PAGED),
        fontSizeSp = preferences.getFloat("font_size", 18f),
        lineHeightMultiplier = preferences.getFloat("line_height", 1.7f),
        font = enumValue(preferences.getString("font", null), ReaderFont.SERIF),
        background = enumValue(preferences.getString("background", null), ReaderBackground.AUTO),
        themeMode = enumValue(preferences.getString("theme", null), AppThemeMode.SYSTEM),
        progressSyncEnabled = preferences.getBoolean("progress_sync_enabled", true),
    ).normalized()

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(fallback)

    private fun ReaderPreferences.normalized() = copy(
        fontSizeSp = fontSizeSp.coerceIn(14f, 30f),
        lineHeightMultiplier = lineHeightMultiplier.coerceIn(1.55f, 1.8f),
    )
}
