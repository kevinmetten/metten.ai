package com.mobileclaw.voice

import org.junit.Assert.*
import org.junit.Test

class PreferredSpeechOutputEngineTest {
    @Test fun `ready primary is exclusive`() {
        val primary = FakeEngine(true); val fallback = FakeEngine(true)
        val engine = PreferredSpeechOutputEngine({ primary }, { fallback }); engine.initialize {}
        engine.speak("Ten.") {}
        assertEquals(1, primary.spoken); assertEquals(0, fallback.initialized); assertEquals(0, fallback.spoken)
    }
    @Test fun `unavailable primary selects fallback once`() {
        val primary = FakeEngine(false); val fallback = FakeEngine(true)
        val engine = PreferredSpeechOutputEngine({ primary }, { fallback }); engine.initialize {}; engine.speak("Ten.") {}
        assertTrue(primary.released); assertEquals(1, fallback.initialized); assertEquals(1, fallback.spoken)
    }

    @Test fun `primary callback after release is stale`() {
        val primary = DelayedEngine(); val fallback = DelayedEngine()
        val results = mutableListOf<SpeechCapability>()
        val engine = PreferredSpeechOutputEngine({ primary }, { fallback })
        engine.initialize(results::add)

        engine.release()
        primary.complete(true)
        engine.speak("No route.") {}

        assertTrue(results.isEmpty())
        assertTrue(primary.released)
        assertEquals(0, fallback.initialized)
        assertEquals(0, primary.spoken)
    }

    @Test fun `fallback callback after release is stale`() {
        val primary = DelayedEngine(); val fallback = DelayedEngine()
        val results = mutableListOf<SpeechCapability>()
        val engine = PreferredSpeechOutputEngine({ primary }, { fallback })
        engine.initialize(results::add)
        primary.complete(false)
        assertEquals(1, fallback.initialized)

        engine.release()
        fallback.complete(true)
        engine.speak("No route.") {}

        assertTrue(results.isEmpty())
        assertTrue(fallback.released)
        assertEquals(0, fallback.spoken)
    }

    @Test fun `duplicate primary callback cannot select or notify twice`() {
        val primary = DelayedEngine(); val fallback = DelayedEngine()
        val results = mutableListOf<SpeechCapability>()
        val engine = PreferredSpeechOutputEngine({ primary }, { fallback })
        engine.initialize(results::add)

        primary.complete(true)
        primary.complete(false)
        engine.speak("One route.") {}

        assertEquals(1, results.size)
        assertEquals(1, primary.spoken)
        assertEquals(0, fallback.initialized)
        assertEquals(0, fallback.spoken)
    }

    private class DelayedEngine : SpeechOutputEngine {
        var initialized = 0; var spoken = 0; var released = false
        private var listener: ((SpeechCapability) -> Unit)? = null
        override fun capability() = SpeechCapability(false, "pending", true)
        override fun initialize(listener: (SpeechCapability) -> Unit) { initialized++; this.listener = listener }
        fun complete(ready: Boolean) = checkNotNull(listener)(SpeechCapability(ready, if (ready) null else "unavailable"))
        override fun speak(text: String, listener: (SpeechOutputEvent) -> Unit) { spoken++; listener(SpeechOutputEvent.Completed) }
        override fun stop() = Unit
        override fun release() { released = true }
    }
    private class FakeEngine(private val ready: Boolean) : SpeechOutputEngine {
        var initialized = 0; var spoken = 0; var released = false
        override fun capability() = SpeechCapability(ready, if (ready) null else "unavailable")
        override fun initialize(listener: (SpeechCapability) -> Unit) { initialized++; listener(capability()) }
        override fun speak(text: String, listener: (SpeechOutputEvent) -> Unit) { spoken++; listener(SpeechOutputEvent.Completed) }
        override fun stop() = Unit
        override fun release() { released = true }
    }
}
