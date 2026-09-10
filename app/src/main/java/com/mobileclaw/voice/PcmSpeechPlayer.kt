package com.mobileclaw.voice

data class PlaybackIdentity(val utteranceId: String, val operationGeneration: Long)
data class PcmChunk(val samples: FloatArray, val frameCount: Int = samples.size)

sealed interface PcmPlaybackEvent {
    data object Started : PcmPlaybackEvent
    data object Drained : PcmPlaybackEvent
    data class Failed(val reason: String) : PcmPlaybackEvent
}

/** Plays one complete, replaceable waveform. Drained means its exact final frame was consumed. */
interface PcmSpeechPlayer {
    fun play(identity: PlaybackIdentity, speech: SynthesizedSpeech, listener: (PcmPlaybackEvent) -> Unit)
    fun cancel(identity: PlaybackIdentity? = null)
    fun release()
}
