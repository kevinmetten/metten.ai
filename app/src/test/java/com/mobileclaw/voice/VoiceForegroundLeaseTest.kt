package com.mobileclaw.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceForegroundLeaseTest {
    @Test fun `replacement is ordered old stop then new start and stale release is harmless`() {
        val calls = mutableListOf<String>()
        var starting = 0L
        val lease = SerializedVoiceForegroundLease({ calls += "start:${++starting}" }, { calls += "stop" })
        assertTrue(lease.acquire(1))
        assertTrue(lease.acquire(3))
        lease.release(1)
        assertEquals(listOf("start:1", "stop", "start:2"), calls)
    }

    @Test fun `same generation acquire is idempotent and released generation cannot reopen`() {
        var starts = 0
        var stops = 0
        val lease = SerializedVoiceForegroundLease({ starts++ }, { stops++ })
        assertTrue(lease.acquire(4))
        assertTrue(lease.acquire(4))
        lease.release(4)
        assertFalse(lease.acquire(4))
        assertEquals(1, starts)
        assertEquals(1, stops)
    }
}
