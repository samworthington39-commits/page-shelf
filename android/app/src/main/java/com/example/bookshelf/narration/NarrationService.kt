package com.example.bookshelf.narration

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.example.bookshelf.BuildConfig
import com.example.bookshelf.MainActivity
import com.example.bookshelf.PageShelfApplication
import com.example.bookshelf.R
import com.example.bookshelf.domain.TextReadingPosition
import com.example.bookshelf.domain.calculateTextProgression
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class NarrationService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val container by lazy { (application as PageShelfApplication).container }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private lateinit var mediaSession: MediaSession
    private lateinit var focusRequest: AudioFocusRequest
    private lateinit var audioPlayer: NarrationAudioPlayer
    private var playbackJob: Job? = null
    private var chapterNavigationJob: Job? = null
    private var sleepTimerJob: Job? = null
    private val playbackMutex = Mutex()
    private var engine: SherpaMatchaEngine? = null
    private var generation = 0
    private var resumeAfterFocusGain = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioPlayer = NarrationAudioPlayer(this) {
            pauseNarration(fromAudioFocus = false)
        }.also { player ->
            player.setPlaybackSpeed(NarrationRuntime.state.value.playbackSpeed)
        }
        mediaSession = MediaSession(this, "PageShelfNarration").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resumeNarration()
                override fun onPause() = pauseNarration(fromAudioFocus = false)
                override fun onStop() = stopNarration()
                override fun onSkipToPrevious() = changeChapter(-1)
                override fun onSkipToNext() = changeChapter(1)
            })
            isActive = true
        }
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(::onAudioFocusChanged)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                chapterNavigationJob?.cancel()
                chapterNavigationJob = null
                cancelSleepTimer()
                val request = intent.toNarrationRequest() ?: return START_NOT_STICKY
                val current = NarrationRuntime.state.value
                NarrationRuntime.state.value = current.copy(
                    status = NarrationStatus.PREPARING,
                    bookId = request.bookId,
                    bookTitle = request.bookTitle,
                    bookFormat = request.bookFormat,
                    contentVersion = request.contentVersion,
                    chapterId = request.chapterId,
                    chapterIndex = request.chapterIndex,
                    chapterCount = 0,
                    chapterTitle = request.chapterTitle,
                    charOffset = request.charOffset,
                    currentTextEndOffset = request.charOffset,
                    currentText = "",
                    sleepTimer = null,
                    sleepTimerEndsAtElapsedRealtimeMs = null,
                    error = null,
                )
                mediaSession.isActive = true
                updateMediaSession()
                startForeground(NOTIFICATION_ID, buildNotification())
                startNarration(request)
            }
            ACTION_PAUSE -> pauseNarration(fromAudioFocus = false)
            ACTION_RESUME -> resumeNarration()
            ACTION_STOP -> stopNarration()
            ACTION_PREVIOUS_CHAPTER -> changeChapter(-1)
            ACTION_NEXT_CHAPTER -> changeChapter(1)
            ACTION_SET_VOICE -> {
                val voice = intent.getStringExtra(EXTRA_VOICE)
                    ?.let { runCatching { NarrationVoice.valueOf(it) }.getOrNull() }
                    ?: return START_NOT_STICKY
                NarrationRuntime.state.value = NarrationRuntime.state.value.copy(voice = voice)
                restartAtCurrentPosition()
            }
            ACTION_SET_PLAYBACK_SPEED -> {
                val playbackSpeed = normalizePlaybackSpeed(
                    intent.getFloatExtra(EXTRA_PLAYBACK_SPEED, DEFAULT_PLAYBACK_SPEED),
                )
                NarrationRuntime.state.value = NarrationRuntime.state.value.copy(
                    playbackSpeed = playbackSpeed,
                )
                audioPlayer.setPlaybackSpeed(playbackSpeed)
                updateMediaSession()
                debugLog(
                    "Player speed changed: playbackSpeed=$playbackSpeed " +
                        "pitch=1.0 positionMs=${audioPlayer.currentPositionMs()}",
                )
            }
            ACTION_SET_SLEEP_TIMER -> {
                val timer = intent.getStringExtra(EXTRA_SLEEP_TIMER)
                    ?.takeIf(String::isNotBlank)
                    ?.let { runCatching { NarrationSleepTimer.valueOf(it) }.getOrNull() }
                setSleepTimer(timer)
            }
        }
        return START_NOT_STICKY
    }

    private fun startNarration(request: NarrationRequest) {
        val previous = playbackJob
        audioPlayer.stop()
        val token = ++generation
        playbackJob = serviceScope.launch {
            previous?.cancelAndJoin()
            playbackMutex.withLock { runNarration(request, token) }
        }
    }

    private suspend fun runNarration(request: NarrationRequest, token: Int) {
        var localEngine: SherpaMatchaEngine? = null
        try {
            if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                error("其他应用正在使用音频，暂时无法开始朗读")
            }
            val toc = container.textReader.toc(request.bookId, request.contentVersion)
            if (toc.isEmpty()) error("这本书没有可朗读的正文")
            if (token == generation) publish(NarrationRuntime.state.value.copy(chapterCount = toc.size))

            var openingChapterIndex = toc.indexOfFirst { it.id == request.chapterId }
                .takeIf { it >= 0 } ?: request.chapterIndex
            openingChapterIndex = openingChapterIndex.coerceIn(toc.indices)
            val selectedVoice = NarrationRuntime.state.value.voice
            val preparedEngine = SherpaMatchaEngine(applicationContext, selectedVoice)
            localEngine = preparedEngine
            engine = preparedEngine
            val queue = TtsAudioQueue(TtsAudioCache(applicationContext), preparedEngine)
            val source = flow {
                var offset = request.charOffset
                for (chapterIndex in openingChapterIndex..toc.lastIndex) {
                    val summary = toc[chapterIndex]
                    val chapter = container.textReader.chapter(
                        request.bookId,
                        summary.id,
                        request.contentVersion,
                    )
                    val segments = SentenceSegmenter.segment(
                        chapter.body,
                        offset,
                    )
                    segments.forEachIndexed { segmentIndex, segment ->
                        emit(
                            NarrationSourceSegment(
                                id = "${chapter.id}_${segment.start}_${segment.end}",
                                bookId = request.bookId,
                                chapterId = chapter.id,
                                chapterIndex = chapterIndex,
                                chapterTitle = chapter.title,
                                chapterBody = chapter.body,
                                start = segment.start,
                                end = segment.end,
                                text = segment.text,
                                isLastInChapter = segmentIndex == segments.lastIndex,
                            ),
                        )
                    }
                    offset = 0
                }
            }

            var stoppedBySleepTimer = false
            coroutineScope {
                val queueHandle = queue.start(this, source, selectedVoice)
                for (queued in queueHandle.segments) {
                    if (token != generation) break
                    val prepared = queue.ensureAvailable(queued, selectedVoice)
                    val readyAhead = queue.markDequeued()
                    val segment = prepared.source
                    val paused = NarrationRuntime.state.value.status == NarrationStatus.PAUSED
                    publish(
                        NarrationRuntime.state.value.copy(
                            status = if (paused) NarrationStatus.PAUSED else NarrationStatus.PLAYING,
                            chapterId = segment.chapterId,
                            chapterIndex = segment.chapterIndex,
                            chapterTitle = segment.chapterTitle,
                            charOffset = segment.start,
                            currentTextEndOffset = segment.end,
                            currentText = segment.text,
                            error = null,
                        ),
                    )
                    saveProgress(
                        request = request,
                        chapterId = segment.chapterId,
                        chapterTitle = segment.chapterTitle,
                        chapterIndex = segment.chapterIndex,
                        charOffset = segment.start,
                        chapterBody = segment.chapterBody,
                        chapterCount = toc.size,
                    )
                    logPreparedSegment(prepared, readyAhead)
                    audioPlayer.play(prepared.file, startWhenReady = !paused)
                    if (segment.isLastInChapter) {
                        saveProgress(
                            request = request,
                            chapterId = segment.chapterId,
                            chapterTitle = segment.chapterTitle,
                            chapterIndex = segment.chapterIndex,
                            charOffset = segment.chapterBody.length,
                            chapterBody = segment.chapterBody,
                            chapterCount = toc.size,
                        )
                        if (NarrationRuntime.state.value.sleepTimer == NarrationSleepTimer.END_OF_CHAPTER) {
                            stoppedBySleepTimer = true
                            queueHandle.producer.cancel()
                            break
                        }
                    }
                }
                queueHandle.producer.join()
            }
            if (token == generation) {
                finishPlayback(
                    if (stoppedBySleepTimer) NarrationStatus.IDLE else NarrationStatus.COMPLETED,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (token == generation) {
                cancelSleepTimer()
                publish(
                    NarrationRuntime.state.value.copy(
                        status = NarrationStatus.ERROR,
                        sleepTimer = null,
                        sleepTimerEndsAtElapsedRealtimeMs = null,
                        error = error.message?.takeIf(String::isNotBlank) ?: "朗读失败",
                    ),
                )
                finishForegroundPlayback()
            }
        } finally {
            localEngine?.let { closing ->
                runCatching { closing.close() }
                if (engine === closing) engine = null
            }
        }
    }

    private suspend fun saveProgress(
        request: NarrationRequest,
        chapterId: String,
        chapterTitle: String,
        chapterIndex: Int,
        charOffset: Int,
        chapterBody: String,
        chapterCount: Int,
    ) {
        val reader = container.settings.state.value
        val chapterLength = chapterBody.length
        val within = if (chapterLength > 0) charOffset.toDouble() / chapterLength else 0.0
        container.textReader.save(
            bookId = request.bookId,
            format = request.bookFormat,
            contentVersion = request.contentVersion,
            chapterTitle = chapterTitle,
            position = TextReadingPosition(
                chapterId = chapterId,
                chapterIndex = chapterIndex,
                charOffset = charOffset,
                progression = calculateTextProgression(chapterIndex, chapterCount, charOffset, chapterLength),
                viewMode = reader.viewMode.name.lowercase(),
                fontSizeSp = reader.fontSizeSp,
                lineHeightMultiplier = reader.lineHeightMultiplier,
                paragraphIndex = chapterBody.take(charOffset).count { it == '\n' },
                chapterProgress = within,
            ),
        )
    }

    private fun pauseNarration(fromAudioFocus: Boolean) {
        val state = NarrationRuntime.state.value
        if (state.status !in setOf(NarrationStatus.PLAYING, NarrationStatus.PREPARING)) return
        audioPlayer.pause()
        resumeAfterFocusGain = fromAudioFocus
        publish(state.copy(status = NarrationStatus.PAUSED))
    }

    private fun resumeNarration() {
        val state = NarrationRuntime.state.value
        if (state.status != NarrationStatus.PAUSED) return
        resumeAfterFocusGain = false
        audioPlayer.resume()
        publish(
            state.copy(
                status = if (state.currentText.isBlank()) NarrationStatus.PREPARING else NarrationStatus.PLAYING,
            ),
        )
    }

    private fun setSleepTimer(timer: NarrationSleepTimer?) {
        val state = NarrationRuntime.state.value
        if (!state.isActive) return
        cancelSleepTimer()
        val endsAt = timer?.durationMillis?.let { duration ->
            SystemClock.elapsedRealtime() + duration
        }
        publish(
            state.copy(
                sleepTimer = timer,
                sleepTimerEndsAtElapsedRealtimeMs = endsAt,
            ),
        )
        if (endsAt != null) {
            sleepTimerJob = serviceScope.launch {
                delay((endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                val latest = NarrationRuntime.state.value
                if (latest.isActive && latest.sleepTimerEndsAtElapsedRealtimeMs == endsAt) {
                    stopNarration()
                }
            }
        }
    }

    private fun restartAtCurrentPosition() {
        val state = NarrationRuntime.state.value
        if (!state.isActive || state.bookId == null || state.chapterId == null) return
        chapterNavigationJob?.cancel()
        chapterNavigationJob = null
        publish(state.copy(status = NarrationStatus.PREPARING, currentText = ""))
        startNarration(
            NarrationRequest(
                bookId = state.bookId,
                bookTitle = state.bookTitle,
                bookFormat = state.bookFormat,
                contentVersion = state.contentVersion,
                chapterId = state.chapterId,
                chapterIndex = state.chapterIndex,
                chapterTitle = state.chapterTitle,
                charOffset = state.charOffset,
            ),
        )
    }

    private fun changeChapter(delta: Int) {
        val state = NarrationRuntime.state.value
        val requestedBookId = state.bookId ?: return
        if (!state.isActive) return
        chapterNavigationJob?.cancel()
        chapterNavigationJob = serviceScope.launch {
            val toc = runCatching {
                container.textReader.toc(requestedBookId, state.contentVersion)
            }.getOrNull() ?: return@launch
            val currentIndex = toc.indexOfFirst { it.id == state.chapterId }
                .takeIf { it >= 0 } ?: state.chapterIndex
            val targetIndex = currentIndex + delta
            val target = toc.getOrNull(targetIndex) ?: return@launch
            withContext(Dispatchers.Main.immediate) {
                val latest = NarrationRuntime.state.value
                if (!latest.isActive || latest.bookId != requestedBookId) return@withContext
                val request = NarrationRequest(
                    bookId = requestedBookId,
                    bookTitle = latest.bookTitle,
                    bookFormat = latest.bookFormat,
                    contentVersion = latest.contentVersion,
                    chapterId = target.id,
                    chapterIndex = targetIndex,
                    chapterTitle = target.title,
                    charOffset = 0,
                )
                publish(
                    latest.copy(
                        status = NarrationStatus.PREPARING,
                        chapterId = target.id,
                        chapterIndex = targetIndex,
                        chapterCount = toc.size,
                        chapterTitle = target.title,
                        charOffset = 0,
                        currentTextEndOffset = 0,
                        currentText = "",
                        error = null,
                    ),
                )
                startNarration(request)
            }
        }
    }

    private fun stopNarration() {
        generation++
        cancelSleepTimer()
        chapterNavigationJob?.cancel()
        chapterNavigationJob = null
        audioPlayer.stop()
        playbackJob?.cancel()
        playbackJob = null
        NarrationRuntime.state.value = NarrationRuntime.state.value.copy(
            status = NarrationStatus.IDLE,
            currentTextEndOffset = NarrationRuntime.state.value.charOffset,
            currentText = "",
            sleepTimer = null,
            sleepTimerEndsAtElapsedRealtimeMs = null,
            error = null,
        )
        updateMediaSession()
        finishForegroundPlayback()
        stopSelf()
    }

    private fun finishPlayback(status: NarrationStatus) {
        cancelSleepTimer()
        publish(
            NarrationRuntime.state.value.copy(
                status = status,
                currentText = "",
                sleepTimer = null,
                sleepTimerEndsAtElapsedRealtimeMs = null,
            ),
        )
        finishForegroundPlayback()
        stopSelf()
    }

    private fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
    }

    private fun finishForegroundPlayback() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        mediaSession.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun publish(state: NarrationState) {
        NarrationRuntime.state.value = state
        updateMediaSession()
        if (state.isActive) notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun updateMediaSession() {
        val state = NarrationRuntime.state.value
        val playbackState = when (state.status) {
            NarrationStatus.PLAYING -> PlaybackState.STATE_PLAYING
            NarrationStatus.PAUSED -> PlaybackState.STATE_PAUSED
            NarrationStatus.PREPARING -> PlaybackState.STATE_BUFFERING
            NarrationStatus.ERROR -> PlaybackState.STATE_ERROR
            NarrationStatus.COMPLETED -> PlaybackState.STATE_STOPPED
            NarrationStatus.IDLE -> PlaybackState.STATE_NONE
        }
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_NEXT,
                )
                .setState(playbackState, PlaybackState.PLAYBACK_POSITION_UNKNOWN, state.playbackSpeed)
                .build(),
        )
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.bookTitle.ifBlank { "页架朗读" })
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.chapterTitle)
                .build(),
        )
    }

    private fun buildNotification(): Notification {
        val state = NarrationRuntime.state.value
        val paused = state.status == NarrationStatus.PAUSED
        val toggleAction = if (paused) ACTION_RESUME else ACTION_PAUSE
        val toggleIcon = if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        val toggleLabel = if (paused) "继续" else "暂停"
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(state.bookTitle.ifBlank { "页架朗读" })
            .setContentText(
                when (state.status) {
                    NarrationStatus.PREPARING -> "正在准备${state.voice.displayName}"
                    NarrationStatus.PAUSED -> "已暂停 · ${state.chapterTitle}"
                    else -> state.chapterTitle
                },
            )
            .setContentIntent(contentIntent)
            .setOngoing(state.isActive)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(toggleIcon, toggleLabel, servicePendingIntent(toggleAction, 1))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", servicePendingIntent(ACTION_STOP, 2))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1),
            )
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, NarrationService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun onAudioFocusChanged(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pauseNarration(fromAudioFocus = true)
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeAfterFocusGain) resumeNarration()
        }
    }

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "朗读", NotificationManager.IMPORTANCE_LOW).apply {
                description = "后台朗读的播放控制"
                setSound(null, null)
            },
        )
    }

    private fun logPreparedSegment(prepared: PreparedTtsSegment, queueReady: Int) {
        if (!BuildConfig.DEBUG) return
        val audioDuration = prepared.audio.durationMs
        val generationTime = prepared.generationTimeMs
        val realTimeFactor = if (audioDuration != null && audioDuration > 0 && generationTime != null) {
            "%.3f".format(generationTime.toDouble() / audioDuration)
        } else {
            "cache"
        }
        Log.d(
            TAG,
            "TTS generated: segment=${prepared.audio.id} synthesisSpeed=${SherpaMatchaEngine.SYNTHESIS_SPEED} " +
                "playbackSpeed=${NarrationRuntime.state.value.playbackSpeed} generationTimeMs=$generationTime " +
                "audioDurationMs=$audioDuration rtf=$realTimeFactor queueReady=$queueReady " +
                "cacheHit=${prepared.cacheHit}",
        )
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    override fun onDestroy() {
        generation++
        cancelSleepTimer()
        chapterNavigationJob?.cancel()
        audioPlayer.stop()
        playbackJob?.cancel()
        audioManager.abandonAudioFocusRequest(focusRequest)
        audioPlayer.close()
        mediaSession.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.bookshelf.narration.START"
        const val ACTION_PAUSE = "com.example.bookshelf.narration.PAUSE"
        const val ACTION_RESUME = "com.example.bookshelf.narration.RESUME"
        const val ACTION_STOP = "com.example.bookshelf.narration.STOP"
        const val ACTION_PREVIOUS_CHAPTER = "com.example.bookshelf.narration.PREVIOUS_CHAPTER"
        const val ACTION_NEXT_CHAPTER = "com.example.bookshelf.narration.NEXT_CHAPTER"
        const val ACTION_SET_VOICE = "com.example.bookshelf.narration.SET_VOICE"
        const val ACTION_SET_PLAYBACK_SPEED = "com.example.bookshelf.narration.SET_PLAYBACK_SPEED"
        const val ACTION_SET_SLEEP_TIMER = "com.example.bookshelf.narration.SET_SLEEP_TIMER"
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_BOOK_TITLE = "book_title"
        const val EXTRA_BOOK_FORMAT = "book_format"
        const val EXTRA_CONTENT_VERSION = "content_version"
        const val EXTRA_CHAPTER_ID = "chapter_id"
        const val EXTRA_CHAPTER_INDEX = "chapter_index"
        const val EXTRA_CHAPTER_TITLE = "chapter_title"
        const val EXTRA_CHAR_OFFSET = "char_offset"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_PLAYBACK_SPEED = "playback_speed"
        const val EXTRA_SLEEP_TIMER = "sleep_timer"
        private const val CHANNEL_ID = "narration_playback"
        private const val NOTIFICATION_ID = 4102
        private const val TAG = "PageShelfTTS"
    }
}

private fun Intent.toNarrationRequest(): NarrationRequest? {
    val bookId = getStringExtra(NarrationService.EXTRA_BOOK_ID) ?: return null
    val chapterId = getStringExtra(NarrationService.EXTRA_CHAPTER_ID) ?: return null
    return NarrationRequest(
        bookId = bookId,
        bookTitle = getStringExtra(NarrationService.EXTRA_BOOK_TITLE).orEmpty(),
        bookFormat = getStringExtra(NarrationService.EXTRA_BOOK_FORMAT).orEmpty(),
        contentVersion = getStringExtra(NarrationService.EXTRA_CONTENT_VERSION),
        chapterId = chapterId,
        chapterIndex = getIntExtra(NarrationService.EXTRA_CHAPTER_INDEX, 0),
        chapterTitle = getStringExtra(NarrationService.EXTRA_CHAPTER_TITLE).orEmpty(),
        charOffset = getIntExtra(NarrationService.EXTRA_CHAR_OFFSET, 0),
    )
}
