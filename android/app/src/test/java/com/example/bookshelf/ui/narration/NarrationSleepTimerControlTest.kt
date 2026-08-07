package com.example.bookshelf.ui.narration

import com.example.bookshelf.narration.NarrationSleepTimer
import org.junit.Assert.assertEquals
import org.junit.Test

class NarrationSleepTimerControlTest {
    @Test
    fun `remaining time rounds up to the next second`() {
        assertEquals("10:00", formatRemainingTime(599_001L))
        assertEquals("09:59", formatRemainingTime(599_000L))
        assertEquals("00:00", formatRemainingTime(-1L))
    }

    @Test
    fun `timer summary includes mode and remaining time`() {
        assertEquals("未设置", sleepTimerSummary(null, null, 1_000L))
        assertEquals(
            "本章结束",
            sleepTimerSummary(NarrationSleepTimer.END_OF_CHAPTER, null, 1_000L),
        )
        assertEquals(
            "10分钟 · 09:59",
            sleepTimerSummary(NarrationSleepTimer.MINUTES_10, 600_000L, 1_000L),
        )
    }
}
