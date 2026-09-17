package com.mobileclaw.voice

import org.junit.Assert.*
import org.junit.Test

class PocketStreamingPlaybackTest {
    @Test fun `first chunk starts playback before synthesis final and later chunks reuse stream`() {
        val player = FakePlayer(); val identity = PlaybackIdentity("one", 1)
        val output = PocketStreamingPlayback(player, identity, 24_000) {}
        val first = output.beginSegment()
        output.accept(first, byteArrayOf(1, 0), 24_000)
        assertEquals(listOf("start:one", "append:one:2"), player.calls)
        output.accept(first, byteArrayOf(2, 0, 3, 0), 24_000)
        output.beginSegment()
        output.finishLogical()
        assertEquals(listOf("start:one", "append:one:2", "append:one:4", "finish:one"), player.calls)
    }

    @Test fun `provider segment finals share one stream and logical finish occurs once`() {
        val player = FakePlayer(); val identity = PlaybackIdentity("many", 2)
        val output = PocketStreamingPlayback(player, identity, 24_000) {}
        repeat(3) { output.accept(output.beginSegment(), byteArrayOf(1, 0), 24_000) }
        output.finishLogical()
        assertEquals(1, player.calls.count { it == "start:many" })
        assertEquals(3, player.calls.count { it == "append:many:2" })
        assertEquals(1, player.calls.count { it == "finish:many" })
    }

    private class FakePlayer : StreamingPcm16Player {
        val calls = mutableListOf<String>()
        override fun start(identity: PlaybackIdentity, sampleRateHz: Int, listener: (StreamingPlaybackEvent) -> Unit) { calls += "start:${identity.utteranceId}" }
        override fun append(identity: PlaybackIdentity, pcm16: ByteArray) { calls += "append:${identity.utteranceId}:${pcm16.size}" }
        override fun finish(identity: PlaybackIdentity) { calls += "finish:${identity.utteranceId}" }
        override fun cancel(identity: PlaybackIdentity?) = Unit
        override fun release() = Unit
    }
}
