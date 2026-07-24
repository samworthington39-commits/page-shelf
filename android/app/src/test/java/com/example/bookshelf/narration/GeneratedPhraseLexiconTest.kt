package com.example.bookshelf.narration

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedPhraseLexiconTest {
    @Test
    fun requiredPhrasesUsePiperCompatiblePhonemes() {
        val entries = generatedFile("merged_lexicon.txt").useLines(Charsets.UTF_8) { lines ->
            lines.map { line -> line.substringBefore(' ') to line.substringAfter(' ') }
                .filter { (word, _) -> word in EXPECTED }
                .toMap()
        }

        assertEquals(EXPECTED, entries)
    }

    @Test
    fun conversionMetadataReportsPinnedSourceWithoutSkippedEntries() {
        val metadata = generatedFile("phrase_lexicon_metadata.txt").readText(Charsets.UTF_8)

        assertTrue("revision=cee0ed6e6e4898580cafd2bd5e3723e20b214aa0" in metadata)
        assertTrue("pinyin_sha256=dcc769607c220b312fea3e71cb63421298b4b891b1f7356a95ab58f2c96fff81" in metadata)
        val entryCount = Regex("phrase_count=(\\d+)").find(metadata)
            ?.groupValues?.get(1)?.toInt()
            ?: error("phrase_count is missing")
        assertTrue(entryCount >= 47_117)
        assertTrue("skipped_count=0" in metadata)
    }

    private fun generatedFile(name: String): File {
        val relative = "build/generated/phrasePinyinAssets/tts/piper_zh/common/$name"
        return sequenceOf(File(relative), File("app/$relative"))
            .firstOrNull(File::isFile)
            ?: error("Generated phrase asset $name is missing")
    }

    private companion object {
        val EXPECTED = mapOf(
            "银行" to "y in 2 _ h ang 2 _",
            "行长" to "h ang 2 _ zh ang 3 _",
            "重新" to "ch ong 2 _ x in 1 _",
            "重复" to "ch ong 2 _ f u 4 _",
            "重量" to "zh ong 4 _ l iang 4 _",
            "长大" to "zh ang 3 _ d a 4 _",
            "长度" to "ch ang 2 _ d u 4 _",
            "音乐" to "y in 1 _ y ue 4 _",
            "快乐" to "k uai 4 _ l e 4 _",
            "首都" to "sh ou 3 _ d u 1 _",
            "都是" to "d ou 1 _ sh i 4 _",
            "差" to "ch a 4 _",
            "最差" to "z ui 4 _ ch a 4 _",
            "很差" to "h en 3 _ ch a 4 _",
            "太差" to "t ai 4 _ ch a 4 _",
            "真差" to "zh en 1 _ ch a 4 _",
            "大夫" to "d ai 4 _ f u 5 _",
            "士大夫" to "sh i 4 _ d a 4 _ f u 1 _",
        )
    }
}
