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

    @Test fun `cancelled replacement watchdog cannot touch replacement state`() {
        val h = Harness()
        val oneEvents = mutableListOf<SpeechOutputEvent>()
        val twoEvents = mutableListOf<SpeechOutputEvent>()
        h.accept("one", oneEvents)
        val r1 = h.scheduler.latestActiveTask()
        h.accept("two", twoEvents)
        assertTrue(r1.cancelled)
        val r2 = h.scheduler.latestActiveTask()
        val before = h.lifecycle.watchdogSnapshot()
        val queries = h.playbackQueryCount
        val scheduled = h.scheduler.scheduledCount()
        val pending = h.scheduler.pendingCount()

        h.scheduler.forceRun(r1)

        assertTrue(h.tracker.isCurrent("two"))
        assertEquals(before, h.lifecycle.watchdogSnapshot())
        assertSame(r2, h.lifecycle.watchdogSnapshot()?.scheduled)
        assertTrue(oneEvents.isEmpty())
        assertTrue(twoEvents.isEmpty())
        assertEquals(queries, h.playbackQueryCount)
        assertEquals(scheduled, h.scheduler.scheduledCount())
        assertEquals(pending, h.scheduler.pendingCount())

        h.playback = true; h.advance(100)
        assertEquals(listOf(SpeechOutputEvent.Started), twoEvents)
        h.playback = false; h.advance(100)
        assertEquals(listOf(SpeechOutputEvent.Started), twoEvents)
        h.advance(400)
        assertEquals(listOf(SpeechOutputEvent.Started, SpeechOutputEvent.Completed), twoEvents)
        assertTrue(oneEvents.isEmpty())
    }

    @Test fun `forced watchdog after invalidate stays inert`() {
        val h = Harness(); h.accept(); val task = h.scheduler.latestActiveTask()
        h.lifecycle.invalidate(); assertTrue(task.cancelled)
        val queries = h.playbackQueryCount; val scheduled = h.scheduler.scheduledCount()
        h.scheduler.forceRun(task)
        assertFalse(h.tracker.isCurrent("u")); assertNull(h.lifecycle.watchdogSnapshot())
        assertTrue(h.events.isEmpty()); assertEquals(queries, h.playbackQueryCount)
        assertEquals(scheduled, h.scheduler.scheduledCount()); assertEquals(0, h.scheduler.pendingCount())
    }

    @Test fun `forced watchdog after release stays inert`() {
        val h = Harness(); h.accept(); val task = h.scheduler.latestActiveTask()
        h.lifecycle.release(); assertTrue(task.cancelled)
        val queries = h.playbackQueryCount; val scheduled = h.scheduler.scheduledCount()
        h.scheduler.forceRun(task)
        assertFalse(h.tracker.isCurrent("u")); assertNull(h.lifecycle.watchdogSnapshot())
        assertTrue(h.events.isEmpty()); assertEquals(queries, h.playbackQueryCount)
        assertEquals(scheduled, h.scheduler.scheduledCount()); assertEquals(0, h.scheduler.pendingCount())
    }

    @Test fun `watchdog completion wins late done and obsolete poll stays inert`() {
        val h = Harness(); h.accept(); h.playback = true; h.advance(100)
        h.playback = false; h.advance(100); val obsolete = h.scheduler.latestActiveTask()
        h.advance(400)
        val scheduled = h.scheduler.scheduledCount()
        h.scheduler.forceRun(obsolete); h.lifecycle.onDone("u")
        assertEquals(1, h.events.count { it == SpeechOutputEvent.Completed })
        assertEquals(0, h.events.count { it is SpeechOutputEvent.Failed })
        assertTrue(h.events.count { it == SpeechOutputEvent.Started } <= 1)
        assertFalse(h.tracker.isCurrent("u")); assertNull(h.lifecycle.watchdogSnapshot())
        assertEquals(scheduled, h.scheduler.scheduledCount()); assertEquals(0, h.scheduler.pendingCount())
    }

    @Test fun `done completion wins forced candidate end poll`() {
        val h = Harness(); h.accept(); h.playback = true; h.advance(100)
        h.playback = false; h.advance(100)
        assertNotNull(h.lifecycle.watchdogSnapshot()?.falseSinceMillis)
        val candidateEnd = h.scheduler.latestActiveTask()
        h.lifecycle.onDone("u"); assertTrue(candidateEnd.cancelled)
        val queries = h.playbackQueryCount; val scheduled = h.scheduler.scheduledCount()
        h.scheduler.forceRun(candidateEnd)
        assertEquals(1, h.events.count { it == SpeechOutputEvent.Completed })
        assertEquals(0, h.events.count { it is SpeechOutputEvent.Failed })
        assertEquals(1, h.events.count { it == SpeechOutputEvent.Started })
        assertFalse(h.tracker.isCurrent("u")); assertNull(h.lifecycle.watchdogSnapshot())
        assertEquals(queries, h.playbackQueryCount)
        assertEquals(scheduled, h.scheduler.scheduledCount()); assertEquals(0, h.scheduler.pendingCount())
    }

    @Test fun `explicit stop and release are silent and prevent rescheduling`() {
        val stop = Harness(); stop.accept(); stop.lifecycle.invalidate(); stop.advance(60_000); stop.lifecycle.onDone("u"); assertTrue(stop.events.isEmpty())
        val release = Harness(); release.accept(); release.lifecycle.release(); release.advance(60_000); release.lifecycle.onError("u", "late"); assertTrue(release.events.isEmpty())
        assertEquals(0, release.scheduler.pendingCount())
    }

    private class Harness {
        var now = 0L; var playback = false; var throwQuery = false; var playbackQueryCount = 0
        val tracker = SpeechOutputUtteranceTracker(); val events = mutableListOf<SpeechOutputEvent>()
        val scheduler = FakeScheduler { now }
        val lifecycle = AndroidSpeechOutputLifecycle(
            tracker, { now }, scheduler,
            { playbackQueryCount++; if (throwQuery) throw IllegalStateException("query") else playback },
            { it?.listener?.invoke(it.event) },
        )
        fun register(id: String): AndroidSpeechOutputLifecycle.WatchdogToken {
            lifecycle.invalidate(); tracker.register(id, events::add); return lifecycle.replace(id)
        }
        fun accept(id: String = "u") { lifecycle.accepted(register(id)) }
        fun accept(id: String, targetEvents: MutableList<SpeechOutputEvent>) {
            lifecycle.invalidate(); tracker.register(id, targetEvents::add); lifecycle.accepted(lifecycle.replace(id))
        }
        fun advance(millis: Long) { val target = now + millis; scheduler.runThrough(target) { now = it }; now = target }
    }

    private class FakeScheduler(private val now: () -> Long) : AndroidSpeechOutputLifecycle.Scheduler {
        data class Task(val at: Long, val action: () -> Unit, var cancelled: Boolean = false, var executed: Boolean = false)
        private val tasks = mutableListOf<Task>()
        override fun schedule(delayMillis: Long, action: () -> Unit): Any = Task(now() + delayMillis, action).also(tasks::add)
        override fun cancel(handle: Any) { (handle as Task).cancelled = true }
        fun activeTasks() = tasks.filter { !it.cancelled && !it.executed }
        fun latestActiveTask() = activeTasks().last()
        fun forceRun(task: Task) { task.executed = true; task.action() }
        fun scheduledCount() = tasks.size
        fun pendingCount() = activeTasks().size
        fun runThrough(target: Long, setTime: (Long) -> Unit) {
            while (true) {
                val next = activeTasks().filter { it.at <= target }.minByOrNull { it.at } ?: return
                next.executed = true; setTime(next.at); next.action()
            }
        }
    }
}
