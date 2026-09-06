package com.mobileclaw.voice

/** Deterministic watchdog for Android TTS engines which occasionally omit terminal callbacks. */
internal class AndroidSpeechOutputLifecycle(
    private val tracker: SpeechOutputUtteranceTracker,
    private val clock: Clock,
    private val scheduler: Scheduler,
    private val playback: PlaybackStateProvider,
    private val deliver: (SpeechOutputUtteranceTracker.Delivery?) -> Unit,
) {
    fun interface Clock { fun nowMillis(): Long }
    interface Scheduler {
        fun schedule(delayMillis: Long, action: () -> Unit): Any
        fun cancel(handle: Any)
    }
    fun interface PlaybackStateProvider { fun isSpeaking(): Boolean }

    data class WatchdogToken(val utteranceId: String, val sequence: Long)
    private data class State(
        val token: WatchdogToken,
        val acceptedAtMillis: Long,
        var startCallbackObserved: Boolean = false,
        var startCallbackAtMillis: Long? = null,
        var rangeCallbackObserved: Boolean = false,
        var speakingObserved: Boolean = false,
        var falseSinceMillis: Long? = null,
        var scheduled: Any? = null,
    )

    private var sequence = 0L
    private var state: State? = null
    private var released = false

    fun replace(utteranceId: String): WatchdogToken {
        cancelCurrent()
        return WatchdogToken(utteranceId, ++sequence).also {
            state = State(it, clock.nowMillis())
        }
    }

    fun accepted(token: WatchdogToken) {
        if (isActive(token)) schedule(token)
    }

    fun rejected(token: WatchdogToken, reason: String) = finish(token) { tracker.failed(token.utteranceId, reason) }

    fun onStart(utteranceId: String) {
        val value = activeFor(utteranceId) ?: return
        if (!value.startCallbackObserved) {
            value.startCallbackObserved = true
            value.startCallbackAtMillis = clock.nowMillis()
        }
        deliver(tracker.started(utteranceId))
    }

    fun onRangeStart(utteranceId: String) {
        activeFor(utteranceId)?.rangeCallbackObserved = true
    }

    fun onDone(utteranceId: String) = finishId(utteranceId) { tracker.completed(utteranceId) }
    fun onError(utteranceId: String, reason: String) = finishId(utteranceId) { tracker.failed(utteranceId, reason) }
    fun onStop(utteranceId: String) = finishId(utteranceId) { tracker.failed(utteranceId, "Offline speech playback stopped unexpectedly.") }

    fun invalidate() {
        cancelCurrent()
        tracker.invalidateCurrent()
    }

    fun release() {
        released = true
        cancelCurrent()
        tracker.release()
    }

    private fun poll(token: WatchdogToken) {
        val value = state ?: return
        value.scheduled = null
        if (!isActive(token)) return
        val now = clock.nowMillis()
        if (now - value.acceptedAtMillis >= MAX_UTTERANCE_SAFETY_MILLIS) {
            finish(token) { tracker.failed(token.utteranceId, "Offline speech playback timed out.") }
            return
        }
        val speaking = try { playback.isSpeaking() } catch (_: RuntimeException) { null }
        if (!isActive(token)) return
        when (speaking) {
            true -> {
                value.speakingObserved = true
                value.falseSinceMillis = null
                deliver(tracker.started(token.utteranceId))
            }
            false -> if (value.speakingObserved) {
                val falseSince = value.falseSinceMillis
                if (falseSince == null) value.falseSinceMillis = now
                else if (now - falseSince >= END_QUIESCENCE_MILLIS) {
                    // This poll is the required confirming query after sustained quiescence.
                    finish(token) { tracker.completed(token.utteranceId) }
                    return
                }
            }
            null -> Unit
        }
        if (!value.speakingObserved && now - value.acceptedAtMillis >= STARTUP_EVIDENCE_GRACE_MILLIS) {
            finish(token) { tracker.failed(token.utteranceId, "Offline speech playback did not provide audible-start evidence.") }
            return
        }
        schedule(token)
    }

    private fun schedule(token: WatchdogToken) {
        val value = state ?: return
        if (!isActive(token) || value.scheduled != null) return
        value.scheduled = scheduler.schedule(WATCHDOG_POLL_INTERVAL_MILLIS) { poll(token) }
    }

    private fun activeFor(id: String): State? = state?.takeIf {
        !released && it.token.utteranceId == id && tracker.isCurrent(id)
    }

    private fun isActive(token: WatchdogToken): Boolean = !released && state?.token == token && tracker.isCurrent(token.utteranceId)

    private inline fun finishId(id: String, terminal: () -> SpeechOutputUtteranceTracker.Delivery?) {
        val value = activeFor(id) ?: return
        finish(value.token, terminal)
    }

    private inline fun finish(token: WatchdogToken, terminal: () -> SpeechOutputUtteranceTracker.Delivery?) {
        if (!isActive(token)) return
        val result = terminal()
        cancelCurrent()
        deliver(result)
    }

    private fun cancelCurrent() {
        state?.scheduled?.let(scheduler::cancel)
        state = null
    }

    companion object {
        const val WATCHDOG_POLL_INTERVAL_MILLIS = 100L
        const val STARTUP_EVIDENCE_GRACE_MILLIS = 3_000L
        const val END_QUIESCENCE_MILLIS = 400L
        const val MAX_UTTERANCE_SAFETY_MILLIS = 60_000L
    }
}
