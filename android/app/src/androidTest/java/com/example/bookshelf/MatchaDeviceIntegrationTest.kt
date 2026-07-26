package com.example.bookshelf

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bookshelf.narration.NarrationVoice
import com.example.bookshelf.narration.SherpaMatchaEngine
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MatchaDeviceIntegrationTest {
    @Test
    fun generatesChineseEnglishSpeechAndReportsPerformance() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val memoryBeforeMb = Debug.getPss() / 1024f
            val initStarted = SystemClock.elapsedRealtime()
            val engine = SherpaMatchaEngine(context, NarrationVoice.FEMALE)
            val initMs = SystemClock.elapsedRealtime() - initStarted
            val memoryAfterInitMb = Debug.getPss() / 1024f

            try {
                val results = TEST_SENTENCES.mapIndexed { index, sentence ->
                    val output = File(context.cacheDir, "matcha-device-$index.wav")
                    try {
                        engine.generate(sentence, output).also { generated ->
                            assertTrue(output.isFile && output.length() > WAV_HEADER_BYTES)
                            assertTrue(generated.durationMs > 0)
                            assertEquals(EXPECTED_SAMPLE_RATE, generated.sampleRate)
                        }
                    } finally {
                        output.delete()
                    }
                }
                val rtfs = results.map { result ->
                    result.generationTimeMs.toDouble() / result.durationMs
                }.sorted()
                val p50 = rtfs[rtfs.size / 2]
                val p95 = rtfs[(rtfs.size * 95 / 100).coerceAtMost(rtfs.lastIndex)]
                Log.i(
                    TAG,
                    "initMs=$initMs memoryDeltaMb=${memoryAfterInitMb - memoryBeforeMb} " +
                        "rtfP50=${"%.3f".format(p50)} rtfP95=${"%.3f".format(p95)} " +
                        "samples=${results.size}",
                )
            } finally {
                engine.close()
            }
        }
    }

    private companion object {
        const val TAG = "MatchaDeviceTest"
        const val EXPECTED_SAMPLE_RATE = 16_000
        const val WAV_HEADER_BYTES = 44L
        val TEST_SENTENCES = listOf(
            "银行的副行长重新核对重量，孩子长大后喜欢音乐。",
            "2026年7月26日，订单金额是123456块钱。",
            "我最近在学习machine learning，希望以后去Paris旅行。",
            "“你真的决定要走吗？”她问。他沉默了很久，才慢慢回答。",
            "人工智能正在改变我们的阅读与学习方式。",
        )
    }
}
