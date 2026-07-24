package com.example.bookshelf.narration

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class NarrationAudioPlayer(
    context: Context,
    private val onAudioBecomingNoisy: () -> Unit,
) : AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val player = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            false,
        )
        setHandleAudioBecomingNoisy(true)
    }
    private var activeCompletion: CompletableDeferred<Unit>? = null
    private var closed = false

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) completeCurrent()
            }

            override fun onPlayerError(error: PlaybackException) {
                failCurrent(IllegalStateException("朗读音频播放失败：${error.errorCodeName}", error))
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
                    onAudioBecomingNoisy()
                }
            }
        })
    }

    suspend fun play(file: File, startWhenReady: Boolean) {
        check(file.isFile) { "朗读音频缓存已被清理" }
        val completion = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main.immediate) {
            check(!closed) { "朗读播放器已经释放" }
            activeCompletion?.cancel()
            activeCompletion = completion
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            player.playWhenReady = startWhenReady
        }
        try {
            completion.await()
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (activeCompletion === completion) {
                    activeCompletion = null
                    player.stop()
                }
            }
        }
    }

    fun setPlaybackSpeed(speed: Float) = onMain {
        if (!closed) player.playbackParameters = PlaybackParameters(speed, PITCH)
    }

    fun pause() = onMain {
        if (!closed) player.pause()
    }

    fun resume() = onMain {
        if (!closed) player.play()
    }

    fun stop() = onMain {
        if (!closed) {
            activeCompletion?.cancel()
            activeCompletion = null
            player.stop()
        }
    }

    fun currentPositionMs(): Long = if (
        Looper.myLooper() == Looper.getMainLooper() && !closed
    ) {
        player.currentPosition
    } else {
        -1L
    }

    override fun close() = onMain {
        if (closed) return@onMain
        closed = true
        activeCompletion?.cancel()
        activeCompletion = null
        player.release()
    }

    private fun completeCurrent() {
        activeCompletion?.complete(Unit)
        activeCompletion = null
    }

    private fun failCurrent(error: Throwable) {
        activeCompletion?.completeExceptionally(error)
        activeCompletion = null
    }

    private inline fun onMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post { action() }
    }

    private companion object {
        const val PITCH = 1.0f
    }
}
