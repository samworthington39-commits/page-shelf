package com.example.bookshelf.data.repository

import com.example.bookshelf.data.local.ChapterCacheDao
import com.example.bookshelf.data.local.ChapterCacheEntity
import com.example.bookshelf.data.remote.BooksApi
import com.example.bookshelf.domain.ProgressResolution
import com.example.bookshelf.domain.ReadingProgress
import com.example.bookshelf.domain.TextChapter
import com.example.bookshelf.domain.TextChapterSummary
import com.example.bookshelf.domain.TextReadingPosition
import com.example.bookshelf.domain.chapterWindowLoadOrder
import com.example.bookshelf.domain.chapterWindowRange
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

data class TextRestore(val position: TextReadingPosition?, val resolution: ProgressResolution)

data class ChapterPreloadResult(
    val chapters: Map<String, TextChapter>,
    val failures: Map<String, Throwable>,
)

private data class TocCache(
    val contentVersion: String?,
    val items: List<TextChapterSummary>,
)

class TextReaderRepository(
    private val api: BooksApi,
    private val cacheDao: ChapterCacheDao,
    private val progressRepository: ProgressRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val memory = LinkedHashMap<String, LinkedHashMap<String, TextChapter>>(2, 0.75f, true)
    private val inFlight = mutableMapOf<Pair<String, String>, Deferred<TextChapter>>()
    private val tocMemory = LinkedHashMap<String, TocCache>(8, 0.75f, true)

    suspend fun toc(bookId: String, contentVersion: String?): List<TextChapterSummary> {
        mutex.withLock {
            tocMemory[bookId]
                ?.takeIf { it.contentVersion == null || contentVersion == null || it.contentVersion == contentVersion }
                ?.let { return it.items }
        }
        val items = runCatching {
            api.toc(bookId).items.map { TextChapterSummary(it.id, it.title, it.position) }
        }.getOrElse { error ->
            cacheDao.chapters(bookId)
                .map { TextChapterSummary(it.chapterId, it.title, it.position) }
                .ifEmpty { throw error }
        }
        mutex.withLock {
            tocMemory[bookId] = TocCache(contentVersion, items)
            while (tocMemory.size > MAX_TOC_MEMORY_BOOKS) {
                tocMemory.remove(tocMemory.entries.first().key)
            }
        }
        return items
    }

    suspend fun chapter(bookId: String, chapterId: String, contentVersion: String?): TextChapter {
        mutex.withLock { memory[bookId]?.get(chapterId) }?.let { return it }
        val key = bookId to chapterId
        val deferred = mutex.withLock {
            inFlight[key] ?: scope.async { loadChapter(bookId, chapterId, contentVersion) }.also { created ->
                inFlight[key] = created
                created.invokeOnCompletion {
                    scope.launch {
                        mutex.withLock {
                            if (inFlight[key] === created) inFlight.remove(key)
                        }
                    }
                }
            }
        }
        return deferred.await().also { chapter ->
            mutex.withLock {
                memory.getOrPut(bookId, ::LinkedHashMap)[chapter.id] = chapter
                while (memory.size > MAX_CHAPTER_MEMORY_BOOKS) {
                    memory.remove(memory.entries.first().key)
                }
            }
        }
    }

    suspend fun prefetchAround(
        bookId: String,
        toc: List<TextChapterSummary>,
        currentIndex: Int,
        contentVersion: String?,
        requestTimeoutMillis: Long = CHAPTER_PRELOAD_TIMEOUT_MILLIS,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): ChapterPreloadResult {
        val range = chapterWindowRange(currentIndex, toc.size)
        val keep = range.map { toc[it].id }.toSet()
        mutex.withLock {
            memory[bookId]?.keys?.filterNot(keep::contains)?.forEach { memory[bookId]?.remove(it) }
            inFlight.filterKeys { (cachedBook, cachedChapter) -> cachedBook == bookId && cachedChapter !in keep }
                .values
                .forEach { it.cancel() }
        }
        val summaries = chapterWindowLoadOrder(currentIndex, toc.size).map(toc::get)
        val results = loadConcurrentlyWithTimeout(
            keys = summaries,
            timeoutMillis = requestTimeoutMillis,
            maxConcurrency = MAX_CONCURRENT_CHAPTER_LOADS,
            onProgress = onProgress,
        ) { summary -> chapter(bookId, summary.id, contentVersion) }
        val preloaded = ChapterPreloadResult(
            chapters = results.mapNotNull { (summary, result) ->
                result.getOrNull()?.let { summary.id to it }
            }.toMap(),
            failures = results.mapNotNull { (summary, result) ->
                result.exceptionOrNull()?.let { summary.id to it }
            }.toMap(),
        )
        // All ±5 chapters are persisted by loadChapter. Keep only the hottest ±2 bodies in RAM;
        // the rest can be restored quickly from Room without accumulating multi-book text heaps.
        val hotIds = chapterWindowRange(currentIndex, toc.size, radius = HOT_MEMORY_RADIUS)
            .map { toc[it].id }
            .toSet()
        mutex.withLock {
            memory[bookId]?.keys?.filterNot(hotIds::contains)?.forEach { memory[bookId]?.remove(it) }
        }
        return preloaded
    }

    suspend fun restore(bookId: String, format: String, contentVersion: String?): TextRestore {
        val resolution = progressRepository.restore(bookId, format, contentVersion)
        return TextRestore(resolution.selected?.toTextPosition(), resolution)
    }

    suspend fun resolve(resolution: ProgressResolution, useLocal: Boolean): TextReadingPosition? =
        progressRepository.resolve(resolution, useLocal)?.toTextPosition()

    suspend fun save(
        bookId: String,
        format: String,
        contentVersion: String?,
        chapterTitle: String,
        position: TextReadingPosition,
    ) {
        progressRepository.saveText(
            ReadingProgress(
                bookId = bookId,
                bookFormat = format,
                chapterId = position.chapterId,
                chapterIndex = position.chapterIndex,
                chapterTitle = chapterTitle,
                chapterProgress = position.chapterProgress,
                textOffset = position.charOffset,
                paragraphIndex = position.paragraphIndex,
                progression = position.progression,
                updatedAtEpochMs = System.currentTimeMillis(),
                deviceId = progressRepository.deviceId,
                contentVersion = contentVersion,
                viewMode = position.viewMode,
            )
        )
    }

    private suspend fun loadChapter(bookId: String, chapterId: String, contentVersion: String?): TextChapter {
        val cached = cacheDao.chapter(bookId, chapterId)
        if (cached != null && (cached.contentVersion == null || contentVersion == null || cached.contentVersion == contentVersion)) {
            return cached.toDomain()
        }
        return runCatching { api.chapter(bookId, chapterId) }
            .map { dto -> TextChapter(dto.id, dto.bookId, dto.title, dto.position, dto.body) }
            .getOrElse { error -> cached?.toDomain() ?: throw error }
            .also { chapter ->
                cacheDao.upsert(
                    ChapterCacheEntity(
                        bookId = bookId,
                        chapterId = chapter.id,
                        title = chapter.title,
                        position = chapter.position,
                        body = chapter.body,
                        contentVersion = contentVersion,
                        isPermanent = cached?.isPermanent == true,
                        lastAccessEpochMs = System.currentTimeMillis(),
                    )
                )
            }
    }
}

