package com.example.bookshelf.narration

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class G2pwPolyphoneResolverTest {
    @Test
    fun skipsInferenceWhenSentenceHasNoCandidate() = runTest {
        val runtime = FakeRuntime()
        val result = G2pwOnnxPolyphoneResolver(runtime, debugLog = {}).resolve("普通句子。")

        assertTrue(result.overrides.isEmpty())
        assertEquals(0, runtime.predictCalls)
        assertEquals("普通句子。", result.originalText)
    }

    @Test
    fun mapsCodePointPredictionBackToUtf16AfterEmoji() = runTest {
        val runtime = FakeRuntime()
        val result = G2pwOnnxPolyphoneResolver(runtime, debugLog = {}).resolve("😀银行。")

        assertEquals(1, runtime.predictCalls)
        assertEquals(listOf(2), runtime.lastQueries)
        assertEquals(3, result.overrides.single().charIndex)
        assertEquals("行", result.overrides.single().character)
        assertEquals("xing", result.overrides.single().pinyin)
        assertEquals(2, result.overrides.single().tone)
        assertEquals("😀银行。", result.originalText)
    }

    @Test
    fun cachesByTextAndModelVersions() = runTest {
        val runtime = FakeRuntime()
        val resolver = G2pwOnnxPolyphoneResolver(runtime, debugLog = {})

        assertFalse(resolver.resolve("银行。").fromCache)
        assertTrue(resolver.resolve("银行。").fromCache)
        assertEquals(1, runtime.predictCalls)
    }

    @Test
    fun mapperFallsBackWhenTargetCharacterDoesNotMatch() {
        val mapper = G2pwPiperPronunciationOverrideMapper("xing2" to "\uE123")
        val base = PiperPhonemeSequence("银行。")

        val result = mapper.applyOverrides(
            "银行。",
            base,
            listOf(PronunciationOverride(0, "行", "xing", 2, 0.9f)),
        )

        assertEquals(base, result)
    }

    @Test
    fun forcedAliasKeepsPunctuationAndOtherCharacters() {
        val processor = PhrasePinyinProcessor.fromPhrases(emptyList())
        val result = processor.preprocess("😀银行，继续。", mapOf(3 to "\uE123"))

        assertEquals("😀银\uE123，继续。", result.text)
    }

    private class FakeRuntime : G2pwRuntime {
        override val modelVersion = "test-model"
        override val configVersion = "test-config"
        var predictCalls = 0
        var lastQueries = emptyList<Int>()

        override fun candidateCharacters(): Set<Char> = setOf('行')

        override suspend fun predict(text: String, queryCodePointIndices: List<Int>): List<G2pwPrediction> {
            predictCalls++
            lastQueries = queryCodePointIndices
            return queryCodePointIndices.map { G2pwPrediction(it, "xing2", 0.95f) }
        }

        override suspend fun warmUp() = Unit
        override fun close() = Unit
    }
}
