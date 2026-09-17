package com.mobileclaw.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceDiagnosticTraceTest {
    @Test fun `records exact chronological ordering with monotonic timestamps`() {
        var time = 40L
        val trace = BoundedVoiceTrace(10) { ++time }
        trace.record("OUTPUT_INTERRUPTED_EMITTED", "output=7")
        trace.record("CONTROLLER_OUTPUT_INTERRUPTED_RECEIVED", "generation=2")
        trace.record("OUTPUT_STOP_CALLED", "")
        assertEquals(listOf(
            "header", "41 OUTPUT_INTERRUPTED_EMITTED output=7",
            "42 CONTROLLER_OUTPUT_INTERRUPTED_RECEIVED generation=2", "43 OUTPUT_STOP_CALLED",
        ), trace.snapshot(listOf("header")).lines())
    }

    @Test fun `capacity retains only most recent events`() {
        val trace = BoundedVoiceTrace(2) { 1 }
        trace.record("FIRST", ""); trace.record("SECOND", ""); trace.record("THIRD", "")
        assertEquals(listOf("1 SECOND", "1 THIRD"), trace.snapshot(emptyList()).lines())
    }

    @Test fun `trace API cannot receive body fields and sanitizes free punctuation`() {
        val trace = BoundedVoiceTrace(2) { 1 }
        trace.record("STT_FINAL", "tokens=9 score=0.42")
        val copied = trace.snapshot(emptyList())
        assertEquals("1 STT_FINAL tokens=9 score=0.42", copied)
        assertFalse(copied.contains("userText")); assertFalse(copied.contains("assistantText"))
    }

    @Test fun `release no-op path has no observable output`() {
        var calls = 0
        val behavior = { sink: VoiceTraceSink -> sink.record("OUTPUT_STOP_CALLED", ""); calls++ }
        behavior(NoOpVoiceTrace)
        assertEquals(1, calls)
    }
}
