package com.mobileclaw.voice

import org.junit.Assert.*
import org.junit.Test

class AndroidAudioTrackPcmPlayerStateTest {
    private fun token(track: Any = Any(), id: String = "u1", generation: Long = 1) =
        PcmDrainToken(track, PlaybackIdentity(id, generation), generation, 1_000, generation)

    @Test fun `last submission and head below final do not drain`() {
        val state = ExactPcmDrainState(); val token = token()
        state.arm(token); assertTrue(state.submitted(token))
        assertFalse(state.check(token, 999))
        assertTrue(state.check(token, 1_000))
        assertFalse(state.check(token, 1_001))
    }

    @Test fun `marker omission is harmless when scheduled head reaches final`() {
        val state = ExactPcmDrainState(); val token = token()
        state.arm(token); state.submitted(token)
        assertFalse(state.check(token, 400))
        assertTrue(state.check(token, 1_100))
    }

    @Test fun `early marker cannot drain and unsigned head is normalized`() {
        val state = ExactPcmDrainState(); val track = Any()
        val token = PcmDrainToken(track, PlaybackIdentity("u", 1), 1, Int.MAX_VALUE.toLong(), 1)
        state.arm(token); state.submitted(token)
        assertFalse(state.check(token, 10))
        assertTrue(state.check(token, Int.MIN_VALUE)) // unsigned 2^31
    }

    @Test fun `stale token is a pure no-op`() {
        val state = ExactPcmDrainState(); val old = token(); state.arm(old); state.submitted(old)
        val replacement = token(track = Any(), id = "u2", generation = 2)
        assertFalse(state.check(replacement, 1_000))
        assertTrue(state.check(old, 1_000))
    }
}
