package com.example.bookshelf

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bookshelf.narration.G2pwOnnxPolyphoneResolver
import com.example.bookshelf.narration.G2pwOnnxRuntime
import com.example.bookshelf.narration.NarrationVoice
import com.example.bookshelf.narration.SherpaPiperEngine
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G2pwDeviceIntegrationTest {
    @Test
    fun resolvesPolyphonesAndGeneratesPiperAudio() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = G2pwOnnxRuntime(context)
        val resolver = G2pwOnnxPolyphoneResolver(runtime)
        val output = File(context.cacheDir, "g2pw-device.wav")
        val memoryBeforeMb = Debug.getPss() / 1024f
        try {
            val initStarted = SystemClock.elapsedRealtime()
            resolver.warmUp()
            val initMs = SystemClock.elapsedRealtime() - initStarted
            val memoryAfterInitMb = Debug.getPss() / 1024f

            val started = SystemClock.elapsedRealtime()
            val result = resolver.resolve(TARGET_TEXT)
            val firstInferenceMs = SystemClock.elapsedRealtime() - started
            assertEquals(TARGET_TEXT, result.originalText)
            assertFalse(result.fromCache)
            assertTrue(result.overrides.isNotEmpty())
            result.overrides.forEach { override ->
                assertEquals(
                    override.character,
                    TARGET_TEXT.substring(override.charIndex, override.charIndex + override.character.length),
                )
                assertTrue(override.tone in 1..5)
                assertTrue(override.pinyin.isNotBlank())
            }

            val cached = resolver.resolve(TARGET_TEXT)
            assertTrue(cached.fromCache)
            assertEquals(result.overrides, cached.overrides)

            val samples = PERF_SENTENCES.map { sentence ->
                val sampleStarted = SystemClock.elapsedRealtime()
                val resolved = resolver.resolve(sentence)
                assertTrue(resolved.overrides.isNotEmpty())
                SystemClock.elapsedRealtime() - sampleStarted
            }.sorted()
            val averageMs = samples.average()
            val p95Ms = samples[(samples.size * 95 / 100).coerceAtMost(samples.lastIndex)]

            val engine = SherpaPiperEngine(context, NarrationVoice.FEMALE, resolver)
            try {
                val generated = engine.generate(TARGET_TEXT, output)
                assertTrue(output.isFile && output.length() > 44)
                assertTrue(generated.durationMs > 0)
                Log.i(
                    TAG,
                    "piperGenerationMs=${generated.generationTimeMs} audioDurationMs=${generated.durationMs}",
                )
            } finally {
                engine.close()
            }

            Log.i(
                TAG,
                "modelBytes=${File(context.noBackupFilesDir, "g2pw/g2pw-int8.onnx").length()} " +
                    "initMs=$initMs firstInferenceMs=$firstInferenceMs averageMs=$averageMs p95Ms=$p95Ms " +
                    "memoryDeltaMb=${memoryAfterInitMb - memoryBeforeMb} overrides=${result.overrides.size}",
            )
            Unit
        } finally {
            runtime.close()
            output.delete()
        }
    }

    private companion object {
        const val TAG = "G2pwDeviceTest"
        const val TARGET_TEXT = "😀银行行长重新核对重量，孩子长大后喜欢音乐，觉得快乐。"
        val PERF_SENTENCES = listOf(
            "他到银行办理业务。",
            "这位行长重新核对账目。",
            "车辆正在缓慢行驶。",
            "孩子长大后去了远方。",
            "音乐响起时大家都很快乐。",
            "这件物品的重量并不算重。",
            "他觉得今天精神很好。",
            "朝阳下薄雾笼罩山谷。",
            "故事流传多年后被收藏。",
            "他还没有察觉异常。",
        )
    }
}
