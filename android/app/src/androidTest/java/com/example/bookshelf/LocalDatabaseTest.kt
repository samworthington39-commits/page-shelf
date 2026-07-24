package com.example.bookshelf

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bookshelf.data.local.AppDatabase
import com.example.bookshelf.data.local.ProgressSyncEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LocalDatabaseTest {
    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun syncQueueKeepsOnlyLatestTaskForEachBook() = runTest {
        val dao = database.progressSyncDao()
        dao.upsert(ProgressSyncEntity("book", 0, 1, 1))
        dao.upsert(ProgressSyncEntity("book", 3, 2, 2))

        val ready = dao.ready(10)

        assertEquals(1, ready.size)
        assertEquals(3, ready.single().attempts)
        assertEquals(2, ready.single().updatedAtEpochMs)
    }
}
