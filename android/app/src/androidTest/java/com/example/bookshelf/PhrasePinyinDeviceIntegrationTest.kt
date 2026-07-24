package com.example.bookshelf

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bookshelf.narration.NarrationVoice
import com.example.bookshelf.narration.SherpaPiperEngine
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhrasePinyinDeviceIntegrationTest {
    @Test
    fun requiredPhrasesMatchQuicklyAndGenerateWithBothVoices() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sample = buildString {
            repeat(1_000) { append(TARGET_TEXT) }
        }

        NarrationVoice.entries.forEach { voice ->
            val engine = SherpaPiperEngine(context, voice)
            val output = File(context.cacheDir, "phrase-pinyin-${voice.name.lowercase()}.wav")
            try {
                val requiredMatches = engine.findPhraseMatches(TARGET_TEXT)
                assertEquals(14, requiredMatches.matches.size)

                repeat(3) { engine.findPhraseMatches(sample) }
                val samplesMs = LongArray(30) {
                    engine.findPhraseMatches(sample).elapsedNanos / 1_000_000
                }.sorted()
                val p50 = samplesMs[samplesMs.size / 2]
                val p95 = samplesMs[(samplesMs.size * 95 / 100).coerceAtMost(samplesMs.lastIndex)]
                Log.i(TAG, "voice=${voice.name} chars=${sample.length} p50=${p50}ms p95=${p95}ms")
                assertTrue("Device phrase matching P95 was ${p95}ms", p95 < 250)

                val generated = engine.generate(TARGET_TEXT, output)
                assertTrue(output.isFile && output.length() > 44)
                assertTrue(generated.durationMs > 0)
            } finally {
                engine.close()
                output.delete()
            }
        }
    }

    private companion object {
        const val TAG = "PhrasePinyinDeviceTest"
        const val TARGET_TEXT =
            "银行、行长、重新、重复、重量、长大、长度、音乐、快乐、首都、都是、最差、大夫、士大夫。"
    }
}
