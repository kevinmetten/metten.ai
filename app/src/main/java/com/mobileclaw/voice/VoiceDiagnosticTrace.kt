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
            timings[name] = System.nanoTime() / 1_000_000L
            if (name == "AUDIOTRACK_STARTED") emitLatencyLocked()
        }
    }

    private fun emitLatencyLocked() {
        fun delta(a: String, b: String) = timings[a]?.let { start -> timings[b]?.minus(start) }
        sink.record("TURN_LATENCY", listOf(
            "sttToBrainMs=${delta("STT_ACCEPTED", "BRAIN_REQUEST_START")}",
            "brainMs=${delta("BRAIN_REQUEST_START", "BRAIN_RESPONSE_COMPLETE")}",
            "responseToSpeakMs=${delta("BRAIN_RESPONSE_COMPLETE", "OUTPUT_SPEAK_CALLED")}",
            "speakToPocketMs=${delta("OUTPUT_SPEAK_CALLED", "POCKET_SYNTH_START")}",
            "pocketToPcmMs=${delta("POCKET_SYNTH_START", "POCKET_FIRST_PCM")}",
            "pcmToAudioTrackMs=${delta("POCKET_FIRST_PCM", "AUDIOTRACK_STARTED")}",
            "totalMs=${delta("STT_ACCEPTED", "AUDIOTRACK_STARTED")}",
        ).joinToString(" "))
    }
}
