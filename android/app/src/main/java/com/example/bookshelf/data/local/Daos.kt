package com.example.bookshelf.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM cached_books ORDER BY title COLLATE NOCASE")
    suspend fun all(): List<CachedBookEntity>

    @Query("SELECT * FROM cached_books WHERE id = :bookId")
    suspend fun byId(bookId: String): CachedBookEntity?

    @Upsert
    suspend fun upsertAll(books: List<CachedBookEntity>)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE bookId = :bookId")
    fun observe(bookId: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE bookId = :bookId")
    suspend fun byId(bookId: String): DownloadEntity?

    @Query("SELECT * FROM downloads ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE isPermanent = 1 AND status IN ('DOWNLOADED', 'OUTDATED') ORDER BY updatedAtEpochMs DESC")
    suspend fun permanent(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE isPermanent = 0")
    suspend fun temporary(): List<DownloadEntity>

    @Upsert
    suspend fun upsert(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE bookId = :bookId")
    suspend fun delete(bookId: String)
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM pdf_progress WHERE bookId = :bookId AND deviceId = :deviceId")
    fun observe(bookId: String, deviceId: String): Flow<ProgressEntity?>

    @Query("SELECT * FROM pdf_progress WHERE bookId = :bookId AND deviceId = :deviceId")
    suspend fun byId(bookId: String, deviceId: String): ProgressEntity?

    @Upsert
    suspend fun upsert(progress: ProgressEntity)
}

@Dao
interface ReadingProgressDao {
    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    suspend fun byBook(bookId: String): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<ReadingProgressEntity>>

    @Query("SELECT * FROM reading_progress ORDER BY updatedAtEpochMs DESC")
    suspend fun all(): List<ReadingProgressEntity>

    @Upsert
    suspend fun upsert(progress: ReadingProgressEntity)

    @Query("DELETE FROM reading_progress WHERE bookId = :bookId")
    suspend fun delete(bookId: String)
}

@Dao
interface ProgressSyncDao {
    @Query("SELECT * FROM progress_sync_queue WHERE nextAttemptEpochMs <= :now ORDER BY updatedAtEpochMs")
    suspend fun ready(now: Long): List<ProgressSyncEntity>

    @Query("SELECT * FROM progress_sync_queue WHERE bookId = :bookId")
    suspend fun byBook(bookId: String): ProgressSyncEntity?

    @Upsert
    suspend fun upsert(item: ProgressSyncEntity)

    @Query("DELETE FROM progress_sync_queue WHERE bookId = :bookId")
    suspend fun delete(bookId: String)

    @Query("DELETE FROM progress_sync_queue")
    suspend fun clear()
}

@Dao
interface ChapterCacheDao {
    @Query("SELECT * FROM chapter_cache WHERE bookId = :bookId ORDER BY position")
    suspend fun chapters(bookId: String): List<ChapterCacheEntity>

    @Query("SELECT * FROM chapter_cache WHERE bookId = :bookId AND chapterId = :chapterId")
    suspend fun chapter(bookId: String, chapterId: String): ChapterCacheEntity?

    @Upsert
    suspend fun upsert(chapter: ChapterCacheEntity)

    @Upsert
    suspend fun upsertAll(chapters: List<ChapterCacheEntity>)

    @Query("UPDATE chapter_cache SET isPermanent = 1 WHERE bookId = :bookId")
    suspend fun markPermanent(bookId: String)

    @Query("DELETE FROM chapter_cache WHERE bookId = :bookId")
    suspend fun deleteBook(bookId: String)

    @Query("DELETE FROM chapter_cache WHERE isPermanent = 0")
    suspend fun clearTemporary()
}
