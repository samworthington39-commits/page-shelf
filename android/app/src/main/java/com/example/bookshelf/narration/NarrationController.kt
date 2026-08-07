package com.example.bookshelf.narration

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal object NarrationRuntime {
    val state = MutableStateFlow(NarrationState())
}

class NarrationController(private val application: Application) {
    private val preferences = application.getSharedPreferences(PREFERENCES, Application.MODE_PRIVATE)
    private val settings: TtsSettingsRepository = DataStoreTtsSettingsRepository(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var persistPlaybackSpeedJob: Job? = null
    private val _prepared = MutableStateFlow<NarrationRequest?>(null)
    val prepared: StateFlow<NarrationRequest?> = _prepared.asStateFlow()
    val state: StateFlow<NarrationState> = NarrationRuntime.state.asStateFlow()

    init {
        val initialVoice = runCatching {
            NarrationVoice.valueOf(preferences.getString(KEY_VOICE, null).orEmpty())
        }.getOrDefault(NarrationVoice.FEMALE)
        val legacySpeed = preferences.getFloat(KEY_LEGACY_SPEED, DEFAULT_PLAYBACK_SPEED)
        NarrationRuntime.state.value = NarrationRuntime.state.value.copy(
            voice = initialVoice,
            playbackSpeed = normalizePlaybackSpeed(legacySpeed),
        )
        scope.launch {
            settings.migrateLegacySpeed()
            settings.playbackSpeed.collect { storedSpeed ->
                if (persistPlaybackSpeedJob?.isActive != true) {
                    NarrationRuntime.state.value = NarrationRuntime.state.value.copy(
                        playbackSpeed = storedSpeed,
                    )
                }
            }
        }
    }

    fun prepare(request: NarrationRequest) {
        _prepared.value = request
    }

    fun startPrepared() {
        _prepared.value?.let(::start)
    }

    fun start(request: NarrationRequest) {
        _prepared.value = request
        send(NarrationService.ACTION_START) {
            putExtra(NarrationService.EXTRA_BOOK_ID, request.bookId)
            putExtra(NarrationService.EXTRA_BOOK_TITLE, request.bookTitle)
            putExtra(NarrationService.EXTRA_BOOK_FORMAT, request.bookFormat)
            putExtra(NarrationService.EXTRA_CONTENT_VERSION, request.contentVersion)
            putExtra(NarrationService.EXTRA_CHAPTER_ID, request.chapterId)
            putExtra(NarrationService.EXTRA_CHAPTER_INDEX, request.chapterIndex)
            putExtra(NarrationService.EXTRA_CHAPTER_TITLE, request.chapterTitle)
            putExtra(NarrationService.EXTRA_CHAR_OFFSET, request.charOffset)
        }
    }

    fun pause() {
        if (state.value.isActive) send(NarrationService.ACTION_PAUSE)
    }

    fun resume() {
        if (state.value.status == NarrationStatus.PAUSED) send(NarrationService.ACTION_RESUME)
    }

    fun stop() {
        if (state.value.isActive) send(NarrationService.ACTION_STOP)
    }

    fun previousChapter() {
        if (state.value.isActive) send(NarrationService.ACTION_PREVIOUS_CHAPTER)
    }

    fun nextChapter() {
        if (state.value.isActive) send(NarrationService.ACTION_NEXT_CHAPTER)
    }

    fun setVoice(voice: NarrationVoice) {
        preferences.edit { putString(KEY_VOICE, voice.name) }
        NarrationRuntime.state.value = NarrationRuntime.state.value.copy(voice = voice)
        if (state.value.isActive) {
            send(NarrationService.ACTION_SET_VOICE) { putExtra(NarrationService.EXTRA_VOICE, voice.name) }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val safeSpeed = normalizePlaybackSpeed(speed)
        if (state.value.playbackSpeed == safeSpeed) return
        NarrationRuntime.state.value = NarrationRuntime.state.value.copy(playbackSpeed = safeSpeed)
        if (state.value.isActive) {
            send(NarrationService.ACTION_SET_PLAYBACK_SPEED) {
                putExtra(NarrationService.EXTRA_PLAYBACK_SPEED, safeSpeed)
            }
        }
        persistPlaybackSpeedJob?.cancel()
        persistPlaybackSpeedJob = scope.launch {
            delay(PLAYBACK_SPEED_PERSIST_DEBOUNCE_MS)
            settings.setPlaybackSpeed(safeSpeed)
        }
    }

    fun setSleepTimer(timer: NarrationSleepTimer?) {
        if (!state.value.isActive) return
        send(NarrationService.ACTION_SET_SLEEP_TIMER) {
            putExtra(NarrationService.EXTRA_SLEEP_TIMER, timer?.name.orEmpty())
        }
    }

    private fun send(action: String, extras: Intent.() -> Unit = {}) {
        val intent = Intent(application, NarrationService::class.java).setAction(action).apply(extras)
        if (action == NarrationService.ACTION_START) {
            ContextCompat.startForegroundService(application, intent)
        } else {
            application.startService(intent)
        }
    }

    companion object {
        const val MIN_PLAYBACK_SPEED = 0.75f
        const val MAX_PLAYBACK_SPEED = 2.5f
        const val PLAYBACK_SPEED_STEP = 0.05f
        val PRESET_PLAYBACK_SPEEDS = listOf(
            0.75f, 0.85f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f,
            1.5f, 1.6f, 1.75f, 2.0f, 2.25f, 2.5f,
        )
        val QUICK_PLAYBACK_SPEEDS = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        private const val PREFERENCES = "narration"
        private const val KEY_VOICE = "voice"
        private const val KEY_LEGACY_SPEED = "speed"
        private const val PLAYBACK_SPEED_PERSIST_DEBOUNCE_MS = 220L
    }
}

internal const val DEFAULT_PLAYBACK_SPEED = 1.0f

internal fun normalizePlaybackSpeed(speed: Float): Float {
    val finiteSpeed = speed.takeIf(Float::isFinite) ?: DEFAULT_PLAYBACK_SPEED
    val clamped = finiteSpeed.coerceIn(
        NarrationController.MIN_PLAYBACK_SPEED,
        NarrationController.MAX_PLAYBACK_SPEED,
    )
    return (clamped / NarrationController.PLAYBACK_SPEED_STEP).roundToInt() *
        NarrationController.PLAYBACK_SPEED_STEP
}
