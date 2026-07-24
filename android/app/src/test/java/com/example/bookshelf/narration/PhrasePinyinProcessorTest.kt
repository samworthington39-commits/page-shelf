package com.example.bookshelf.narration

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhrasePinyinProcessorTest {
    @Test
    fun keepsRequiredPolyphonicPhrasesForNativeLongestMatching() {
        val processor = loadGeneratedProcessor()
        val phrases = listOf(
            "银行", "行长", "重新", "重复", "重量", "长大", "长度", "音乐",
            "快乐", "首都", "都是", "最差", "大夫", "士大夫",
        )
        val source = phrases.joinToString("、")

        val result = processor.preprocess(source)

        assertEquals(phrases.size, result.matchCount)
        assertEquals(source, result.text)
    }

    @Test
    fun selectsLongestCompletePhraseBeforeShorterEntry() {
        val processor = PhrasePinyinProcessor.fromPhrases(
            listOf("银行", "银行行员", "行员"),
        )

        val result = processor.preprocess("银行行员去银行")

        assertEquals(2, result.matchCount)
        assertEquals("银行行员去银行", result.text)
    }

    @Test
    fun historicalTitleWinsOverCommonDoctorReadingEntry() {
        val processor = loadGeneratedProcessor()

        val result = processor.preprocess("大夫和士大夫")

        assertEquals(2, result.matchCount)
        assertEquals("大夫和士大夫", result.text)
    }

    @Test
    fun fixedLengthFallbackDoesNotSplitMatchedPhrase() {
        val processor = PhrasePinyinProcessor.fromPhrases(listOf("银行行员"))
        val source = "字".repeat(99) + "银行行员" + "继续阅读后面的文字。"
        val matches = processor.findMatches(source).matches

        val segments = SentenceSegmenter.segment(source, 0, matches)

        assertEquals(99, segments.first().end)
        matches.forEach { phrase ->
            assertTrue(segments.none { it.start < phrase.end && phrase.start < it.end && it.end < phrase.end })
        }
        assertValidSourceRanges(source, segments)
    }

    @Test
    fun startOffsetInsidePhraseMovesToPhraseStart() {
        val processor = PhrasePinyinProcessor.fromPhrases(listOf("银行行员"))
        val source = "开头银行行员后面还有足够多的文字用于朗读。"
        val matches = processor.findMatches(source).matches
        val requested = source.indexOf("行员")

        val segments = SentenceSegmenter.segment(source, requested, matches)

        assertEquals(source.indexOf("银行行员"), segments.first().start)
    }

    @Test
    fun fullDictionaryPreprocessingStaysBelowPerceptibleLatency() {
        val processor = loadGeneratedProcessor()
        val sample = buildString {
            repeat(1_000) {
                append("银行行长重新重复核对重量，孩子长大后研究长度、音乐和快乐，首都都是人群。")
            }
        }
        repeat(3) { processor.preprocess(sample) }

        val samplesMs = LongArray(30) {
            val result = processor.preprocess(sample)
            assertTrue(result.matchCount >= 11_000)
            result.elapsedNanos / 1_000_000
        }.sorted()
        val p50 = samplesMs[samplesMs.size / 2]
        val p95 = samplesMs[(samplesMs.size * 95 / 100).coerceAtMost(samplesMs.lastIndex)]
        println("phrase preprocessing chars=${sample.length} p50=${p50}ms p95=${p95}ms")

        assertTrue("Phrase preprocessing P95 was ${p95}ms", p95 < 250)
    }

    private fun loadGeneratedProcessor(): PhrasePinyinProcessor {
        val relative = "build/generated/phrasePinyinAssets/tts/piper_zh/common/phrase_trie.bin"
        val file = sequenceOf(File(relative), File("app/$relative"))
            .firstOrNull(File::isFile)
            ?: error("Generated phrase trie is missing; run generatePhrasePinyinAssets")
        return file.inputStream().use(PhrasePinyinProcessor::load)
    }

    private fun assertValidSourceRanges(text: String, segments: List<NarrationSegment>) {
        assertTrue(segments.isNotEmpty())
        segments.forEach { segment ->
            assertTrue(segment.end > segment.start)
            assertEquals(segment.text, text.substring(segment.start, segment.end).trim())
        }
    }
}
