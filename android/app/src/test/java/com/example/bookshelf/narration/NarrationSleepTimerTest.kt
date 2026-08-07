package com.example.bookshelf.narration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NarrationSleepTimerTest {
    @Test
    fun `sleep timer exposes the requested choices`() {
        assertEquals(
            listOf("本章结束", "10分钟", "20分钟", "30分钟", "1小时"),
            NarrationSleepTimer.entries.map(NarrationSleepTimer::displayName),
        )
    }

    @Test
    fun `duration choices use the expected milliseconds`() {
        assertNull(NarrationSleepTimer.END_OF_CHAPTER.durationMillis)
        assertEquals(600_000L, NarrationSleepTimer.MINUTES_10.durationMillis)
        assertEquals(1_200_000L, NarrationSleepTimer.MINUTES_20.durationMillis)
        assertEquals(1_800_000L, NarrationSleepTimer.MINUTES_30.durationMillis)
        assertEquals(3_600_000L, NarrationSleepTimer.HOUR_1.durationMillis)
    }
}
