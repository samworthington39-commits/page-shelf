package com.example.bookshelf.narration

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal enum class SegmentState {
    WAITING,
    GENERATING,
    READY,
    PLAYING,
    COMPLETED,
    FAILED,
}

internal data class NarrationSourceSegment(
    val id: String,
    val bookId: String,
    val chapterId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val chapterBody: String,
    val start: Int,
    val end: Int,
    val text: String,
    val isLastInChapter: Boolean,
)

internal data class TtsAudioSegment(
    val id: String,
    val chapterId: String,
    val text: String,
    val audioPath: String?,
    val state: SegmentState,
    val durationMs: Long?,
)

internal data class PreparedTtsSegment(
    val source: NarrationSourceSegment,
    val audio: TtsAudioSegment,
    val file: File,
    val generationTimeMs: Long?,
    val cacheHit: Boolean,
)

internal data class TtsQueueHandle(
    val segments: ReceiveChannel<PreparedTtsSegment>,
    val producer: Job,
)

/** Sequentially generates normal-speed WAV files and buffers two segments ahead. */
internal class TtsAudioQueue(
    private val cache: TtsAudioCache,
    private val generator: NarrationTtsEngine,
) {
    private val readyCount = AtomicInteger(0)

    fun start(
        scope: CoroutineScope,
        source: Flow<NarrationSourceSegment>,
        voice: NarrationVoice,
    ): TtsQueueHandle {
        val channel = Channel<PreparedTtsSegment>(capacity = PREFETCH_SEGMENTS)
        val producer = scope.launch {
            try {
                source.collect { segment ->
                    readyCount.incrementAndGet()
                    try {
                        channel.send(prepare(segment, voice))
                    } catch (error: Throwable) {
                        readyCount.decrementAndGet()
                        throw error
                    }
                }
                channel.close()
            } catch (error: Throwable) {
                channel.close(error)
            }
        }
        return TtsQueueHandle(channel, producer)
    }

    fun markDequeued(): Int = readyCount.updateAndGet { count -> (count - 1).coerceAtLeast(0) }

    suspend fun ensureAvailable(
        prepared: PreparedTtsSegment,
        voice: NarrationVoice,
    ): PreparedTtsSegment = if (prepared.file.isFile) prepared else prepare(prepared.source, voice)

    private suspend fun prepare(
        segment: NarrationSourceSegment,
        voice: NarrationVoice,
    ): PreparedTtsSegment {
        val cached = cache.getOrGenerate(
            key = TtsCacheKey(
                bookId = segment.bookId,
                chapterId = segment.chapterId,
                text = segment.text,
                voice = voice,
            ),
            generator = generator,
        )
        return PreparedTtsSegment(
            source = segment,
            audio = TtsAudioSegment(
                id = segment.id,
                chapterId = segment.chapterId,
                text = segment.text,
                audioPath = cached.file.absolutePath,
                state = SegmentState.READY,
                durationMs = cached.durationMs,
            ),
            file = cached.file,
            generationTimeMs = cached.generationTimeMs,
            cacheHit = cached.cacheHit,
        )
    }

    private companion object {
        const val PREFETCH_SEGMENTS = 2
    }
}
