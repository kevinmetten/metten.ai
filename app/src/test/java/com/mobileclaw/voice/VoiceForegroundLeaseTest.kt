package com.mobileclaw.voice

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class VoiceForegroundLeaseTest {
    @Test fun `first acquisition is idempotent and released generation cannot reopen`() {
        var starts = 0; var stops = 0
        val lease = SerializedVoiceForegroundLease({ starts++ }, { stops++ })
        assertEquals(VoiceForegroundAcquireResult.Acquired, lease.acquire(1))
        assertEquals(VoiceForegroundAcquireResult.AlreadyOwned, lease.acquire(1))
        assertEquals(VoiceForegroundReleaseResult.Released, lease.release(1))
        assertEquals(VoiceForegroundAcquireResult.StaleOrEnded, lease.acquire(1))
        assertEquals(1, starts); assertEquals(1, stops)
    }

    @Test fun `failed first start has no phantom owner and newer generation recovers`() {
        var attempts = 0
        val lease = SerializedVoiceForegroundLease({ if (++attempts == 1) throw SecurityException("denied token=secret") }, {})
        val failed = lease.acquire(1) as VoiceForegroundAcquireResult.Failed
        assertFalse(failed.reason.contains("secret")) // bounded/focused contract must not expose exception class or stack.
        assertEquals(SerializedVoiceForegroundLease.PhysicalState.None, lease.stateSnapshot())
        assertEquals(VoiceForegroundAcquireResult.StaleOrEnded, lease.acquire(1))
        assertEquals(VoiceForegroundAcquireResult.Acquired, lease.acquire(3))
        assertEquals(SerializedVoiceForegroundLease.PhysicalState.Active(3), lease.stateSnapshot())
    }

    @Test fun `replacement failures retain exact conservative physical state`() {
        val calls = mutableListOf<String>(); var start = 1L; var stopFails = false; var startFails = false
        val lease = SerializedVoiceForegroundLease(
            { calls += "start:$start"; if (startFails) throw IllegalStateException("start failed") },
            { calls += "stop"; if (stopFails) throw IllegalStateException("stop failed") },
        )
        assertEquals(VoiceForegroundAcquireResult.Acquired, lease.acquire(1))
        start = 3; startFails = true
        assertTrue(lease.acquire(3) is VoiceForegroundAcquireResult.Failed)
        assertEquals(SerializedVoiceForegroundLease.PhysicalState.None, lease.stateSnapshot())
        assertEquals(VoiceForegroundAcquireResult.StaleOrEnded, lease.acquire(3))
        start = 5; startFails = false
        assertEquals(VoiceForegroundAcquireResult.Acquired, lease.acquire(5))
        start = 7; stopFails = true
        assertTrue(lease.acquire(7) is VoiceForegroundAcquireResult.Failed)
        assertEquals(SerializedVoiceForegroundLease.PhysicalState.StopFailed(5, "Voice foreground service could not stop."), lease.stateSnapshot())
        assertFalse(calls.contains("start:7"))
        start = 9
        assertTrue(lease.acquire(9) is VoiceForegroundAcquireResult.Failed)
        assertFalse(calls.contains("start:9"))
        stopFails = false; start = 11
        assertEquals(VoiceForegroundAcquireResult.Acquired, lease.acquire(11))
        assertTrue(calls.indexOfLast { it == "stop" } < calls.indexOf("start:11"))
    }

    @Test fun `failed release can retry and stale release cannot stop newer owner`() {
        var stopFails = true; var stops = 0
        val lease = SerializedVoiceForegroundLease({}, { stops++; if (stopFails) throw IllegalStateException("blocked") })
        lease.acquire(1)
        assertTrue(lease.release(1) is VoiceForegroundReleaseResult.Failed)
        assertTrue(lease.stateSnapshot() is SerializedVoiceForegroundLease.PhysicalState.StopFailed)
        stopFails = false
        assertEquals(VoiceForegroundReleaseResult.Released, lease.release(1))
        assertEquals(VoiceForegroundAcquireResult.Acquired, lease.acquire(3))
        assertEquals(VoiceForegroundReleaseResult.NoOp, lease.release(1))
        assertEquals(2, stops)
    }

    @Test fun `Errors propagate without normalizing conservative state`() {
        val startError = AssertionError("start")
        val startLease = SerializedVoiceForegroundLease({ throw startError }, {})
        assertSame(startError, assertThrows(AssertionError::class.java) { startLease.acquire(1) })
        assertEquals(SerializedVoiceForegroundLease.PhysicalState.None, startLease.stateSnapshot())
        val stopError = AssertionError("stop")
        val stopLease = SerializedVoiceForegroundLease({}, { throw stopError })
        stopLease.acquire(1)
        assertSame(stopError, assertThrows(AssertionError::class.java) { stopLease.release(1) })
        assertEquals(SerializedVoiceForegroundLease.PhysicalState.Active(1), stopLease.stateSnapshot())
    }

    @Test fun `concurrent old release finishes before newer start`() {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val stopEntered = CountDownLatch(1); val allowStop = CountDownLatch(1); val start3 = CountDownLatch(1)
        var starting = 1L
        val lease = SerializedVoiceForegroundLease(
            { calls += "start:$starting"; if (starting == 3L) start3.countDown() },
            { calls += "stop:1"; stopEntered.countDown(); check(allowStop.await(2, TimeUnit.SECONDS)) },
        )
        assertEquals(VoiceForegroundAcquireResult.Acquired, lease.acquire(1))
        val releaseResult = AtomicReference<VoiceForegroundReleaseResult>(); val acquireResult = AtomicReference<VoiceForegroundAcquireResult>()
        val releaseDone = CountDownLatch(1); val acquireDone = CountDownLatch(1)
        val a = Thread { releaseResult.set(lease.release(1)); releaseDone.countDown() }
        a.start(); assertTrue(stopEntered.await(2, TimeUnit.SECONDS)); starting = 3
        val b = Thread { acquireResult.set(lease.acquire(3)); acquireDone.countDown() }
        b.start(); assertFalse(start3.await(200, TimeUnit.MILLISECONDS)); allowStop.countDown()
        assertTrue(releaseDone.await(2, TimeUnit.SECONDS)); assertTrue(acquireDone.await(2, TimeUnit.SECONDS))
        a.join(2_000); b.join(2_000); assertFalse(a.isAlive); assertFalse(b.isAlive)
        assertEquals(VoiceForegroundReleaseResult.Released, releaseResult.get())
        assertEquals(VoiceForegroundAcquireResult.Acquired, acquireResult.get())
        assertEquals(listOf("start:1", "stop:1", "start:3"), calls)
        assertEquals(VoiceForegroundReleaseResult.NoOp, lease.release(1))
        assertEquals(VoiceForegroundAcquireResult.AlreadyOwned, lease.acquire(3))
        assertEquals(listOf("start:1", "stop:1", "start:3"), calls)
    }

    @Test fun `concurrent failed old stop prevents newer start`() {
        val calls = Collections.synchronizedList(mutableListOf<String>()); val entered = CountDownLatch(1); val allow = CountDownLatch(1)
        var starting = 1L
        val lease = SerializedVoiceForegroundLease(
            { calls += "start:$starting" },
            { calls += "stop:1"; entered.countDown(); check(allow.await(2, TimeUnit.SECONDS)); throw IllegalStateException("blocked") },
        )
        lease.acquire(1)
        val releaseResult = AtomicReference<VoiceForegroundReleaseResult>(); val acquireResult = AtomicReference<VoiceForegroundAcquireResult>()
        val releaseDone = CountDownLatch(1); val acquireDone = CountDownLatch(1)
        val a = Thread { releaseResult.set(lease.release(1)); releaseDone.countDown() }
        a.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); starting = 3
        val b = Thread { acquireResult.set(lease.acquire(3)); acquireDone.countDown() }
        b.start(); assertFalse(calls.contains("start:3")); allow.countDown()
        assertTrue(releaseDone.await(2, TimeUnit.SECONDS)); assertTrue(acquireDone.await(2, TimeUnit.SECONDS))
        a.join(2_000); b.join(2_000); assertFalse(a.isAlive); assertFalse(b.isAlive)
        assertTrue(releaseResult.get() is VoiceForegroundReleaseResult.Failed)
        assertTrue(acquireResult.get() is VoiceForegroundAcquireResult.Failed)
        assertTrue(lease.stateSnapshot() is SerializedVoiceForegroundLease.PhysicalState.StopFailed)
        assertFalse(calls.contains("start:3"))
    }
}
