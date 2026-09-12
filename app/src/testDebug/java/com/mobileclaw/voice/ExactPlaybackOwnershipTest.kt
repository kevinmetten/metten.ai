package com.mobileclaw.voice

import org.junit.Assert.*
import org.junit.Test

class ExactPlaybackOwnershipTest {
    private data class Work(val identity: PlaybackIdentity, var writes: Int = 0)

    @Test fun `cancel invalidates all pending old chunks before cleanup`() {
        val ownership = ExactPlaybackOwnership<Work> { it.identity }
        val old = Work(PlaybackIdentity("old", 1)); ownership.replace(old)
        val pending = List(100) { { ownership.current(old.identity)?.let { it.writes++ } } }
        assertSame(old, ownership.cancel(old.identity))
        pending.forEach { it() }
        assertEquals(0, old.writes)
    }

    @Test fun `old work and stale cancel cannot affect replacement`() {
        val ownership = ExactPlaybackOwnership<Work> { it.identity }
        val old = Work(PlaybackIdentity("old", 1)); val newer = Work(PlaybackIdentity("new", 2))
        ownership.replace(old); ownership.replace(newer)
        assertNull(ownership.current(old.identity))
        assertNull(ownership.cancel(old.identity))
        assertSame(newer, ownership.current(newer.identity))
    }
}
