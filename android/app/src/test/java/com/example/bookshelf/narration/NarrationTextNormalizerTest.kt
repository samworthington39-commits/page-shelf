package com.example.bookshelf.narration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationTextNormalizerTest {
    @Test
    fun removesQuotesMathSymbolsAndRepeatedDotsButKeepsProsody() {
        assertEquals(
            "他说：价格 12.50，真的？",
            NarrationTextNormalizer.normalize("“他说”：价格=12.50...真的？"),
        )
    }

    @Test
    fun collapsesDecorativePunctuationAndPreservesEnglishApostrophes() {
        assertEquals(
            "你好，世界。don't stop！",
            NarrationTextNormalizer.normalize("【你好】……——...世界。 don't_stop!"),
        )
    }

    @Test
    fun punctuationOnlyTextIsNotSpeakable() {
        assertEquals("", NarrationTextNormalizer.normalize("“”=...——"))
        assertFalse(NarrationTextNormalizer.hasSpeakableContent("“”=...——"))
        assertTrue(NarrationTextNormalizer.hasSpeakableContent("“你好……”"))
    }
}
