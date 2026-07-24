package com.example.bookshelf.narration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSegmenterTest {
    @Test
    fun strongPunctuationKeepsShortSentencesSeparate() {
        val text = "  你好世界。 Hello world!\n下一句有足够多的中文文字，可以单独完成一段朗读。"

        val segments = SentenceSegmenter.segment(text, 0)

        assertEquals(text.indexOf('你'), segments.first().start)
        assertEquals(text.length, segments.last().end)
        assertEquals("你好世界。", segments[0].text)
        assertEquals("Hello world!", segments[1].text)
        assertValidSourceRanges(text, segments)
    }

    @Test
    fun scansStrongPunctuationInsideShortFinalRemainder() {
        val text = "好。行。结束。"

        val segments = SentenceSegmenter.segment(text, 0)

        assertEquals(listOf("好。", "行。", "结束。"), segments.map(NarrationSegment::text))
        assertValidSourceRanges(text, segments)
    }

    @Test
    fun startsAtRequestedReadingPosition() {
        val text = "第一句。第二句。第三句包含足够的文字用于继续朗读。"
        val offset = text.indexOf("第二句")

        val segments = SentenceSegmenter.segment(text, offset)

        assertEquals(offset, segments.first().start)
        assertTrue(segments.first().text.startsWith("第二句。"))
        assertValidSourceRanges(text, segments)
    }

    @Test
    fun capsVeryLongParagraphsAtOneHundredCharacters() {
        val text = buildString { repeat(600) { append('字') } }

        val segments = SentenceSegmenter.segment(text, 0)

        assertTrue(segments.all { it.text.length <= SentenceSegmenter.MAX_SEGMENT_LENGTH })
        assertEquals(text, segments.joinToString(separator = "") { it.text })
    }

    @Test
    fun keepsDialogueEllipsisAndDashTogether() {
        val text = "“你真的决定要走吗？”她问。他沉默了很久……才回答：“这件事——我必须亲自去做，才知道结果。”"

        val segments = SentenceSegmenter.segment(text, 0)

        assertTrue(segments.none { it.text == "…" || it.text == "—" || it.text == "”" })
        assertEquals(1, segments.sumOf { it.text.windowed(2).count { pair -> pair == "……" } })
        assertValidSourceRanges(text, segments)
    }

    @Test
    fun keepsDecimalPointsInsideNumbers() {
        val text = "播放器以1.25倍速度工作，模型仍然保持1.0的固定生成速度，中文发音不会因此被压缩。"

        val segments = SentenceSegmenter.segment(text, 0)

        assertTrue(segments.any { "1.25" in it.text })
        assertTrue(segments.any { "1.0" in it.text })
        assertValidSourceRanges(text, segments)
    }

    private fun assertValidSourceRanges(text: String, segments: List<NarrationSegment>) {
        assertTrue(segments.isNotEmpty())
        segments.forEach { segment ->
            assertTrue(segment.end > segment.start)
            assertTrue(segment.end - segment.start <= SentenceSegmenter.MAX_SEGMENT_LENGTH)
            assertEquals(segment.text, text.substring(segment.start, segment.end).trim())
        }
    }
}
