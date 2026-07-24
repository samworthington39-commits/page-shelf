package com.example.bookshelf

import com.example.bookshelf.domain.calculateTextProgression
import com.example.bookshelf.ui.reader.chapterBodyOffset
import com.example.bookshelf.ui.reader.chapterDisplayText
import org.junit.Assert.assertEquals
import org.junit.Test

class TextReaderDomainTest {
    @Test
    fun textProgressUsesChapterAndCharacterOffset() {
        assertEquals(0.0, calculateTextProgression(0, 4, 0, 100), 0.0001)
        assertEquals(0.125, calculateTextProgression(0, 4, 50, 100), 0.0001)
        assertEquals(0.625, calculateTextProgression(2, 4, 50, 100), 0.0001)
        assertEquals(1.0, calculateTextProgression(3, 4, 100, 100), 0.0001)
    }

    @Test
    fun chapterHeadingIsPartOfTheFirstPageWithoutChangingBodyOffsets() {
        val display = chapterDisplayText("第二章 风起", "正文内容", "第 2 章")

        assertEquals("第二章 风起\n\n正文内容", display.text)
        assertEquals(0, chapterBodyOffset(0, display.bodyStart, 4))
        assertEquals(2, chapterBodyOffset(display.bodyStart + 2, display.bodyStart, 4))
    }
}
