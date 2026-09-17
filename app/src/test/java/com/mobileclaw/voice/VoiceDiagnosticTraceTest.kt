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

    @Test fun `persistent milestones are bounded and reject speech bodies`() {
        val record = PersistentVoiceRecord(maxEvents = 3, maxBytes = 300)
        record.begin(8, aec = false, thresholdMillis = 1_000)
        repeat(20) { record.add("NATIVE_CANCEL_ENTER", "identity=$it") }
        record.add("STT_FINAL", "Actually stop there and secret assistant body")
        val payload = record.encode()
        assertTrue(payload.length <= 300)
        assertEquals(3, payload.lines().count { it.startsWith("NATIVE_CANCEL_ENTER") })
        assertFalse(payload.contains("Actually")); assertFalse(payload.contains("assistant body"))
    }

    @Test fun `new process defaults do not mutate prior serialized session`() {
        val crashed = PersistentVoiceRecord()
        crashed.begin(88, aec = false, thresholdMillis = 1_000)
        crashed.add("NATIVE_CANCEL_ENTER", "identity=4")
        val persisted = crashed.encode()
        PersistentVoiceRecord() // simulated relaunched process, before a new Voice session
        assertTrue(persisted.contains("generation=88"))
        assertTrue(persisted.contains("lastVoicePhase=NATIVE_CANCEL_ENTER"))
    }

    @Test fun `metadata sanitizer bounds structural java crash-like fields`() {
        val record = PersistentVoiceRecord(maxBytes = 400)
        record.begin(1, false, 1_000)
        record.add("PLAYER_CANCEL_ENTER", "type=java.lang.IllegalStateException appFrame=Voice.cancel:9 ${"!".repeat(800)}")
        assertTrue(record.encode().length <= 400)
        assertFalse(record.encode().contains("!"))
    }
}
