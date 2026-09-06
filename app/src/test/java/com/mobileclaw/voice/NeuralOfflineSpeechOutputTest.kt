package com.mobileclaw.voice

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class NeuralOfflineSpeechOutputTest {
    @Test fun `completion waits for exact player drain and next utterance works`() {
        val synthesizer = FakeSynthesizer()
        val player = FakePlayer()
        val output = NeuralOfflineSpeechOutput(synthesizer, player, DirectExecutor) { it() }
        output.initialize {}; val first = mutableListOf<SpeechOutputEvent>()
        output.speak("Ten.", first::add)
        assertEquals(listOf(SpeechOutputEvent.Started), first)
        assertFalse(first.contains(SpeechOutputEvent.Completed))
        player.drain(); assertEquals(listOf(SpeechOutputEvent.Started, SpeechOutputEvent.Completed), first)
        val second = mutableListOf<SpeechOutputEvent>()
        output.speak("Twelve.", second::add); player.drain()
        assertEquals(listOf(SpeechOutputEvent.Started, SpeechOutputEvent.Completed), second)
    }

    @Test fun `replacement makes stale drain harmless`() {
        val player = FakePlayer(); val output = NeuralOfflineSpeechOutput(FakeSynthesizer(), player, DirectExecutor) { it() }
        output.initialize {}; val first = mutableListOf<SpeechOutputEvent>(); val second = mutableListOf<SpeechOutputEvent>()
        output.speak("Ten.", first::add); val stale = player.listener
        output.speak("Twelve.", second::add); stale?.invoke(PcmPlaybackEvent.Drained)
        assertEquals(listOf(SpeechOutputEvent.Started), first)
        assertEquals(listOf(SpeechOutputEvent.Started), second)
        player.drain(); assertEquals(SpeechOutputEvent.Completed, second.last())
    }

    @Test fun `inference and playback failures terminalize once`() {
        val synth = FakeSynthesizer().apply { failure = IllegalStateException("inference") }
        val output = NeuralOfflineSpeechOutput(synth, FakePlayer(), DirectExecutor) { it() }; output.initialize {}
        val events = mutableListOf<SpeechOutputEvent>(); output.speak("Ten.", events::add)
        assertEquals(1, events.filterIsInstance<SpeechOutputEvent.Failed>().size)
    }

    @Test fun `stop and release silently invalidate playback`() {
        val player = FakePlayer(); val synth = FakeSynthesizer()
        val output = NeuralOfflineSpeechOutput(synth, player, DirectExecutor) { it() }; output.initialize {}
        val events = mutableListOf<SpeechOutputEvent>(); output.speak("Ten.", events::add)
        val stale = player.listener; output.stop(); stale?.invoke(PcmPlaybackEvent.Drained)
        assertEquals(listOf(SpeechOutputEvent.Started), events)
        output.release(); output.release(); assertTrue(player.released)
    }

    private class FakeSynthesizer : LocalTtsSynthesizer {
        var failure: Throwable? = null
        override fun initialize() = Result.success(Unit)
        override fun synthesize(text: String) = failure?.let { Result.failure(it) }
            ?: Result.success(SynthesizedSpeech(PcmFormat(24_000), FloatArray(240)))
        override fun cancel() = Unit
        override fun release() = Unit
    }
    private class FakePlayer : PcmSpeechPlayer {
        var listener: ((PcmPlaybackEvent) -> Unit)? = null; var released = false
        override fun play(identity: PlaybackIdentity, speech: SynthesizedSpeech, listener: (PcmPlaybackEvent) -> Unit) { this.listener = listener; listener(PcmPlaybackEvent.Started) }
        override fun cancel(identity: PlaybackIdentity?) = Unit
        override fun release() { released = true }
        fun drain() = listener?.invoke(PcmPlaybackEvent.Drained) ?: Unit
    }
    private object DirectExecutor : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow() = mutableListOf<Runnable>()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }
}
