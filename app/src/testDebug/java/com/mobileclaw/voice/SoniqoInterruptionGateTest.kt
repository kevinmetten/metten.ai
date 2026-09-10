package com.mobileclaw.voice

import org.junit.Assert.*
import org.junit.Test

class SoniqoInterruptionGateTest {
    @Test fun `duration without lexical human evidence never interrupts`() {
        val h = Harness()
        h.start("Seventeen plus twenty-eight equals forty-five.")
        h.scheduler.fire(h.gate.thresholdMillis())
        assertEquals(0, h.interruptions)
        h.gate.speechEnded()
        assertFalse(h.gate.allowFinal("equals forty five"))
    }

    @Test fun `assistant echo fragments cannot create a self turn storm`() {
        val h = Harness()
        var acceptedFinals = 0
        repeat(4) {
            h.start("Seventeen plus twenty-eight equals forty-five.")
            h.gate.partial("seventeen plus twenty eight")
            h.scheduler.fire(h.gate.thresholdMillis())
            h.gate.partial("twenty eight equals forty five")
            h.gate.speechEnded()
            if (h.gate.allowFinal("equals forty five")) acceptedFinals++
        }
        assertEquals(0, h.interruptions)
        assertEquals(0, acceptedFinals)
        assertEquals(h.output, h.current)
    }

    @Test fun `AEC sustained divergent speech interrupts once and same final survives`() {
        genuineBargeIn(aec = true, expectedDelay = 500L)
    }

    @Test fun `non AEC sustained divergent speech interrupts once and same final survives`() {
        genuineBargeIn(aec = false, expectedDelay = 1_000L)
    }

    @Test fun `human evidence before duration waits for exact threshold`() {
        val h = Harness().apply { gate.configure(true) }
        h.start("One, two, three, four, five")
        h.gate.partial("Actually stop counting and tell me the three largest planets")
        assertEquals(0, h.interruptions)
        h.scheduler.fire(500L)
        assertEquals(1, h.interruptions)
    }

    @Test fun `confirmed segment keeps a shortened unknown final exactly once`() {
        val h = Harness().apply { gate.configure(true) }
        h.start("One, two, three, four, five")
        h.gate.partial("Actually stop counting and tell me about Jupiter")
        h.scheduler.fire(500L)
        assertEquals(1, h.interruptions)
        h.gate.speechEnded()
        assertTrue(h.gate.allowFinal("about Jupiter"))
        assertFalse(h.gate.allowFinal("about Jupiter"))
        assertEquals(1, h.interruptions)
    }

    @Test fun `sustained human overlap survives output drain before interruption`() {
        val h = Harness().apply { gate.configure(true) }
        val reference = h.reference("One, two, three, four, five")
        h.gate.speechStarted(reference, muted = false)
        h.gate.partial("Actually tell me about the largest planets")
        h.current = null
        h.gate.outputEnded(reference, naturallyDrained = true)
        h.scheduler.fire(500L)
        assertEquals(0, h.interruptions)
        h.gate.speechEnded()
        assertTrue(h.gate.allowFinal("Actually tell me about the largest planets"))
        assertFalse(h.gate.allowFinal("Actually tell me about the largest planets"))
    }

    @Test fun `replacement output makes old segment unable to interrupt`() {
        val h = Harness()
        h.start("old assistant words")
        h.gate.partial("a clearly different human request")
        h.current = PlaybackIdentity("new", 2)
        h.scheduler.fire(h.gate.thresholdMillis())
        assertEquals(0, h.interruptions)
        h.gate.speechEnded()
        assertTrue(h.gate.allowFinal("a clearly different human request"))
    }

    @Test fun `natural drain quarantine discards immediate echo fragment`() {
        val h = Harness()
        val reference = h.reference("Seventeen plus twenty-eight equals forty-five")
        h.gate.outputEnded(reference, naturallyDrained = true); h.current = null
        h.gate.speechStarted(null, muted = false)
        h.gate.partial("equals forty five"); h.gate.speechEnded()
        assertFalse(h.gate.allowFinal("equals forty five"))
    }

    @Test fun `natural drain quarantine preserves divergent tail overlap user speech`() {
        val h = Harness()
        val reference = h.reference("One two three four five")
        h.gate.outputEnded(reference, naturallyDrained = true); h.current = null
        h.gate.speechStarted(null, muted = false)
        h.gate.partial("Actually tell me the three largest planets"); h.gate.speechEnded()
        assertTrue(h.gate.allowFinal("Actually tell me the three largest planets"))
        assertEquals(0, h.interruptions)
    }

    @Test fun `tail quarantine expires narrowly`() {
        val h = Harness()
        h.gate.outputEnded(h.reference("assistant tail words"), naturallyDrained = true); h.current = null
        h.scheduler.fire(SoniqoInterruptionGate.OUTPUT_TAIL_QUARANTINE_MS)
        assertTrue(h.gate.allowFinal("assistant tail words"))
    }

    @Test fun `short during-output segment and muted speech are rejected`() {
        val h = Harness()
        h.start("assistant output"); h.gate.speechEnded()
        assertFalse(h.gate.allowFinal("unrelated human request"))
        assertEquals(0, h.interruptions)
        h.gate.speechStarted(h.reference("assistant output"), muted = true)
        h.scheduler.fireAll(); assertEquals(0, h.interruptions)
    }

    @Test fun `close invalidates timers and state`() {
        val h = Harness(); h.start("assistant output"); h.gate.close(); h.scheduler.fireAll()
        assertEquals(0, h.interruptions)
    }

    private fun genuineBargeIn(aec: Boolean, expectedDelay: Long) {
        val h = Harness().apply { gate.configure(aec) }
        assertEquals(expectedDelay, h.gate.thresholdMillis())
        h.start("One, two, three, four, five")
        h.scheduler.fire(expectedDelay)
        assertEquals(0, h.interruptions)
        val user = "Actually stop counting and tell me the three largest planets"
        h.gate.partial(user); h.gate.partial(user)
        assertEquals(1, h.interruptions)
        h.gate.speechEnded()
        assertTrue(h.gate.allowFinal(user))
        assertEquals(1, h.interruptions)
    }

    private class Harness {
        val scheduler = FakeScheduler()
        val output = PlaybackIdentity("old", 1)
        var current: PlaybackIdentity? = output
        var interruptions = 0
        val gate = SoniqoInterruptionGate(scheduler, { current }, { interruptions++ })
        fun reference(text: String) = SoniqoInterruptionGate.OutputReference(output, text)
        fun start(text: String) = gate.speechStarted(reference(text), muted = false)
    }

    private class FakeScheduler : InterruptionScheduler {
        private data class Entry(val delay: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val entries = mutableListOf<Entry>()
        override fun schedule(delayMillis: Long, action: () -> Unit): CancellableTimer =
            Entry(delayMillis, action).also(entries::add).let { entry -> CancellableTimer { entry.cancelled = true } }
        fun fire(delay: Long) {
            entries.filter { !it.cancelled && it.delay == delay }.toList().also { entries.removeAll(it) }.forEach { it.action() }
        }
        fun fireAll() = entries.filterNot { it.cancelled }.toList().also { entries.clear() }.forEach { it.action() }
    }
}
