package com.mobileclaw.voice

import org.junit.Assert.*
import org.junit.Test

class StreamingPcmDrainStateTest {
    @Test fun `chunks share identity and completion waits for every written frame`() {
        val identity = PlaybackIdentity("pocket", 7)
        val state = StreamingPcmDrainState(identity)
        state.wrote(320); state.wrote(160)
        assertEquals(240L, state.framesWritten)
        assertFalse(state.check(10_000)) // synthesis has not declared the final chunk
        state.finish()
        assertFalse(state.check(239))
        assertTrue(state.check(240))
        assertFalse(state.check(240))
    }
}
