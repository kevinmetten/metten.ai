package com.mobileclaw.voice

import org.junit.Assert.*
import org.junit.Test

class AndroidSpeechOutputLifecycleTest {
    @Test fun `Fold recovery requires true then sustained false`() {
        val h = Harness(); h.accept("fold"); h.playback = true; h.advance(100)
        h.playback = false; h.advance(100)
        assertEquals(listOf(SpeechOutputEvent.Started), h.events)
        h.advance(300); assertEquals(listOf(SpeechOutputEvent.Started), h.events)
        h.advance(100); assertEquals(SpeechOutputEvent.Completed, h.events.last())
    }

    @Test fun `false true bounce resets mixer drain quiescence`() {
        val h = Harness(); h.accept(); h.playback = true; h.advance(100)
        h.playback = false; h.advance(300); h.playback = true; h.advance(100)
        h.playback = false; h.advance(400)
        assertFalse(h.events.contains(SpeechOutputEvent.Completed))
        h.advance(100); assertEquals(SpeechOutputEvent.Completed, h.events.last())
    }

    @Test fun `onStart and range never speculate short utterance success`() {
        val h = Harness(); h.accept("Ten."); h.lifecycle.onStart("Ten."); h.lifecycle.onRangeStart("Ten.")
        h.advance(2_900); assertFalse(h.events.any { it is SpeechOutputEvent.Completed || it is SpeechOutputEvent.Failed })
        h.advance(100); assertTrue(h.events.last() is SpeechOutputEvent.Failed)
    }

    @Test fun `never starts and playback query exceptions fail within startup bound`() {
        val h = Harness(); h.accept(); h.throwQuery = true; h.advance(3_000)
        assertTrue(h.events.single() is SpeechOutputEvent.Failed)
    }

    @Test fun `never stops fails only at hard safety bound`() {
        val h = Harness(); h.accept(); h.playback = true; h.advance(100); h.advance(59_800)
        assertEquals(listOf(SpeechOutputEvent.Started), h.events)
        h.advance(100); assertTrue(h.events.last() is SpeechOutputEvent.Failed)
    }

    @Test fun `callbacks terminalize once including unexpected stop`() {
        val done = Harness(); done.accept(); done.lifecycle.onStart("u"); done.lifecycle.onDone("u"); done.lifecycle.onError("u", "late")
        assertEquals(listOf(SpeechOutputEvent.Started, SpeechOutputEvent.Completed), done.events)
        val error = Harness(); error.accept(); error.lifecycle.onError("u", "bad"); error.lifecycle.onDone("u")
        assertEquals(1, error.events.size); assertTrue(error.events.single() is SpeechOutputEvent.Failed)
        val stopped = Harness(); stopped.accept(); stopped.lifecycle.onStop("u")
        assertTrue(stopped.events.single() is SpeechOutputEvent.Failed)
    }

    @Test fun `immediate speak error fails once`() {
        val h = Harness(); val token = h.register("u")
        h.lifecycle.rejected(token, "could not start"); h.lifecycle.onDone("u")
        assertTrue(h.events.single() is SpeechOutputEvent.Failed)
    }

    @Test fun `done wins watchdog race`() {
        val h = Harness(); h.accept(); h.playback = true; h.advance(100); h.playback = false; h.advance(400)
        h.lifecycle.onDone("u"); h.advance(100)
        assertEquals(1, h.events.count { it == SpeechOutputEvent.Completed })
    }

    @Test fun `replacement rejects every stale callback and watchdog`() {
        val h = Harness(); h.accept("one"); h.playback = true
        h.accept("two"); h.lifecycle.onStart("one"); h.lifecycle.onRangeStart("one"); h.lifecycle.onDone("one"); h.lifecycle.onError("one", "bad"); h.lifecycle.onStop("one")
        h.advance(100); assertEquals(listOf(SpeechOutputEvent.Started), h.events)
        h.lifecycle.onDone("two"); assertEquals(SpeechOutputEvent.Completed, h.events.last())
    }

    @Test fun `explicit stop and release are silent and prevent rescheduling`() {
        val stop = Harness(); stop.accept(); stop.lifecycle.invalidate(); stop.advance(60_000); stop.lifecycle.onDone("u"); assertTrue(stop.events.isEmpty())
        val release = Harness(); release.accept(); release.lifecycle.release(); release.advance(60_000); release.lifecycle.onError("u", "late"); assertTrue(release.events.isEmpty())
        assertEquals(0, release.scheduler.pendingCount())
    }

    private class Harness {
        var now = 0L; var playback = false; var throwQuery = false
        val tracker = SpeechOutputUtteranceTracker(); val events = mutableListOf<SpeechOutputEvent>()
        val scheduler = FakeScheduler { now }
        val lifecycle = AndroidSpeechOutputLifecycle(
            tracker, { now }, scheduler,
            { if (throwQuery) throw IllegalStateException("query") else playback },
            { it?.listener?.invoke(it.event) },
        )
        fun register(id: String): AndroidSpeechOutputLifecycle.WatchdogToken {
            lifecycle.invalidate(); tracker.register(id, events::add); return lifecycle.replace(id)
        }
        fun accept(id: String = "u") { lifecycle.accepted(register(id)) }
        fun advance(millis: Long) { val target = now + millis; scheduler.runThrough(target) { now = it }; now = target }
    }

    private class FakeScheduler(private val now: () -> Long) : AndroidSpeechOutputLifecycle.Scheduler {
        private data class Task(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val tasks = mutableListOf<Task>()
        override fun schedule(delayMillis: Long, action: () -> Unit): Any = Task(now() + delayMillis, action).also(tasks::add)
        override fun cancel(handle: Any) { (handle as Task).cancelled = true }
        fun pendingCount() = tasks.count { !it.cancelled }
        fun runThrough(target: Long, setTime: (Long) -> Unit) {
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: return
                tasks.remove(next); setTime(next.at); next.action()
            }
        }
    }
}
