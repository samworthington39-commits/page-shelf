package com.example.bookshelf.worker

import android.content.Context
import android.os.storage.StorageManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.bookshelf.PageShelfApplication
import com.example.bookshelf.data.local.ChapterCacheEntity
import com.example.bookshelf.data.local.DownloadEntity
import com.example.bookshelf.data.repository.DownloadRepository
import com.example.bookshelf.domain.DownloadStatus
import java.io.RandomAccessFile
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class BookDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return@withContext Result.failure()
        val format = inputData.getString(KEY_FORMAT) ?: return@withContext Result.failure()
        val contentVersion = inputData.getString(KEY_CONTENT_VERSION)
            ?: inputData.getString(LEGACY_KEY_FINGERPRINT)
            ?: return@withContext Result.failure()
        val fileFingerprint = inputData.getString(KEY_FILE_FINGERPRINT)
            ?: contentVersion.substringBefore(':')
        val permanent = inputData.getBoolean(KEY_PERMANENT, true)
        val expectedTotal = inputData.getLong(KEY_TOTAL_BYTES, 0L)
        val container = (applicationContext as PageShelfApplication).container
        if (container.credentials.bearerToken() == null) {
            runCatching { container.auth.autoLogin() }.getOrElse { error ->
                return@withContext Result.retry()
            }
        }
        val dao = container.database.downloadDao()
        val readablePath = dao.byId(bookId)?.localPath?.takeIf { java.io.File(it).isFile }
        val partial = DownloadRepository.partialFile(applicationContext, bookId, format)
        val destination = DownloadRepository.finalFile(applicationContext, bookId, format)

        try {
            var offset = partial.takeIf { it.exists() }?.length() ?: 0L
            val remaining = (expectedTotal - offset).coerceAtLeast(0L)
            val storageManager = applicationContext.getSystemService(StorageManager::class.java)
            val allocatable = partial.parentFile?.let { directory ->
                runCatching { storageManager.getAllocatableBytes(storageManager.getUuidForPath(directory)) }.getOrNull()
            }
            if (remaining > 0 && allocatable?.let { it < remaining + 10L * 1024 * 1024 } == true) {
                error("手机存储空间不足，请清理空间后重试")
            }
            dao.upsert(
                status(bookId, format, DownloadStatus.DOWNLOADING, offset, expectedTotal, contentVersion, permanent)
                    .copy(localPath = readablePath)
            )
            var response = container.api.file(
                bookId,
                range = if (offset > 0) "bytes=$offset-" else null,
                ifRange = if (offset > 0) "\"$fileFingerprint\"" else null,
            )
            if (response.code() == 416) {
                partial.delete()
                offset = 0
                response = container.api.file(bookId)
            }
            if (!response.isSuccessful) error("下载失败：HTTP ${response.code()}")
            if (offset > 0 && response.code() == 200) {
                partial.delete()
                offset = 0
            }
            val body = response.body() ?: error("服务器没有返回文件内容")
            val total = expectedTotal.takeIf { it > 0 } ?: (offset + body.contentLength()).coerceAtLeast(0)
            RandomAccessFile(partial, "rw").use { target ->
                target.seek(offset)
                body.byteStream().use { source ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = offset
                    var lastReported = offset
                    var lastReportAt = System.currentTimeMillis()
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        target.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (downloaded - lastReported >= 256 * 1024 || now - lastReportAt >= 500) {
                            setProgress(workDataOf("downloaded" to downloaded, "total" to total))
                            dao.upsert(
                                status(bookId, format, DownloadStatus.DOWNLOADING, downloaded, total, contentVersion, permanent)
                                    .copy(localPath = readablePath)
                            )
                            lastReported = downloaded
                            lastReportAt = now
                        }
                    }
                }
            }
            if (expectedTotal > 0 && partial.length() != expectedTotal) {
                error("文件不完整：${partial.length()} / $expectedTotal bytes")
            }
            if (!sha256(partial).equals(fileFingerprint, ignoreCase = true)) {
                error("文件校验失败，下载内容可能已损坏")
            }
            Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)

            if (format in setOf("txt", "epub")) {
                val chapterDao = container.database.chapterCacheDao()
                val toc = container.api.toc(bookId).items
                toc.forEach { summary ->
                    coroutineContext.ensureActive()
                    val chapter = container.api.chapter(bookId, summary.id)
                    chapterDao.upsert(
                        ChapterCacheEntity(
                            bookId = bookId,
                            chapterId = chapter.id,
                            title = chapter.title,
                            position = chapter.position,
                            body = chapter.body,
                            contentVersion = contentVersion,
                            isPermanent = permanent,
                            lastAccessEpochMs = System.currentTimeMillis(),
                        )
                    )
                }
            }

            dao.upsert(
                status(
                    bookId,
                    format,
                    DownloadStatus.DOWNLOADED,
                    destination.length(),
                    destination.length(),
                    contentVersion,
                    permanent,
                ).copy(localPath = destination.absolutePath)
            )
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            dao.upsert(
                status(bookId, format, DownloadStatus.PAUSED, partial.length(), expectedTotal, contentVersion, permanent)
                    .copy(localPath = readablePath)
            )
            throw cancelled
        } catch (error: Exception) {
            dao.upsert(
                status(bookId, format, DownloadStatus.FAILED, partial.length(), expectedTotal, contentVersion, permanent)
                    .copy(localPath = readablePath, error = error.message ?: "下载失败")
            )
            Result.failure(workDataOf("error" to (error.message ?: "下载失败")))
        }
    }

    private fun status(
        bookId: String,
        format: String,
        state: DownloadStatus,
        downloaded: Long,
        total: Long,
        fingerprint: String,
        permanent: Boolean,
    ) = DownloadEntity(
        bookId = bookId,
        status = state.name,
        format = format,
        bytesDownloaded = downloaded,
        totalBytes = total,
        localPath = null,
        fingerprint = fingerprint,
        error = null,
        isPermanent = permanent,
        updatedAtEpochMs = System.currentTimeMillis(),
    )

    private fun sha256(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val KEY_BOOK_ID = "book_id"
        const val KEY_FORMAT = "format"
        const val KEY_CONTENT_VERSION = "content_version"
        const val KEY_FILE_FINGERPRINT = "file_fingerprint"
        private const val LEGACY_KEY_FINGERPRINT = "fingerprint"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_PERMANENT = "permanent"
    }
}
