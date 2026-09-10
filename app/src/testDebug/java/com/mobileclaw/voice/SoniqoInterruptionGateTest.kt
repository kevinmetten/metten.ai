package com.mobileclaw.voice

import org.junit.Assert.*
import org.junit.Test

class SoniqoInterruptionGateTest {
    @Test fun `speech onset waits and short during-output segment is rejected`() {
        val h = Harness()
        h.gate.speechStarted(h.output, muted = false)
        assertEquals(0, h.interruptions)
        h.gate.speechEnded(); h.scheduler.fireAll()
        assertEquals(0, h.interruptions)
        assertFalse(h.gate.allowFinal())
        assertTrue(h.gate.allowFinal())
    }

    @Test fun `continuous speech confirms once and keeps final eligible`() {
        val h = Harness()
        h.gate.speechStarted(h.output, muted = false); h.scheduler.fireAll(); h.scheduler.fireAll()
        assertEquals(1, h.interruptions)
        h.gate.speechEnded()
        assertTrue(h.gate.allowFinal())
    }

    @Test fun `stale output and muted speech cannot interrupt`() {
        val h = Harness()
        h.gate.speechStarted(h.output, muted = false)
        h.output = PlaybackIdentity("new", 2); h.scheduler.fireAll()
        assertEquals(0, h.interruptions)
        h.gate.speechStarted(h.output, muted = true); h.scheduler.fireAll()
        assertEquals(0, h.interruptions)
    }

    @Test fun `AEC selects 500ms and unavailable AEC selects 1000ms`() {
        val h = Harness()
        h.gate.configure(true); assertEquals(500L, h.gate.thresholdMillis())
        h.gate.configure(false); assertEquals(1_000L, h.gate.thresholdMillis())
    }

    @Test fun `close invalidates pending timer`() {
        val h = Harness(); h.gate.speechStarted(h.output, muted = false); h.gate.close(); h.scheduler.fireAll()
        assertEquals(0, h.interruptions)
    }

    @Test fun `later speech onset cannot erase older echo disposition`() {
        val h = Harness()
        h.gate.speechStarted(h.output, muted = false); h.gate.speechEnded()
        h.output = null
        h.gate.speechStarted(null, muted = false)
        assertFalse(h.gate.allowFinal())
        h.gate.speechEnded()
        assertTrue(h.gate.allowFinal())
    }

    @Test fun `completed segment dispositions are consumed in event order`() {
        val h = Harness()
        h.gate.speechStarted(h.output, muted = false); h.gate.speechEnded()
        h.output = null; h.gate.speechStarted(null, muted = false); h.gate.speechEnded()
        assertFalse(h.gate.allowFinal())
        assertTrue(h.gate.allowFinal())
    }

    private class Harness {
        val scheduler = FakeScheduler(); var output: PlaybackIdentity? = PlaybackIdentity("old", 1); var interruptions = 0
        val gate = SoniqoInterruptionGate(scheduler, { output }, { interruptions++ })
    }
    private class FakeScheduler : InterruptionScheduler {
        private data class Entry(val action: () -> Unit, var cancelled: Boolean = false)
        private val entries = mutableListOf<Entry>()
        override fun schedule(delayMillis: Long, action: () -> Unit): CancellableTimer = Entry(action).also(entries::add).let { entry -> CancellableTimer { entry.cancelled = true } }
        fun fireAll() { entries.toList().also { entries.clear() }.filterNot { it.cancelled }.forEach { it.action() } }
    }
}
