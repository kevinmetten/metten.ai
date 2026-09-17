package com.mobileclaw.voice

import org.junit.Assert.*
import org.junit.Test

class PocketTextSegmentationTest {
    @Test fun `long response is ordered complete and prefers sentences`() {
        val sentences = (1..30).map { "Sentence $it explains lift clearly. " }
        val text = sentences.joinToString("")
        val pieces = PocketTextSegmentation.initial(text)
        assertTrue(pieces.size > 1)
        assertEquals(text, pieces.joinToString(""))
        assertTrue(pieces.dropLast(1).all { it.endsWith(". ") })
        assertTrue(pieces.all { it.codePointCount(0, it.length) <= PocketTextSegmentation.INITIAL_MAX_CODE_POINTS })
    }

    @Test fun `oversized sentence falls back without breaking surrogate pairs`() {
        val text = "😀" + "word ".repeat(400) + "end."
        val pieces = PocketTextSegmentation.initial(text)
        assertEquals(text, pieces.joinToString(""))
        assertTrue(pieces.size > 1)
        assertTrue(pieces.none { it.lastOrNull()?.isHighSurrogate() == true || it.firstOrNull()?.isLowSurrogate() == true })
    }
}
