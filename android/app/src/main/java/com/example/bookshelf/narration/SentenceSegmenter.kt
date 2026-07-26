package com.example.bookshelf.narration

import kotlin.math.abs

internal object SentenceSegmenter {
    internal const val MIN_SEGMENT_LENGTH = 15
    internal const val IDEAL_MIN_LENGTH = 30
    internal const val IDEAL_MAX_LENGTH = 70
    internal const val MAX_SEGMENT_LENGTH = 100

    private val strongBreaks = setOf('。', '！', '？', '!', '?', '…')
    private val secondaryBreaks = setOf('；', ';', '：', ':')
    private val softBreaks = setOf('，', ',', '、')
    private val openingQuotes = mapOf('“' to '”', '‘' to '’', '「' to '」', '『' to '』', '《' to '》', '（' to '）', '(' to ')')
    private val closingQuotes = openingQuotes.values.toSet() + setOf('"', '\'')

    fun segment(
        text: String,
        startOffset: Int,
    ): List<NarrationSegment> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<NarrationSegment>()
        var start = startOffset.coerceIn(0, text.length)
        while (start < text.length) {
            while (start < text.length && text[start].isWhitespace()) start++
            if (start >= text.length) break

            val end = chooseEnd(text, start)
            val spoken = text.substring(start, end).trim()
            if (NarrationTextNormalizer.hasSpeakableContent(spoken)) {
                result += NarrationSegment(start, end, spoken)
            }
            start = end.coerceAtLeast(start + 1)
        }
        return result
    }

    private fun chooseEnd(text: String, start: Int): Int {
        val hardLimit = (start + MAX_SEGMENT_LENGTH).coerceAtMost(text.length)

        val strong = mutableListOf<Int>()
        val secondary = mutableListOf<Int>()
        val soft = mutableListOf<Int>()
        val quoteStack = ArrayDeque<Char>()
        var asciiQuoteOpen = false
        var index = start
        while (index < hardLimit) {
            val char = text[index]
            when {
                char == '"' || char == '\'' -> asciiQuoteOpen = !asciiQuoteOpen
                char in openingQuotes -> quoteStack.addLast(openingQuotes.getValue(char))
                quoteStack.isNotEmpty() && char == quoteStack.last() -> quoteStack.removeLast()
            }

            val breakType = when {
                char in strongBreaks -> BreakType.STRONG
                char == '.' && isSentencePeriod(text, index) -> BreakType.STRONG
                char == '\n' || char == '\r' -> BreakType.STRONG
                char in secondaryBreaks -> BreakType.SECONDARY
                char in softBreaks -> BreakType.SOFT
                else -> null
            }
            if (breakType != null) {
                val endpoint = consumeBoundary(text, index, hardLimit)
                val closesCurrentQuote = (index + 1 until endpoint).any { position ->
                    text[position] in closingQuotes
                }
                val insideQuote = quoteStack.isNotEmpty() || asciiQuoteOpen
                if (!insideQuote || closesCurrentQuote || breakType == BreakType.STRONG && endpoint == text.length) {
                    when (breakType) {
                        BreakType.STRONG -> strong += endpoint
                        BreakType.SECONDARY -> secondary += endpoint
                        BreakType.SOFT -> soft += endpoint
                    }
                }
            }
            index++
        }

        selectStrong(strong, start)?.let { return it }
        selectWeak(secondary, start)?.let { return it }
        selectWeak(soft, start)?.let { return it }
        if (hardLimit == text.length) return text.length
        return avoidBrokenBoundary(text, hardLimit)
    }

    private fun selectStrong(points: List<Int>, start: Int): Int? {
        return points.firstOrNull { it > start }
    }

    private fun selectWeak(points: List<Int>, start: Int): Int? {
        val candidates = points.filter { it - start >= MIN_SEGMENT_LENGTH }
        if (candidates.isEmpty()) return null
        return candidates.minByOrNull { point ->
            val length = point - start
            when {
                length in IDEAL_MIN_LENGTH..IDEAL_MAX_LENGTH -> abs(length - 50)
                length < IDEAL_MIN_LENGTH -> 100 + IDEAL_MIN_LENGTH - length
                else -> 100 + length - IDEAL_MAX_LENGTH
            }
        }
    }

    private fun consumeBoundary(text: String, position: Int, limit: Int): Int {
        var end = position + 1
        val boundary = text[position]
        if (boundary == '…' || boundary == '—' || boundary == '.' || boundary == '!' || boundary == '?') {
            while (end < limit && text[end] == boundary) end++
        }
        while (end < limit && text[end] in closingQuotes) end++
        if (boundary == '\n' || boundary == '\r') {
            while (end < limit && (text[end] == '\n' || text[end] == '\r')) end++
        }
        return end
    }

    private fun isSentencePeriod(text: String, index: Int): Boolean {
        val previousIsDigit = index > 0 && text[index - 1].isDigit()
        val nextIsDigit = index + 1 < text.length && text[index + 1].isDigit()
        if (previousIsDigit && nextIsDigit) return false
        val next = text.getOrNull(index + 1)
        return next == null || next.isWhitespace() || next in closingQuotes
    }

    private fun avoidBrokenBoundary(
        text: String,
        proposed: Int,
    ): Int {
        var end = proposed.coerceIn(1, text.length)
        if (end < text.length && end > 0 && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) {
            end--
        }
        return end
    }

    private enum class BreakType { STRONG, SECONDARY, SOFT }
}
