package com.example.bookshelf.data.repository

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterWindowPreloadTest {
    @Test
    fun `window entries start concurrently`() = runTest {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)

        val result = loadConcurrentlyWithTimeout(listOf("current", "next", "previous"), 1_000) { key ->
            val nowActive = active.incrementAndGet()
            maximumActive.updateAndGet { maximum -> maxOf(maximum, nowActive) }
            delay(100)
            active.decrementAndGet()
            key
        }

        assertTrue("chapter requests should overlap", maximumActive.get() > 1)
        assertEquals(listOf("current", "next", "previous"), result.values.mapNotNull(Result<String>::getOrNull))
    }

    @Test
    fun `one timed out entry completes the window with a failure`() = runTest {
        val result = loadConcurrentlyWithTimeout(listOf("cached", "offline"), 150) { key ->
            if (key == "offline") delay(1_000)
            key
        }

        assertEquals("cached", result.getValue("cached").getOrNull())
        assertTrue(result.getValue("offline").isFailure)
    }

    @Test
    fun `large chapter window is concurrency bounded and reports completion`() = runTest {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val progress = mutableListOf<Int>()

        val result = loadConcurrentlyWithTimeout(
            keys = (0 until 11).toList(),
            timeoutMillis = 1_000,
            maxConcurrency = 3,
            onProgress = { completed, total ->
                assertEquals(11, total)
                progress += completed
            },
        ) { key ->
            val nowActive = active.incrementAndGet()
            maximumActive.updateAndGet { maximum -> maxOf(maximum, nowActive) }
            delay(50)
            active.decrementAndGet()
            key
        }

        assertEquals(11, result.size)
        assertTrue("at most three chapter bodies may load together", maximumActive.get() <= 3)
        assertEquals((1..11).toList(), progress.sorted())
    }
}
