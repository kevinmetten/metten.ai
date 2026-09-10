package com.mobileclaw.voice

internal fun interface CancellableTimer { fun cancel() }
internal fun interface InterruptionScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): CancellableTimer
}

/** Converts VAD segments into confirmed, exact-output Metten interruption events. */
internal class SoniqoInterruptionGate(
    private val scheduler: InterruptionScheduler,
    private val currentOutput: () -> PlaybackIdentity?,
    private val emitConfirmed: () -> Unit,
) {
    private data class Segment(val id: Long, val output: PlaybackIdentity, var confirmed: Boolean = false)
    private var thresholdMillis = CONSERVATIVE_CONFIRMATION_MS
    private var nextSegment = 0L
    private var segment: Segment? = null
    private var timer: CancellableTimer? = null
    private var discardNextFinal = false
    private var closed = false

    @Synchronized fun configure(acousticEchoCancelerEnabled: Boolean) {
        thresholdMillis = if (acousticEchoCancelerEnabled) AEC_CONFIRMATION_MS else CONSERVATIVE_CONFIRMATION_MS
    }
    @Synchronized fun thresholdMillis() = thresholdMillis

    @Synchronized fun speechStarted(output: PlaybackIdentity?, muted: Boolean) {
        cancelPending()
        discardNextFinal = false
        if (closed || muted || output == null) return
        val value = Segment(++nextSegment, output)
        segment = value
        timer = scheduler.schedule(thresholdMillis) { confirm(value.id, value.output) }
    }

    @Synchronized fun speechEnded() {
        val value = segment ?: return
        if (!value.confirmed) discardNextFinal = true
        cancelPending()
        segment = null
    }

    /** A confirmed segment remains eligible; a short during-output echo consumes one final. */
    @Synchronized fun allowFinal(): Boolean {
        if (discardNextFinal) { discardNextFinal = false; return false }
        return segment?.confirmed != false
    }

    @Synchronized fun muted() { cancelPending(); segment = null; discardNextFinal = false }
    @Synchronized fun close() { closed = true; muted() }

    private fun confirm(id: Long, output: PlaybackIdentity) {
        val emit = synchronized(this) {
            val value = segment
            if (closed || value?.id != id || value.output != output || value.confirmed || currentOutput() != output) false
            else { value.confirmed = true; timer = null; true }
        }
        if (emit) emitConfirmed()
    }
    private fun cancelPending() { timer?.cancel(); timer = null }

    companion object { const val AEC_CONFIRMATION_MS = 500L; const val CONSERVATIVE_CONFIRMATION_MS = 1_000L }
}
