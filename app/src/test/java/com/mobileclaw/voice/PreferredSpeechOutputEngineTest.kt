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
    private class FakeEngine(private val ready: Boolean) : SpeechOutputEngine {
        var initialized = 0; var spoken = 0; var released = false
        override fun capability() = SpeechCapability(ready, if (ready) null else "unavailable")
        override fun initialize(listener: (SpeechCapability) -> Unit) { initialized++; listener(capability()) }
        override fun speak(text: String, listener: (SpeechOutputEvent) -> Unit) { spoken++; listener(SpeechOutputEvent.Completed) }
        override fun stop() = Unit
        override fun release() { released = true }
    }
}
