package com.example.bookshelf.narration

import org.junit.Assert.assertEquals
import org.junit.Test

class SherpaPiperEngineTest {
    @Test
    fun synthesisSpeedAlwaysRemainsNormal() {
        assertEquals(1.0f, SherpaPiperEngine.SYNTHESIS_SPEED, 0f)
    }

    @Test
    fun playbackSpeedUsesNewRangeAndFiveHundredthsSteps() {
        assertEquals(0.75f, normalizePlaybackSpeed(0.2f), 0.0001f)
        assertEquals(2.5f, normalizePlaybackSpeed(4f), 0.0001f)
        assertEquals(1.25f, normalizePlaybackSpeed(1.23f), 0.0001f)
        assertEquals(1.0f, normalizePlaybackSpeed(Float.NaN), 0.0001f)
    }

    @Test
    fun requiredPresetSpeedsAreAvailable() {
        assertEquals(
            listOf(0.75f, 0.85f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f, 1.75f, 2.0f, 2.25f, 2.5f),
            NarrationController.PRESET_PLAYBACK_SPEEDS,
        )
        assertEquals(
            listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f),
            NarrationController.QUICK_PLAYBACK_SPEEDS,
        )
    }
}
