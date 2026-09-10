package com.mobileclaw.voice

sealed interface StreamingPlaybackEvent {
    data object Started : StreamingPlaybackEvent
    data object Drained : StreamingPlaybackEvent
    data class Failed(val reason: String) : StreamingPlaybackEvent
}

/** One PCM16 stream per utterance. Finish means no more frames; Drained means hardware presented them. */
internal interface StreamingPcm16Player {
    fun start(identity: PlaybackIdentity, sampleRateHz: Int, listener: (StreamingPlaybackEvent) -> Unit)
    fun append(identity: PlaybackIdentity, pcm16: ByteArray)
    fun finish(identity: PlaybackIdentity)
    fun cancel(identity: PlaybackIdentity? = null)
    fun release()
}

/** Pure ownership/drain accounting shared by Android playback and deterministic tests. */
internal class StreamingPcmDrainState(val identity: PlaybackIdentity) {
    var framesWritten = 0L
        private set
    var final = false
        private set
    var drained = false
        private set
    fun wrote(bytes: Int) { require(bytes > 0 && bytes % 2 == 0 && !final && !drained); framesWritten += bytes / 2 }
    fun finish() { require(framesWritten > 0 && !drained); final = true }
    fun check(playbackHeadFrames: Long): Boolean {
        if (!final || drained || playbackHeadFrames < framesWritten) return false
        drained = true
        return true
    }
}

/** Provider-neutral routing of Pocket callback chunks into one exact playback stream. */
internal class PocketStreamingPlayback(
    private val player: StreamingPcm16Player,
    private val identity: PlaybackIdentity,
    private val sampleRateHz: Int,
    listener: (StreamingPlaybackEvent) -> Unit,
) {
    private var final = false
    init { player.start(identity, sampleRateHz, listener) }
    fun accept(pcm16: ByteArray, chunkSampleRateHz: Int, isFinal: Boolean) {
        check(!final) { "Pocket emitted audio after its final chunk." }
        check(chunkSampleRateHz == sampleRateHz) { "Pocket changed sample rate within an utterance." }
        if (pcm16.isNotEmpty()) player.append(identity, pcm16)
        if (isFinal) { final = true; player.finish(identity) }
    }
}
