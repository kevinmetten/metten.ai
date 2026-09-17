package com.mobileclaw.voice

/** Platform-neutral, text-free diagnostic seam. Callers may only provide compact metadata. */
fun interface VoiceTraceSink { fun record(event: String, metadata: String) }

object NoOpVoiceTrace : VoiceTraceSink { override fun record(event: String, metadata: String) = Unit }

class BoundedVoiceTrace(
    private val capacity: Int = 300,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) : VoiceTraceSink {
    private val lines = ArrayDeque<String>()
    @Synchronized override fun record(event: String, metadata: String) {
        require(event.matches(Regex("[A-Z0-9_]+")))
        // Metadata is deliberately constrained: transcript/response bodies cannot enter this sink.
        val safe = metadata.replace(Regex("[^A-Za-z0-9_.=:/, -]"), "?").take(500)
        lines += "${clockMillis()} $event${if (safe.isBlank()) "" else " $safe"}"
        while (lines.size > capacity) lines.removeFirst()
    }
    @Synchronized fun snapshot(header: List<String>): String =
        (header + lines).joinToString("\n")
    @Synchronized fun clear() = lines.clear()
}

/** Debug builds install a bounded sink; release leaves this as a strict no-op. */
object VoiceDiagnostics {
    @Volatile var sink: VoiceTraceSink = NoOpVoiceTrace
    @Volatile var generation: Long = 0
    @Volatile var aecEnabled: Boolean = false
    @Volatile var thresholdMillis: Long = 1_000
    private val timings = linkedMapOf<String, Long>()

    fun beginSession(newGeneration: Long) {
        generation = newGeneration
        (sink as? BoundedVoiceTrace)?.clear()
        synchronized(timings) { timings.clear() }
        event("VOICE_SESSION_STARTED", "generation=$newGeneration")
    }

    fun event(name: String, metadata: String = "") {
        sink.record(name, metadata)
        synchronized(timings) {
            if (name == "STT_ACCEPTED") timings.clear()
            timings[name] = System.nanoTime() / 1_000_000L
            if (name == "AUDIOTRACK_STARTED") emitLatencyLocked()
        }
    }

    private fun emitLatencyLocked() {
        fun delta(a: String, b: String) = timings[a]?.let { start -> timings[b]?.minus(start) }
        sink.record("TURN_LATENCY", listOf(
            "sttToBrainMs=${delta("STT_ACCEPTED", "BRAIN_REQUEST_START")}",
            "brainToFirstChunkMs=${delta("BRAIN_REQUEST_START", "BRAIN_FIRST_SPEECH_CHUNK")}",
            "audioBeforeBrainComplete=${timings["BRAIN_RESPONSE_COMPLETE"] == null}",
            "brainMs=${delta("BRAIN_REQUEST_START", "BRAIN_RESPONSE_COMPLETE")}",
            "responseToSpeakMs=${delta("BRAIN_RESPONSE_COMPLETE", "OUTPUT_SPEAK_CALLED")}",
            "speakToPocketMs=${delta("OUTPUT_SPEAK_CALLED", "POCKET_SYNTH_START")}",
            "pocketToPcmMs=${delta("POCKET_SYNTH_START", "POCKET_FIRST_PCM")}",
            "pcmToAudioTrackMs=${delta("POCKET_FIRST_PCM", "AUDIOTRACK_STARTED")}",
            "totalMs=${delta("STT_ACCEPTED", "AUDIOTRACK_STARTED")}",
        ).joinToString(" "))
    }
}

/** Privacy-preserving on-disk payload. It accepts only structural, allow-listed milestones. */
class PersistentVoiceRecord(private val maxEvents: Int = 48, private val maxBytes: Int = 6_000) {
    private val events = ArrayDeque<String>()
    var generation: Long = 0; private set
    var phase: String = "NONE"; private set
    fun begin(value: Long, aec: Boolean, thresholdMillis: Long) {
        generation = value; phase = "SESSION_STARTED"; events.clear()
        add("SESSION_STARTED", "generation=$value aec=${if (aec) 1 else 0} thresholdMs=$thresholdMillis")
    }
    fun add(event: String, metadata: String = "") {
        if (event !in durableEvents) return
        phase = event
        val safe = metadata.replace(Regex("[^A-Za-z0-9_.=:/, -]"), "?").take(240)
        events += "$event${if (safe.isBlank()) "" else " $safe"}"
        trim()
    }
    fun encode(): String = (listOf("generation=$generation", "lastVoicePhase=$phase") + events).joinToString("\n").takeLast(maxBytes)
    private fun trim() { while (events.size > maxEvents || encode().length > maxBytes) events.removeFirst() }
    companion object {
        val durableEvents = setOf(
            "SESSION_STARTED", "CONTROLLER_OUTPUT_INTERRUPTED_RECEIVED", "CONTROLLER_INTERRUPT_ACCEPTED",
            "BRAIN_STREAM_CANCEL_REQUESTED", "BRAIN_STREAM_CANCEL_RETURNED", "SPEECH_TEXT_CANCEL_ENTER",
            "SPEECH_TEXT_CANCEL_RETURNED", "STOP_EXACT_ACCEPTED", "NATIVE_CANCEL_ENTER", "NATIVE_CANCEL_RETURNED",
            "TAIL_HANDOFF_ENTER", "TAIL_HANDOFF_RETURNED", "PLAYER_CANCEL_ENTER", "PLAYER_CANCEL_RETURNED",
            "AUDIOTRACK_PAUSE_ENTER", "AUDIOTRACK_PAUSE_RETURNED", "AUDIOTRACK_FLUSH_ENTER",
            "AUDIOTRACK_FLUSH_RETURNED", "AUDIOTRACK_RELEASE_ENTER", "AUDIOTRACK_RELEASE_RETURNED",
            "CONTROLLER_LISTENING_RETURNED",
        )
    }
}
