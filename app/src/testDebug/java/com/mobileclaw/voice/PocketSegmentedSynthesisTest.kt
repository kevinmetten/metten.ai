package com.mobileclaw.voice

import org.junit.Assert.*
import org.junit.Test

class PocketSegmentedSynthesisTest {
    @Test fun `cache overflow before PCM retries in order without duplicate playback`() {
        val events = mutableListOf<StreamingPlaybackEvent>()
        val player = FakePlayer(); val playback = PocketStreamingPlayback(player, PlaybackIdentity("one", 1), 24_000, events::add)
        val submitted = mutableListOf<String>()
        PocketSegmentedSynthesis(playback, { true }, { text, callback ->
            submitted += text
            if (submitted.size == 1) error("Pocket TTS text and voice conditioning exceed the 1000-token LM cache")
            callback(byteArrayOf(submitted.size.toByte(), 0), 24_000, true)
        }).run("A useful clause, " + "details ".repeat(60))
        assertTrue(submitted.size >= 3)
        assertEquals(1, player.finishes)
        assertEquals(submitted.size - 1, player.appends)
        assertEquals(1, events.count { it == StreamingPlaybackEvent.Started })
        assertEquals(1, events.count { it == StreamingPlaybackEvent.Drained })
    }

    @Test fun `cancellation prevents later segments and stale callback append`() {
        val player = FakePlayer(); val playback = PocketStreamingPlayback(player, PlaybackIdentity("old", 1), 24_000) {}
        var current = true
        var callback: ((ByteArray, Int, Boolean) -> Unit)? = null
        var requests = 0
        PocketSegmentedSynthesis(playback, { current }, { _, cb ->
            requests++; callback = cb; cb(byteArrayOf(1, 0), 24_000, true); current = false
        }).run("First sentence. " + "Later sentence. ".repeat(80))
        assertEquals(1, requests)
        assertEquals(1, player.appends)
        callback!!(byteArrayOf(2, 0), 24_000, true)
        assertEquals(1, player.appends)
        assertEquals(0, player.finishes)
    }

    private class FakePlayer : StreamingPcm16Player {
        var appends = 0; var finishes = 0
        private var listener: ((StreamingPlaybackEvent) -> Unit)? = null
        override fun start(identity: PlaybackIdentity, sampleRateHz: Int, listener: (StreamingPlaybackEvent) -> Unit) {
            this.listener = listener
            listener(StreamingPlaybackEvent.Started)
        }
        override fun append(identity: PlaybackIdentity, pcm16: ByteArray) { appends++ }
        override fun finish(identity: PlaybackIdentity) { finishes++; listener?.invoke(StreamingPlaybackEvent.Drained) }
        override fun cancel(identity: PlaybackIdentity?) = Unit
        override fun release() = Unit
    }
}
