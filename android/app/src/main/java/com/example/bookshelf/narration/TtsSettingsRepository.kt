package com.example.bookshelf.narration

import android.content.Context
import androidx.core.content.edit
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.ttsSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tts_settings",
)

internal interface TtsSettingsRepository {
    val playbackSpeed: Flow<Float>
    suspend fun setPlaybackSpeed(speed: Float)
    suspend fun migrateLegacySpeed()
}

internal class DataStoreTtsSettingsRepository(context: Context) : TtsSettingsRepository {
    private val applicationContext = context.applicationContext
    private val dataStore = applicationContext.ttsSettingsDataStore
    private val legacyPreferences = applicationContext.getSharedPreferences(
        LEGACY_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    override val playbackSpeed: Flow<Float> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            normalizePlaybackSpeed(preferences[PLAYBACK_SPEED] ?: DEFAULT_PLAYBACK_SPEED)
        }
        .distinctUntilChanged()

    override suspend fun setPlaybackSpeed(speed: Float) {
        dataStore.edit { preferences ->
            preferences[PLAYBACK_SPEED] = normalizePlaybackSpeed(speed)
        }
    }

    override suspend fun migrateLegacySpeed() {
        val legacySpeed = legacyPreferences.takeIf { it.contains(LEGACY_SPEED) }
            ?.getFloat(LEGACY_SPEED, DEFAULT_PLAYBACK_SPEED)
        dataStore.edit { preferences ->
            if (PLAYBACK_SPEED !in preferences && legacySpeed != null) {
                preferences[PLAYBACK_SPEED] = normalizePlaybackSpeed(legacySpeed)
            }
        }
        if (legacySpeed != null) legacyPreferences.edit { remove(LEGACY_SPEED) }
    }

    private companion object {
        val PLAYBACK_SPEED = floatPreferencesKey("tts_playback_speed")
        const val LEGACY_PREFERENCES = "narration"
        const val LEGACY_SPEED = "speed"
    }
}
