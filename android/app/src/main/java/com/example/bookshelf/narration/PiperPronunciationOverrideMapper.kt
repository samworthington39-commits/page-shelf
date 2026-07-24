package com.example.bookshelf.narration

import android.content.Context

data class PiperPhonemeSequence(
    val originalText: String,
    val forcedPronunciationAliases: Map<Int, String> = emptyMap(),
)

interface PiperPronunciationOverrideMapper {
    fun applyOverrides(
        originalText: String,
        basePhonemes: PiperPhonemeSequence,
        overrides: List<PronunciationOverride>,
    ): PiperPhonemeSequence
}

/** Keeps the source spans intact; aliases are serialized only at the sherpa boundary. */
class G2pwPiperPronunciationOverrideMapper private constructor(
    private val aliases: Map<String, String>,
) : PiperPronunciationOverrideMapper {
    constructor(context: Context) : this(context.assets.open("g2pw/piper_aliases.tsv").bufferedReader().useLines { lines ->
        lines.mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    })

    internal constructor(vararg aliases: Pair<String, String>) : this(mapOf(*aliases))

    override fun applyOverrides(
        originalText: String,
        basePhonemes: PiperPhonemeSequence,
        overrides: List<PronunciationOverride>,
    ): PiperPhonemeSequence {
        if (basePhonemes.originalText != originalText) return basePhonemes
        val mapped = basePhonemes.forcedPronunciationAliases.toMutableMap()
        for (override in overrides) {
            val end = override.charIndex + override.character.length
            if (override.charIndex < 0 || end > originalText.length ||
                originalText.substring(override.charIndex, end) != override.character
            ) return basePhonemes
            val alias = aliases["${override.pinyin}${override.tone}"] ?: return basePhonemes
            mapped[override.charIndex] = alias
        }
        return PiperPhonemeSequence(originalText, mapped)
    }
}
