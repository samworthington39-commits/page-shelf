package com.example.bookshelf.data.local

import androidx.room.Entity

@Entity(tableName = "cached_books")
data class CachedBookEntity(
    @androidx.room.PrimaryKey val id: String,
    val shelfId: String?,
    val format: String,
    val title: String,
    val author: String?,
    val subject: String?,
    val pageCount: Int?,
    val chapterCount: Int?,
    val fileSize: Long,
    val fingerprint: String,
    val fileFingerprint: String?,
    val mimeType: String,
    val coverStatus: String,
    val parseStatus: String,
    val parseWarnings: String,
    val passwordRequired: Boolean,
    val canOpen: Boolean,
    val hasPdfNavigation: Boolean,
    val chaptersCapability: Boolean,
    val reflowableTextCapability: Boolean,
    val fontSettingsCapability: Boolean,
    val pageNavigationCapability: Boolean,
    val zoomCapability: Boolean,
    val offlineDownloadCapability: Boolean,
    val progressSyncCapability: Boolean,
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @androidx.room.PrimaryKey val bookId: String,
    val status: String,
    val format: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val localPath: String?,
    val fingerprint: String?,
    val error: String?,
    val isPermanent: Boolean,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "pdf_progress", primaryKeys = ["bookId", "deviceId"])
data class ProgressEntity(
    val bookId: String,
    val deviceId: String,
    val pageIndex: Int,
    val pageCount: Int,
    val progression: Double,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "reading_progress")
data class ReadingProgressEntity(
    @androidx.room.PrimaryKey val bookId: String,
    val bookFormat: String,
    val chapterId: String?,
    val chapterIndex: Int,
    val chapterTitle: String?,
    val chapterProgress: Double,
    val textOffset: Int,
    val paragraphIndex: Int,
    val pageIndex: Int,
    val pdfPage: Int,
    val pdfPageOffset: Double,
    val pageCount: Int?,
    val progression: Double,
    val updatedAtEpochMs: Long,
    val deviceId: String,
    val contentVersion: String?,
    val viewMode: String,
)

@Entity(tableName = "progress_sync_queue")
data class ProgressSyncEntity(
    @androidx.room.PrimaryKey val bookId: String,
    val attempts: Int,
    val nextAttemptEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "chapter_cache", primaryKeys = ["bookId", "chapterId"])
data class ChapterCacheEntity(
    val bookId: String,
    val chapterId: String,
    val title: String,
    val position: Int,
    val body: String,
    val contentVersion: String?,
    val isPermanent: Boolean,
    val lastAccessEpochMs: Long,
)
