package com.mobileclaw.voice

data class SpeechCapability(val available: Boolean, val reason: String? = null, val initializing: Boolean = false)

sealed interface SpeechInputEvent {
    data object Ready : SpeechInputEvent
    data object SpeechStarted : SpeechInputEvent
    data class Partial(val text: String) : SpeechInputEvent
    data class Final(val text: String) : SpeechInputEvent
    data class RecoverableError(
        val reason: String,
        val retryDelayMillis: Long = 700,
        val kind: SpeechInputFailureKind = SpeechInputFailureKind.NO_SPEECH,
    ) : SpeechInputEvent
    data class FatalError(val reason: String) : SpeechInputEvent
}

enum class SpeechInputFailureKind { NO_SPEECH, BUSY, CLIENT_CANCELLATION, START_FAILURE }

interface SpeechInputEngine {
    fun capability(): SpeechCapability
    fun startListening(listener: (SpeechInputEvent) -> Unit)
    fun stopListening()
    fun release()
}

sealed interface SpeechOutputEvent {
    data object Started : SpeechOutputEvent
    data object Completed : SpeechOutputEvent
    data class Failed(val reason: String) : SpeechOutputEvent
}

interface SpeechOutputEngine {
    fun capability(): SpeechCapability
    fun initialize(listener: (SpeechCapability) -> Unit) = listener(capability())
    fun speak(text: String, listener: (SpeechOutputEvent) -> Unit)
    fun stop()
    fun release()
}
