package com.mobileclaw.voice

import org.junit.Assert.*
import org.junit.Test

class SpeechOutputUtteranceTrackerTest {
    @Test fun `normal callbacks are exactly once and ordered`() {
        val tracker = SpeechOutputUtteranceTracker(); val events = mutableListOf<SpeechOutputEvent>()
        assertTrue(tracker.register("one", events::add))
        deliver(tracker.started("one")); deliver(tracker.started("one"))
        deliver(tracker.completed("one")); deliver(tracker.completed("one"))
        assertEquals(listOf(SpeechOutputEvent.Started, SpeechOutputEvent.Completed), events)
    }

    @Test fun `first terminal wins for error and done in either order`() {
        val tracker = SpeechOutputUtteranceTracker(); val events = mutableListOf<SpeechOutputEvent>()
        tracker.register("error-first", events::add)
        deliver(tracker.failed("error-first", "bad")); deliver(tracker.completed("error-first"))
        tracker.register("done-first", events::add)
        deliver(tracker.completed("done-first")); deliver(tracker.failed("done-first", "late"))
        assertEquals(2, events.size)
        assertTrue(events[0] is SpeechOutputEvent.Failed)
        assertEquals(SpeechOutputEvent.Completed, events[1])
    }

    @Test fun `replacement stop and release silently invalidate exact utterances`() {
        val tracker = SpeechOutputUtteranceTracker(); val one = mutableListOf<SpeechOutputEvent>(); val two = mutableListOf<SpeechOutputEvent>()
        tracker.register("one", one::add); tracker.register("two", two::add)
        deliver(tracker.started("one")); deliver(tracker.completed("one"))
        deliver(tracker.started("two")); tracker.invalidateCurrent(); deliver(tracker.completed("two"))
        assertTrue(one.isEmpty()); assertEquals(listOf(SpeechOutputEvent.Started), two)
        tracker.register("three", one::add); tracker.release()
        deliver(tracker.failed("three", "late")); assertFalse(tracker.register("four", one::add)); assertTrue(one.isEmpty())
    }

    private fun deliver(value: SpeechOutputUtteranceTracker.Delivery?) { value?.listener?.invoke(value.event) }
}
