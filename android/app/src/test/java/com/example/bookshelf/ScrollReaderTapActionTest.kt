package com.example.bookshelf

import com.example.bookshelf.ui.reader.ScrollReaderTapAction
import com.example.bookshelf.ui.reader.scrollPageDistance
import com.example.bookshelf.ui.reader.scrollReaderTapAction
import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollReaderTapActionTest {
    @Test
    fun verticalThirdsMapToPageAndToolbarActions() {
        assertEquals(ScrollReaderTapAction.PAGE_UP, scrollReaderTapAction(0f, 900))
        assertEquals(ScrollReaderTapAction.PAGE_UP, scrollReaderTapAction(299f, 900))
        assertEquals(ScrollReaderTapAction.TOGGLE_CONTROLS, scrollReaderTapAction(300f, 900))
        assertEquals(ScrollReaderTapAction.TOGGLE_CONTROLS, scrollReaderTapAction(600f, 900))
        assertEquals(ScrollReaderTapAction.PAGE_DOWN, scrollReaderTapAction(601f, 900))
        assertEquals(ScrollReaderTapAction.PAGE_DOWN, scrollReaderTapAction(900f, 900))
    }

    @Test
    fun invalidHeightFallsBackToToolbarAndPageKeepsReadingOverlap() {
        assertEquals(ScrollReaderTapAction.TOGGLE_CONTROLS, scrollReaderTapAction(10f, 0))
        assertEquals(880f, scrollPageDistance(1000), 0f)
        assertEquals(0f, scrollPageDistance(-1), 0f)
    }
}
