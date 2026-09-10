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
    private var initializationSequence = 0L
    private var initialization: Initialization? = null
    @Volatile private var capability = SpeechCapability(false, "Speech output is still initializing.", true)

    private enum class Expected { PRIMARY, FALLBACK, TERMINAL }
    private data class Initialization(
        val token: Long,
        val listener: (SpeechCapability) -> Unit,
        var expected: Expected,
    )

    override fun capability() = selected?.capability() ?: capability
    override fun initialize(listener: (SpeechCapability) -> Unit) {
        val attempt = synchronized(monitor) {
            if (released) return
            Initialization(++initializationSequence, listener, Expected.PRIMARY).also { initialization = it }
        }
        primary.initialize primaryResult@{ neural ->
            if (neural.available) {
                val delivery = synchronized(monitor) {
                    if (!isExpected(attempt, Expected.PRIMARY)) null else {
                        selected = primary
                        capability = neural
                        attempt.expected = Expected.TERMINAL
                        attempt.listener to neural
                    }
                }
                delivery?.let { (consumer, result) -> consumer(result) }
            } else {
                val shouldStartFallback = synchronized(monitor) {
                    if (!isExpected(attempt, Expected.PRIMARY)) false else {
                        attempt.expected = Expected.FALLBACK
                        true
                    }
                }
                if (!shouldStartFallback) return@primaryResult
                primary.release()
                val candidate = fallbackFactory()
                val accepted = synchronized(monitor) {
                    if (!isExpected(attempt, Expected.FALLBACK) || fallback != null) false
                    else { fallback = candidate; true }
                }
                if (!accepted) { candidate.release(); return@primaryResult }
                candidate.initialize { android ->
                    val delivery = synchronized(monitor) {
                        if (!isExpected(attempt, Expected.FALLBACK) || fallback !== candidate) null else {
                            if (android.available) selected = candidate
                            capability = if (android.available) android else SpeechCapability(false,
                                listOfNotNull(neural.reason, android.reason).distinct().joinToString(" "))
                            attempt.expected = Expected.TERMINAL
                            attempt.listener to capability
                        }
                    }
                    delivery?.let { (consumer, result) -> consumer(result) }
                }
            }
        }
    }

    private fun isExpected(attempt: Initialization, expected: Expected) =
        !released && initialization === attempt && initialization?.token == attempt.token && attempt.expected == expected
    override fun speak(text: String, listener: (SpeechOutputEvent) -> Unit) {
        val engine = synchronized(monitor) { if (released) null else selected }
        if (engine == null) listener(SpeechOutputEvent.Failed(capability.reason ?: "Speech output is unavailable."))
        else engine.speak(text, listener)
    }
    override fun stop() {
        val engine = synchronized(monitor) { if (released) null else selected }
        engine?.stop()
    }
    override fun release() {
        val engines = synchronized(monitor) {
            if (released) return
            released = true
            initializationSequence++
            initialization = null
            capability = SpeechCapability(false, "Speech output was released.")
            listOfNotNull(primary, fallback).distinct()
        }
        engines.forEach { it.release() }
    }
}
