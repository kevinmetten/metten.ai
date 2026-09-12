package com.mobileclaw.voice

import java.util.concurrent.atomic.AtomicReference

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

/** Atomic exact identity invalidation used before any potentially blocking track cleanup. */
internal class ExactPlaybackOwnership<T>(private val identityOf: (T) -> PlaybackIdentity) {
    private val current = AtomicReference<T?>()
    fun replace(value: T): T? = current.getAndSet(value)
    fun current(identity: PlaybackIdentity): T? = current.get()?.takeIf { identityOf(it) == identity }
    fun isCurrent(value: T) = current.get() === value
    fun cancel(identity: PlaybackIdentity? = null): T? {
        while (true) {
            val value = current.get() ?: return null
            if (identity != null && identityOf(value) != identity) return null
            if (current.compareAndSet(value, null)) return value
        }
    }
    fun complete(value: T) = current.compareAndSet(value, null)
}

/** Pure ownership/drain accounting shared by Android playback and deterministic tests. */
internal enum class StreamingDrainOutcome { WAITING, DRAINED, TIMED_OUT, STALE }
internal class StreamingPcmDrainState(val identity: PlaybackIdentity, private val sampleRateHz: Int = 24_000) {
    var framesWritten = 0L
        private set
    var final = false
        private set
    var drained = false
        private set
    private var deadlineMillis = Long.MAX_VALUE
    private var noProgressDeadlineMillis = Long.MAX_VALUE
    private var lastHead = 0L
    fun wrote(bytes: Int) { require(bytes > 0 && bytes % 2 == 0 && !final && !drained); framesWritten += bytes / 2 }
    fun finish(nowMillis: Long = 0L) {
        require(framesWritten > 0 && !drained); final = true
        deadlineMillis = nowMillis + framesWritten * 1_000L / sampleRateHz + DRAIN_MARGIN_MS
        noProgressDeadlineMillis = nowMillis + NO_PROGRESS_MS
    }
    fun check(checkIdentity: PlaybackIdentity, playbackHeadFrames: Long, nowMillis: Long = 0L): StreamingDrainOutcome {
        if (checkIdentity != identity || drained) return StreamingDrainOutcome.STALE
        if (!final) return StreamingDrainOutcome.WAITING
        if (playbackHeadFrames >= framesWritten) { drained = true; return StreamingDrainOutcome.DRAINED }
        if (playbackHeadFrames > lastHead) { lastHead = playbackHeadFrames; noProgressDeadlineMillis = nowMillis + NO_PROGRESS_MS }
        if (nowMillis >= deadlineMillis || nowMillis >= noProgressDeadlineMillis) { drained = true; return StreamingDrainOutcome.TIMED_OUT }
        return StreamingDrainOutcome.WAITING
    }
    private companion object { const val DRAIN_MARGIN_MS = 5_000L; const val NO_PROGRESS_MS = 3_000L }
}

/** Provider-neutral routing of Pocket callback chunks into one exact playback stream. */
internal class PocketStreamingPlayback(
    private val player: StreamingPcm16Player,
    private val identity: PlaybackIdentity,
    private val sampleRateHz: Int,
    listener: (StreamingPlaybackEvent) -> Unit,
) {
    private var logicalFinal = false
    private var segment = 0
    init { player.start(identity, sampleRateHz, listener) }
    fun beginSegment(): Int = synchronized(this) {
        check(!logicalFinal) { "Pocket segment started after logical final." }
        ++segment
    }
    @Synchronized fun accept(segmentId: Int, pcm16: ByteArray, chunkSampleRateHz: Int) {
        // Native callbacks may race cancellation/retry; an old segment never owns the current stream.
        if (logicalFinal || segmentId != segment) return
        check(chunkSampleRateHz == sampleRateHz) { "Pocket changed sample rate within an utterance." }
        if (pcm16.isNotEmpty()) player.append(identity, pcm16)
    }
    @Synchronized fun finishLogical() {
        check(!logicalFinal) { "Pocket logical output finished twice." }
        logicalFinal = true
        player.finish(identity)
    }
}
