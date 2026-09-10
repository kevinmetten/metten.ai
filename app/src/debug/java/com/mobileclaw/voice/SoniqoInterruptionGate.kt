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
    private enum class Disposition { ALLOW, DISCARD }
    private data class Segment(val id: Long, val output: PlaybackIdentity?, var confirmed: Boolean = output == null)
    private var thresholdMillis = CONSERVATIVE_CONFIRMATION_MS
    private var nextSegment = 0L
    private var segment: Segment? = null
    private var timer: CancellableTimer? = null
    private val completed = ArrayDeque<Disposition>()
    private var closed = false

    @Synchronized fun configure(acousticEchoCancelerEnabled: Boolean) {
        thresholdMillis = if (acousticEchoCancelerEnabled) AEC_CONFIRMATION_MS else CONSERVATIVE_CONFIRMATION_MS
    }
    @Synchronized fun thresholdMillis() = thresholdMillis

    @Synchronized fun speechStarted(output: PlaybackIdentity?, muted: Boolean) {
        cancelPending()
        if (closed || muted) return
        val value = Segment(++nextSegment, output)
        segment = value
        if (output != null) timer = scheduler.schedule(thresholdMillis) { confirm(value.id, output) }
    }

    @Synchronized fun speechEnded() {
        val value = segment ?: return
        completed += if (value.confirmed) Disposition.ALLOW else Disposition.DISCARD
        cancelPending()
        segment = null
    }

    /** Finals consume completed VAD segments in order; later speech cannot erase an older discard. */
    @Synchronized fun allowFinal(): Boolean {
        return when (completed.removeFirstOrNull()) {
            Disposition.DISCARD -> false
            Disposition.ALLOW, null -> segment?.confirmed != false
        }
    }

    @Synchronized fun muted() { cancelPending(); segment = null }
    @Synchronized fun close() { closed = true; muted(); completed.clear() }

    private fun confirm(id: Long, output: PlaybackIdentity) {
        val emit = synchronized(this) {
            val value = segment
            if (closed || value?.id != id || value.output != output || value.confirmed) false
            else {
                timer = null
                when (currentOutput()) {
                    output -> { value.confirmed = true; true }
                    null -> { value.confirmed = true; false }
                    else -> false
                }
            }
        }
        if (emit) emitConfirmed()
    }
    private fun cancelPending() { timer?.cancel(); timer = null }

    companion object { const val AEC_CONFIRMATION_MS = 500L; const val CONSERVATIVE_CONFIRMATION_MS = 1_000L }
}
