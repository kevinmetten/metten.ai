package com.mobileclaw.voice

/** Selects exactly one output during initialization; it never switches mid-utterance. */
class PreferredSpeechOutputEngine(
    primaryFactory: () -> SpeechOutputEngine,
    private val fallbackFactory: () -> SpeechOutputEngine,
) : SpeechOutputEngine {
    private val monitor = Any()
    private var selected: SpeechOutputEngine? = null
    private val primary = primaryFactory()
    private var fallback: SpeechOutputEngine? = null
    private var released = false
    @Volatile private var capability = SpeechCapability(false, "Speech output is still initializing.", true)

    override fun capability() = selected?.capability() ?: capability
    override fun initialize(listener: (SpeechCapability) -> Unit) {
        primary.initialize primaryResult@{ neural ->
            if (released) return@primaryResult
            if (neural.available) {
                synchronized(monitor) { if (!released) { selected = primary; capability = neural } }
                listener(capability)
            } else {
                primary.release()
                val candidate = synchronized(monitor) { if (released) null else fallbackFactory().also { fallback = it } } ?: return@primaryResult
                candidate.initialize { android ->
                    synchronized(monitor) {
                        if (!released) {
                            if (android.available) selected = candidate
                            capability = if (android.available) android else SpeechCapability(false,
                                listOfNotNull(neural.reason, android.reason).distinct().joinToString(" "))
                        }
                    }
                    if (!released) listener(capability)
                }
            }
        }
    }
    override fun speak(text: String, listener: (SpeechOutputEvent) -> Unit) {
        val engine = synchronized(monitor) { if (released) null else selected }
        if (engine == null) listener(SpeechOutputEvent.Failed(capability.reason ?: "Speech output is unavailable."))
        else engine.speak(text, listener)
    }
    override fun stop() = synchronized(monitor) { selected }?.stop()
    override fun release() {
        val engines = synchronized(monitor) {
            if (released) return
            released = true
            capability = SpeechCapability(false, "Speech output was released.")
            listOfNotNull(selected).ifEmpty { listOfNotNull(primary, fallback) }.distinct()
        }
        engines.forEach { it.release() }
    }
}
