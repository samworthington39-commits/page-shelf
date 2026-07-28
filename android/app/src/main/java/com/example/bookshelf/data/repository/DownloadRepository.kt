package com.example.bookshelf.data.repository

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.bookshelf.data.local.ChapterCacheDao
import com.example.bookshelf.data.local.DownloadDao
import com.example.bookshelf.data.local.DownloadEntity
import com.example.bookshelf.domain.Book
import com.example.bookshelf.domain.DownloadState
import com.example.bookshelf.domain.DownloadStatus
import com.example.bookshelf.worker.BookDownloadWorker
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DownloadRepository(
    context: Context,
    private val dao: DownloadDao,
    private val chapterCache: ChapterCacheDao,
) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    fun observe(bookId: String): Flow<DownloadState> = dao.observe(bookId).map { entity ->
        entity?.toDomain() ?: DownloadState(bookId, DownloadStatus.NOT_DOWNLOADED)
    }

    fun observeAll(): Flow<List<DownloadState>> = dao.observeAll().map { list -> list.map(DownloadEntity::toDomain) }

    suspend fun state(bookId: String): DownloadState =
        dao.byId(bookId)?.toDomain() ?: DownloadState(bookId, DownloadStatus.NOT_DOWNLOADED)

    suspend fun enqueue(book: Book, permanent: Boolean = true) {
        require(book.capabilities.offlineDownload) { "该格式不支持下载" }
        val existing = dao.byId(book.id)
        if (existing != null && existing.status in setOf(DownloadStatus.DOWNLOADED.name, DownloadStatus.OUTDATED.name) &&
            existing.localPath?.let(::File)?.isFile == true &&
            existing.fingerprint == book.fingerprint
        ) {
            if (permanent && !existing.isPermanent) {
                dao.upsert(existing.copy(isPermanent = true, updatedAtEpochMs = System.currentTimeMillis()))
                chapterCache.markPermanent(book.id)
            }
            return
        }
        dao.upsert(
            DownloadEntity(
                bookId = book.id,
                status = DownloadStatus.QUEUED.name,
                format = book.format,
                bytesDownloaded = existing?.bytesDownloaded ?: 0,
                totalBytes = book.fileSize,
                localPath = existing?.localPath,
                fingerprint = book.fingerprint,
                error = null,
                isPermanent = permanent || existing?.isPermanent == true,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        )
        val input = Data.Builder()
            .putString(BookDownloadWorker.KEY_BOOK_ID, book.id)
            .putString(BookDownloadWorker.KEY_FORMAT, book.format)
            .putString(BookDownloadWorker.KEY_CONTENT_VERSION, book.fingerprint)
            .putString(BookDownloadWorker.KEY_FILE_FINGERPRINT, book.fileFingerprint)
            .putLong(BookDownloadWorker.KEY_TOTAL_BYTES, book.fileSize)
            .putBoolean(BookDownloadWorker.KEY_PERMANENT, permanent || existing?.isPermanent == true)
            .build()
        val request = OneTimeWorkRequestBuilder<BookDownloadWorker>()
            .setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(workName(book.id))
            .build()
        workManager.enqueueUniqueWork(workName(book.id), ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun ensureReadableCopy(book: Book) = enqueue(book, permanent = false)

    suspend fun pause(bookId: String) {
        workManager.cancelUniqueWork(workName(bookId))
        dao.byId(bookId)?.let {
            dao.upsert(it.copy(status = DownloadStatus.PAUSED.name, updatedAtEpochMs = System.currentTimeMillis()))
        }
    }

    suspend fun delete(bookId: String) {
        workManager.cancelUniqueWork(workName(bookId))
        dao.byId(bookId)?.let { entity -> entity.localPath?.let(::File)?.delete() }
        downloadsDirectory(appContext).listFiles()
            ?.filter { it.name.startsWith("$bookId.") }
            ?.forEach(File::delete)
        chapterCache.deleteBook(bookId)
        dao.delete(bookId)
    }

    suspend fun redownload(book: Book, permanent: Boolean = true) {
        delete(book.id)
        enqueue(book, permanent)
    }

    suspend fun clearTemporary(): Long {
        var freed = 0L
        dao.temporary().forEach { entity ->
            workManager.cancelUniqueWork(workName(entity.bookId))
            entity.localPath?.let(::File)?.let { file ->
                freed += file.length()
                file.delete()
            }
            dao.delete(entity.bookId)
        }
        chapterCache.clearTemporary()
        return freed
    }

    companion object {
        fun workName(bookId: String) = "book-download-$bookId"
        fun downloadsDirectory(context: Context) = File(context.filesDir, "downloads").apply { mkdirs() }
        fun partialFile(context: Context, bookId: String, format: String) =
            File(downloadsDirectory(context), "$bookId.${safeExtension(format)}.part")
        fun finalFile(context: Context, bookId: String, format: String) =
            File(downloadsDirectory(context), "$bookId.${safeExtension(format)}")

        private fun safeExtension(format: String): String = format.lowercase().takeIf { it in setOf("pdf", "txt", "epub", "mobi") } ?: "book"
    }
}

fun DownloadEntity.toDomain() = DownloadState(
    bookId = bookId,
    status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.FAILED),
    format = format,
    bytesDownloaded = bytesDownloaded,
    totalBytes = totalBytes,
    localPath = localPath,
    fingerprint = fingerprint,
    error = error,
    isPermanent = isPermanent,
)
