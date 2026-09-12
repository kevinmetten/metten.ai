package com.mobileclaw.voice

import org.junit.Assert.*
import org.junit.Test

class StreamingPcmDrainStateTest {
    private val identity = PlaybackIdentity("pocket", 7)

    @Test fun `not final and final short of exact head cannot drain`() {
        val state = StreamingPcmDrainState(identity, 1_000); state.wrote(400)
        assertEquals(StreamingDrainOutcome.WAITING, state.check(identity, 10_000, 0))
        state.finish(100)
        assertEquals(StreamingDrainOutcome.WAITING, state.check(identity, 199, 101))
        assertEquals(StreamingDrainOutcome.DRAINED, state.check(identity, 200, 102))
        assertEquals(StreamingDrainOutcome.STALE, state.check(identity, 200, 103))
    }

    @Test fun `stale identity cannot drain and timeout fires once`() {
        val state = StreamingPcmDrainState(identity, 1_000); state.wrote(2_000); state.finish(100)
        assertEquals(StreamingDrainOutcome.STALE, state.check(PlaybackIdentity("new", 8), 1_000, 10_000))
        assertEquals(StreamingDrainOutcome.TIMED_OUT, state.check(identity, 0, 3_100))
        assertEquals(StreamingDrainOutcome.STALE, state.check(identity, 0, 9_000))
    }

    @Test fun `progress extends no-progress deadline but not absolute deadline`() {
        val state = StreamingPcmDrainState(identity, 1_000); state.wrote(20_000); state.finish(0)
        assertEquals(StreamingDrainOutcome.WAITING, state.check(identity, 100, 2_900))
        assertEquals(StreamingDrainOutcome.WAITING, state.check(identity, 200, 5_800))
        assertEquals(StreamingDrainOutcome.TIMED_OUT, state.check(identity, 200, 8_801))
    }
}
