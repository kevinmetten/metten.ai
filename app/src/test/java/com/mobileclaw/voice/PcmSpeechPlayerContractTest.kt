package com.mobileclaw.voice

import org.junit.Assert.*
import org.junit.Test

class PcmSpeechPlayerContractTest {
    @Test fun `streaming look behind never exposes unknown final chunk`() {
        val holdback = FinalPcmHoldback()
        val c1 = PcmChunk(floatArrayOf(1f, 2f))
        val c2 = PcmChunk(floatArrayOf(3f))
        assertNull(holdback.accept(c1))
        assertArrayEquals(c1.samples, holdback.accept(c2)!!.samples, 0f)
        val (total, final) = holdback.finish()
        assertEquals(3L, total)
        assertArrayEquals(c2.samples, final.samples, 0f)
    }

    @Test fun `cancel discards pending final chunk`() {
        val holdback = FinalPcmHoldback(); holdback.accept(PcmChunk(floatArrayOf(1f)))
        holdback.discard()
        assertFails { holdback.finish() }
    }

    private fun assertFails(action: () -> Unit) {
        try { action(); fail("Expected failure") } catch (_: IllegalStateException) { }
    }
}
