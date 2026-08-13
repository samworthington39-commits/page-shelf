package com.example.bookshelf.data.repository

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.bookshelf.data.local.ProgressSyncDao
import com.example.bookshelf.data.local.ProgressSyncEntity
import com.example.bookshelf.data.local.ReadingProgressDao
import com.example.bookshelf.data.local.ReadingProgressEntity
import com.example.bookshelf.data.remote.BooksApi
import com.example.bookshelf.data.remote.ProgressRequest
import com.example.bookshelf.data.remote.ProgressResponseDto
import com.example.bookshelf.data.remote.TextProgressRequest
import com.example.bookshelf.data.settings.AppSettingsStore
import com.example.bookshelf.domain.ProgressResolution
import com.example.bookshelf.domain.ReadingProgress
import com.example.bookshelf.domain.calculateProgression
import com.example.bookshelf.domain.hasMeaningfulProgressConflict
import com.example.bookshelf.worker.ProgressSyncWorker
import retrofit2.HttpException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ProgressRepository(
    context: Context,
    private val api: BooksApi,
    private val progressDao: ReadingProgressDao,
    private val queueDao: ProgressSyncDao,
    val deviceId: String,
    private val settings: AppSettingsStore,
) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private var syncJob: Job? = null

    fun observeAll(): Flow<List<ReadingProgress>> = progressDao.observeAll().map { list ->
        list.map(ReadingProgressEntity::toDomain)
    }

    suspend fun local(bookId: String): ReadingProgress? = progressDao.byBook(bookId)?.toDomain()

    suspend fun deleteLocal(bookId: String) {
        progressDao.delete(bookId)
        queueDao.delete(bookId)
    }

    suspend fun restore(bookId: String, format: String, contentVersion: String?): ProgressResolution {
        val local = progressDao.byBook(bookId)?.toDomain()
        if (!settings.state.value.progressSyncEnabled) {
            return ProgressResolution(local, null, local, false)
        }
        val remote = runCatching { api.latestProgress(bookId) }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.toDomain(format, contentVersion)
        val conflict = local != null && remote != null && hasMeaningfulProgressConflict(local, remote)
        val selected = when {
            conflict -> local
            local == null -> remote
            remote == null -> local
            remote.updatedAtEpochMs > local.updatedAtEpochMs -> remote
            else -> local
        }
        if (!conflict && selected != null) progressDao.upsert(selected.toEntity())
        return ProgressResolution(local, remote, selected, conflict)
    }

    suspend fun savePdf(
        bookId: String,
        pageIndex: Int,
        pageCount: Int,
        pageOffset: Double,
        viewMode: String,
        contentVersion: String?,
    ) {
        val safeIndex = pageIndex.coerceIn(0, pageCount - 1)
        save(
            ReadingProgress(
                bookId = bookId,
                bookFormat = "pdf",
                pageIndex = safeIndex,
                pdfPage = safeIndex,
                pdfPageOffset = pageOffset.coerceIn(0.0, 1.0),
                pageCount = pageCount,
                progression = calculateProgression(safeIndex, pageCount),
                updatedAtEpochMs = System.currentTimeMillis(),
                deviceId = deviceId,
                contentVersion = contentVersion,
                viewMode = viewMode,
            )
        )
    }

    suspend fun saveText(progress: ReadingProgress) {
        require(progress.bookFormat in setOf("txt", "epub", "mobi"))
        save(progress.copy(updatedAtEpochMs = System.currentTimeMillis(), deviceId = deviceId))
    }

    suspend fun resolve(resolution: ProgressResolution, useLocal: Boolean): ReadingProgress? {
        val selected = if (useLocal) resolution.local else resolution.remote
        selected ?: return null
        progressDao.upsert(selected.toEntity())
        if (useLocal && settings.state.value.progressSyncEnabled) enqueue(selected.bookId)
        return selected
    }

    suspend fun applySyncPreference(enabled: Boolean) {
        if (enabled) {
            progressDao.all().forEach { progress -> upsertSyncEntry(progress.bookId) }
            scheduleSync(0, ExistingWorkPolicy.REPLACE)
            syncNow()
        } else {
            queueDao.clear()
            syncJob?.cancel()
            workManager.cancelUniqueWork(PROGRESS_SYNC_WORK_NAME)
        }
    }

    private suspend fun save(progress: ReadingProgress) {
        progressDao.upsert(progress.toEntity())
        // Reading and narration are offline-first. A dead server must never block page turns or
        // audio playback while OkHttp waits for its connect timeout.
        if (settings.state.value.progressSyncEnabled) enqueue(progress.bookId)
        else queueDao.delete(progress.bookId)
    }

    private suspend fun upload(progress: ReadingProgress) {
        if (progress.bookFormat == "pdf") {
            api.saveProgress(
                progress.bookId,
                deviceId,
                ProgressRequest(
                    pageIndex = progress.pdfPage,
                    pageCount = requireNotNull(progress.pageCount),
                    progression = progress.progression,
                    locatorJson = progress.locator(),
                ),
            )
        } else {
            api.saveTextProgress(
                progress.bookId,
                deviceId,
                TextProgressRequest(progress.progression, progress.locator()),
            )
        }
    }

    private suspend fun enqueue(bookId: String) {
        upsertSyncEntry(bookId)
        // REPLACE: a fresh save must cancel any stale delayed retry and run right away.
        // ExistingWorkPolicy.KEEP used to drop these immediate requests whenever an
        // earlier failure had queued a long backoff, stalling sync for up to 6 hours.
        scheduleSync(0, ExistingWorkPolicy.REPLACE)
        syncNow()
    }

    private suspend fun upsertSyncEntry(bookId: String) {
        // A fresh save resets the backoff so the next attempt happens immediately instead
        // of inheriting an old retry window that could be hours away.
        queueDao.upsert(
            ProgressSyncEntity(
                bookId = bookId,
                attempts = 0,
                nextAttemptEpochMs = System.currentTimeMillis(),
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        )
    }

    suspend fun syncPending() = syncMutex.withLock {
        if (!settings.state.value.progressSyncEnabled) {
            queueDao.clear()
            return@withLock
        }
        queueDao.ready(System.currentTimeMillis()).forEach { item ->
            val progress = progressDao.byBook(item.bookId)?.toDomain()
            if (progress == null) {
                queueDao.delete(item.bookId)
                return@forEach
            }
            runCatching { upload(progress) }
                .onSuccess {
                    queueDao.delete(item.bookId)
                    Log.i(SYNC_LOG_TAG, "uploaded ${item.bookId}")
                }
                .onFailure {
                    // A deliberate cancellation (e.g. a newer save replaced this run) must
                    // propagate instead of being counted as an upload failure.
                    if (it is CancellationException) throw it
                    val code = (it as? HttpException)?.code()
                    if (code != null && code in PERMANENT_HTTP_CODES) {
                        // 4xx like 404 (book removed from server) or 409 (PDF changed) will
                        // never succeed; retrying them only clogs the queue with backoff.
                        // 401 is intentionally retryable: it means the session was revoked and
                        // the queue should survive until the user signs in again.
                        queueDao.delete(item.bookId)
                        Log.w(SYNC_LOG_TAG, "dropped ${item.bookId} permanent HTTP $code")
                    } else {
                        val attempts = item.attempts + 1
                        val delayMillis = (15_000L * (1L shl attempts.coerceAtMost(10))).coerceAtMost(6 * 60 * 60 * 1000L)
                        queueDao.upsert(
                            item.copy(
                                attempts = attempts,
                                nextAttemptEpochMs = System.currentTimeMillis() + delayMillis,
                                updatedAtEpochMs = System.currentTimeMillis(),
                            )
                        )
                        Log.w(SYNC_LOG_TAG, "upload failed ${item.bookId} attempts=$attempts ${it.javaClass.simpleName}" + (code?.let { " http=$it" } ?: ""))
                        scheduleSync(delayMillis)
                    }
                }
        }
    }

    /** Best-effort, non-blocking flush used after saves and when the app returns to the
     *  foreground. Failures are handled by the queue backoff and WorkManager, never here,
     *  so a dead server can never block page turns or narration. */
    fun syncNow() {
        if (!settings.state.value.progressSyncEnabled) return
        syncJob?.cancel()
        syncJob = syncScope.launch {
            delay(SYNC_COALESCE_MILLIS)
            try {
                syncPending()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(SYNC_LOG_TAG, "syncNow failed: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun scheduleSync(
        delayMillis: Long,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        val request = OneTimeWorkRequestBuilder<ProgressSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(PROGRESS_SYNC_WORK_NAME, policy, request)
    }

    private companion object {
        const val PROGRESS_SYNC_WORK_NAME = "progress-sync"
        const val SYNC_COALESCE_MILLIS = 150L
        const val SYNC_LOG_TAG = "PageShelfSync"
        // Server-side rejections that will never succeed on retry.
        val PERMANENT_HTTP_CODES = setOf(400, 403, 404, 409, 422)
    }
}

private fun ReadingProgress.locator(): Map<String, Any> = if (bookFormat == "pdf") {
    mapOf(
        "type" to "pdf",
        "page_index" to pdfPage,
        "page" to pdfPage + 1,
        "page_offset" to pdfPageOffset,
        "view" to viewMode,
        "content_version" to (contentVersion ?: ""),
    )
} else {
    mapOf(
        "type" to "text",
        "book_format" to bookFormat,
        "chapter_id" to (chapterId ?: ""),
        "chapter_index" to chapterIndex,
        "chapter_title" to (chapterTitle ?: ""),
        "chapter_progress" to chapterProgress,
        "char_offset" to textOffset,
        "paragraph_index" to paragraphIndex,
        "view" to viewMode,
        "content_version" to (contentVersion ?: ""),
    )
}

private fun ProgressResponseDto.toDomain(formatHint: String, contentVersionHint: String?): ReadingProgress {
    val locator = locatorJson.orEmpty()
    val format = (locator["book_format"] as? String)
        ?: if (locator["type"] == "pdf") "pdf" else formatHint
    return ReadingProgress(
        bookId = bookId,
        bookFormat = format,
        chapterId = locator["chapter_id"] as? String,
        chapterIndex = (locator["chapter_index"] as? Number)?.toInt() ?: 0,
        chapterTitle = locator["chapter_title"] as? String,
        chapterProgress = (locator["chapter_progress"] as? Number)?.toDouble() ?: 0.0,
        textOffset = (locator["char_offset"] as? Number)?.toInt() ?: 0,
        paragraphIndex = (locator["paragraph_index"] as? Number)?.toInt() ?: 0,
        pageIndex = pageIndex ?: 0,
        pdfPage = (locator["page_index"] as? Number)?.toInt() ?: pageIndex ?: 0,
        pdfPageOffset = (locator["page_offset"] as? Number)?.toDouble() ?: 0.0,
        pageCount = pageCount,
        progression = progression,
        updatedAtEpochMs = runCatching { Instant.parse(updatedAt).toEpochMilli() }.getOrDefault(System.currentTimeMillis()),
        deviceId = deviceId,
        contentVersion = (locator["content_version"] as? String)?.ifBlank { null } ?: contentVersionHint,
        viewMode = (locator["view"] as? String) ?: "paged",
    )
}

fun ReadingProgress.toEntity() = ReadingProgressEntity(
    bookId, bookFormat, chapterId, chapterIndex, chapterTitle, chapterProgress, textOffset,
    paragraphIndex, pageIndex, pdfPage, pdfPageOffset, pageCount, progression, updatedAtEpochMs,
    deviceId, contentVersion, viewMode,
)

fun ReadingProgressEntity.toDomain() = ReadingProgress(
    bookId, bookFormat, chapterId, chapterIndex, chapterTitle, chapterProgress, textOffset,
    paragraphIndex, pageIndex, pdfPage, pdfPageOffset, pageCount, progression, updatedAtEpochMs,
    deviceId, contentVersion, viewMode,
)
