package com.example.bookshelf.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CachedBookEntity::class,
        DownloadEntity::class,
        ProgressEntity::class,
        ReadingProgressEntity::class,
        ProgressSyncEntity::class,
        ChapterCacheEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun downloadDao(): DownloadDao
    abstract fun progressDao(): ProgressDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun progressSyncDao(): ProgressSyncDao
    abstract fun chapterCacheDao(): ChapterCacheDao

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "page-shelf.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_books ADD COLUMN shelfId TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN format TEXT NOT NULL DEFAULT 'pdf'")
                db.execSQL("ALTER TABLE downloads ADD COLUMN isPermanent INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS reading_progress (
                        bookId TEXT NOT NULL PRIMARY KEY,
                        bookFormat TEXT NOT NULL,
                        chapterId TEXT,
                        chapterIndex INTEGER NOT NULL,
                        chapterTitle TEXT,
                        chapterProgress REAL NOT NULL,
                        textOffset INTEGER NOT NULL,
                        paragraphIndex INTEGER NOT NULL,
                        pageIndex INTEGER NOT NULL,
                        pdfPage INTEGER NOT NULL,
                        pdfPageOffset REAL NOT NULL,
                        pageCount INTEGER,
                        progression REAL NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL,
                        deviceId TEXT NOT NULL,
                        contentVersion TEXT,
                        viewMode TEXT NOT NULL
                    )""".trimIndent()
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS progress_sync_queue (
                        bookId TEXT NOT NULL PRIMARY KEY,
                        attempts INTEGER NOT NULL,
                        nextAttemptEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS chapter_cache (
                        bookId TEXT NOT NULL,
                        chapterId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        body TEXT NOT NULL,
                        contentVersion TEXT,
                        isPermanent INTEGER NOT NULL,
                        lastAccessEpochMs INTEGER NOT NULL,
                        PRIMARY KEY(bookId, chapterId)
                    )""".trimIndent()
                )
                db.execSQL(
                    """INSERT OR IGNORE INTO reading_progress (
                        bookId, bookFormat, chapterId, chapterIndex, chapterTitle,
                        chapterProgress, textOffset, paragraphIndex, pageIndex, pdfPage,
                        pdfPageOffset, pageCount, progression, updatedAtEpochMs, deviceId,
                        contentVersion, viewMode
                    ) SELECT bookId, 'pdf', NULL, 0, NULL, 0, 0, 0, pageIndex,
                        pageIndex, 0, pageCount, progression, updatedAtEpochMs, deviceId,
                        NULL, 'continuous' FROM pdf_progress""".trimIndent()
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_books ADD COLUMN fileFingerprint TEXT")
                db.execSQL(
                    """UPDATE cached_books
                        SET fileFingerprint = CASE
                            WHEN instr(fingerprint, ':') > 0
                                THEN substr(fingerprint, 1, instr(fingerprint, ':') - 1)
                            ELSE fingerprint
                        END""".trimIndent()
                )
            }
        }
    }
}
