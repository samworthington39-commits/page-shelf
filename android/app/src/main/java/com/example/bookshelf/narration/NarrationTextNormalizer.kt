package com.example.bookshelf.narration

import kotlin.text.CharCategory

/**
 * Keeps sentence punctuation as prosody hints while removing characters that
 * Matcha/eSpeak may pronounce as symbol names.
 */
internal object NarrationTextNormalizer {
    private val discardedCategories = setOf(
        CharCategory.CONNECTOR_PUNCTUATION,
        CharCategory.DASH_PUNCTUATION,
        CharCategory.START_PUNCTUATION,
        CharCategory.END_PUNCTUATION,
        CharCategory.INITIAL_QUOTE_PUNCTUATION,
        CharCategory.FINAL_QUOTE_PUNCTUATION,
        CharCategory.OTHER_PUNCTUATION,
        CharCategory.MATH_SYMBOL,
        CharCategory.CURRENCY_SYMBOL,
        CharCategory.MODIFIER_SYMBOL,
        CharCategory.OTHER_SYMBOL,
    )
    private val pauseCharacters = setOf('，', '、', '；', '：', '。', '！', '？')
    private val dashOrEllipsis = setOf('…', '—')
    private val strongPauseCharacters = setOf('。', '！', '？')

    fun normalize(text: String): String {
        if (text.isBlank()) return ""
        val result = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val character = text[index]
            val pause = canonicalPause(character)
            when {
                character == '.' -> {
                    var runEnd = index + 1
                    while (runEnd < text.length && text[runEnd] == '.') runEnd++
                    val decimalPoint =
                        runEnd == index + 1 &&
                            text.getOrNull(index - 1)?.isDigit() == true &&
                            text.getOrNull(runEnd)?.isDigit() == true
                    if (decimalPoint) result.append(character) else result.appendPause(
                        if (runEnd - index > 1) '，' else '。',
                    )
                    index = runEnd
                    continue
                }
                character == '…' || character == '—' -> {
                    var runEnd = index + 1
                    while (runEnd < text.length && text[runEnd] in dashOrEllipsis) runEnd++
                    result.appendPause('，')
                    index = runEnd
                    continue
                }
                character == '\'' &&
                    text.getOrNull(index - 1)?.isLetter() == true &&
                    text.getOrNull(index + 1)?.isLetter() == true -> result.append(character)
                character.isWhitespace() -> result.appendSpace()
                pause != null -> result.appendPause(pause)
                character.category in discardedCategories -> result.appendSpace()
                else -> result.append(character)
            }
            index++
        }
        return result.toString()
            .trimEnd()
            .trimStart { it.isWhitespace() || it in pauseCharacters }
    }

    fun hasSpeakableContent(text: String): Boolean =
        normalize(text).codePoints().anyMatch { codePoint -> Character.isLetterOrDigit(codePoint) }

    private fun canonicalPause(character: Char): Char? = when (character) {
        ',', '，' -> '，'
        '、' -> '、'
        ';', '；' -> '；'
        ':', '：' -> '：'
        '。' -> '。'
        '!', '！' -> '！'
        '?', '？' -> '？'
        else -> null
    }

    private fun StringBuilder.appendSpace() {
        if (isNotEmpty() && last() != ' ' && last() !in pauseCharacters) append(' ')
    }

    private fun StringBuilder.appendPause(character: Char) {
        while (isNotEmpty() && last() == ' ') deleteCharAt(lastIndex)
        if (isEmpty()) return
        val previous = last()
        if (previous in pauseCharacters) {
            if (character.isStrongPause() && !previous.isStrongPause()) {
                setCharAt(lastIndex, character)
            }
            return
        }
        append(character)
    }

    private fun Char.isStrongPause(): Boolean = this in strongPauseCharacters
}