internal const val CHAPTER_PRELOAD_TIMEOUT_MILLIS = 15_000L
internal const val MAX_CONCURRENT_CHAPTER_LOADS = 3
private const val MAX_TOC_MEMORY_BOOKS = 8
private const val MAX_CHAPTER_MEMORY_BOOKS = 2
private const val HOT_MEMORY_RADIUS = 2

internal suspend fun <K, V> loadConcurrentlyWithTimeout(
    keys: List<K>,
    timeoutMillis: Long,
    maxConcurrency: Int = MAX_CONCURRENT_CHAPTER_LOADS,
    onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    loader: suspend (K) -> V,
): Map<K, Result<V>> = supervisorScope {
    require(maxConcurrency > 0) { "maxConcurrency must be positive" }
    val permits = Semaphore(maxConcurrency)
    val completed = AtomicInteger(0)
    keys.map { key ->
        async {
            val result = permits.withPermit {
                try {
                    Result.success(withTimeout(timeoutMillis) { loader(key) })
                } catch (error: TimeoutCancellationException) {
                    Result.failure(error)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Result.failure(error)
                }
            }
            onProgress(completed.incrementAndGet(), keys.size)
            key to result
        }
    }.awaitAll().toMap(LinkedHashMap())
}

private fun ChapterCacheEntity.toDomain() = TextChapter(chapterId, bookId, title, position, body)

private fun ReadingProgress.toTextPosition(): TextReadingPosition? {
    val id = chapterId ?: return null
    return TextReadingPosition(
        chapterId = id,
        chapterIndex = chapterIndex,
        charOffset = textOffset,
        progression = progression,
        viewMode = viewMode,
        paragraphIndex = paragraphIndex,
        chapterProgress = chapterProgress,
    )
}
