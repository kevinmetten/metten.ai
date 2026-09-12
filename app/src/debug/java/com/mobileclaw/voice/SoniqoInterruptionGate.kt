package com.mobileclaw.voice

internal fun interface CancellableTimer { fun cancel() }
internal fun interface InterruptionScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): CancellableTimer
}

/** Converts VAD and lexical evidence into exact-output Metten interruption events. */
internal class SoniqoInterruptionGate(
    private val scheduler: InterruptionScheduler,
    private val currentOutput: () -> PlaybackIdentity?,
    private val emitConfirmed: () -> Unit,
    private val debug: (String) -> Unit = {},
) {
    internal data class OutputReference(val identity: PlaybackIdentity, val text: String) {
        val tokens = normalize(text)
    }

    private enum class Classification { ECHO, HUMAN, UNKNOWN }
    private data class Analysis(val classification: Classification, val overlap: Double, val divergentResidue: Int, val divergentRun: Int)
    private data class Segment(
        val id: Long,
        val reference: OutputReference?,
        val beganDuringOutput: Boolean,
        var durationReached: Boolean = false,
        var classification: Classification = if (reference == null) Classification.HUMAN else Classification.UNKNOWN,
        var interrupted: Boolean = false,
    )

    private var thresholdMillis = CONSERVATIVE_CONFIRMATION_MS
    private var aecEnabled = false
    private var nextSegment = 0L
    private var segment: Segment? = null
    private val completed = ArrayDeque<Segment>()
    private var timer: CancellableTimer? = null
    private var recentOutput: OutputReference? = null
    private var recentExpiry: CancellableTimer? = null
    private var settledSegmentAwaitingNextSpeechId: Long? = null
    private var closed = false

    @Synchronized fun configure(acousticEchoCancelerEnabled: Boolean) {
        aecEnabled = acousticEchoCancelerEnabled
        thresholdMillis = if (aecEnabled) AEC_CONFIRMATION_MS else CONSERVATIVE_CONFIRMATION_MS
    }

    @Synchronized fun thresholdMillis() = thresholdMillis

    @Synchronized fun speechStarted(output: OutputReference?, muted: Boolean, recentHandoff: OutputReference? = null) {
        cancelSegmentTimer()
        if (closed || muted) return
        // A segment beginning just after drain still gets the exact, narrowly-lived output reference.
        val reference = output ?: recentHandoff ?: recentOutput
        val value = Segment(++nextSegment, reference, beganDuringOutput = output != null)
        segment = value
        settledSegmentAwaitingNextSpeechId = null
        debug("VAD segment=${value.id} output=${reference?.identity} AEC=$aecEnabled started")
        if (output != null) {
            timer = scheduler.schedule(thresholdMillis) { durationReached(value.id, output.identity) }
        } else {
            // There is no output to interrupt, but lexical classification still protects the final from tail echo.
            value.durationReached = true
        }
    }

    fun partial(text: String) = applyTranscript(text, final = false)

    @Synchronized fun speechEnded() {
        val value = segment ?: return
        completed += value
        debug("VAD segment=${value.id} ended classification=${value.classification}")
        cancelSegmentTimer()
        segment = null
    }

    /** Finals consume completed VAD segments in order and may provide their last lexical evidence. */
    fun allowFinal(text: String, activeOutput: OutputReference? = null): Boolean {
        var emit = false
        val allowed = synchronized(this) {
            val value = completed.removeFirstOrNull() ?: segment
            if (value == null) {
                if (settledSegmentAwaitingNextSpeechId != null) {
                    debug("duplicate final for settled VAD segment=$settledSegmentAwaitingNextSpeechId discarded")
                    return@synchronized false
                }
                val reference = activeOutput ?: recentOutput
                val tokens = normalize(text)
                val analysis = analyze(reference?.tokens, tokens)
                val fallback = activeOutput != null && tokens.size >= FINAL_FALLBACK_MIN_TOKENS &&
                    analysis.classification == Classification.HUMAN && currentOutput() == activeOutput.identity
                if (fallback) {
                    settledSegmentAwaitingNextSpeechId = ++nextSegment
                    emit = true
                    debug("final without VAD output=${activeOutput.identity} overlap=${analysis.overlap} divergentResidue=${analysis.divergentResidue} divergentRun=${analysis.divergentRun} HUMAN OutputInterrupted emitted; allowed")
                    return@synchronized true
                }
                debug("final without VAD output=${reference?.identity} overlap=${analysis.overlap} divergentResidue=${analysis.divergentResidue} divergentRun=${analysis.divergentRun} classified=${analysis.classification} ${if (analysis.classification == Classification.ECHO || activeOutput != null) "discarded" else "allowed"}")
                return@synchronized activeOutput == null && analysis.classification != Classification.ECHO
            }
            val finalAnalysis = analyze(value.reference?.tokens, normalize(text))
            val finalClassification = finalAnalysis.classification
            value.classification = finalClassification
            emit = shouldInterrupt(value)
            val accept = when {
                value.reference == null -> true
                finalClassification == Classification.ECHO -> false
                !value.beganDuringOutput -> finalClassification == Classification.HUMAN
                value.interrupted -> true
                !value.durationReached -> false
                finalClassification != Classification.HUMAN -> false
                // Sustained overlapping human speech remains valid if its captured output drained or was replaced.
                currentOutput() != value.reference.identity -> true
                else -> false
            }
            settledSegmentAwaitingNextSpeechId = value.id
            debug("VAD segment=${value.id} final overlap=${finalAnalysis.overlap} divergentResidue=${finalAnalysis.divergentResidue} divergentRun=${finalAnalysis.divergentRun} classified=$finalClassification duration=${value.durationReached} interrupted=${value.interrupted} ${if (accept) "allowed" else "discarded"}")
            accept
        }
        if (emit) emitConfirmed()
        return allowed
    }

    /** Retains exact output text briefly after both natural drain and cancellation. */
    @Synchronized fun outputEnded(reference: OutputReference, naturallyDrained: Boolean) {
        recentExpiry?.cancel()
        recentOutput = reference
        debug("output=${reference.identity} ${if (naturallyDrained) "natural drain" else "cancel"}; tail quarantine armed")
        recentExpiry = scheduler.schedule(OUTPUT_TAIL_QUARANTINE_MS) {
            synchronized(this) {
                if (recentOutput == reference) {
                    recentOutput = null
                    recentExpiry = null
                    debug("output=${reference.identity} tail quarantine expired")
                }
            }
        }
    }

    @Synchronized fun muted() { cancelSegmentTimer(); segment = null }
    @Synchronized fun close() {
        closed = true
        muted()
        completed.clear()
        recentExpiry?.cancel(); recentExpiry = null; recentOutput = null
    }

    private fun applyTranscript(text: String, final: Boolean) {
        var emit = false
        synchronized(this) {
            val value = segment ?: return
            val analysis = analyze(value.reference?.tokens, normalize(text))
            value.classification = analysis.classification
            debug("VAD segment=${value.id} output=${value.reference?.identity} ${if (final) "final" else "partial"} overlap=${analysis.overlap} divergentResidue=${analysis.divergentResidue} divergentRun=${analysis.divergentRun} classified=${value.classification}")
            emit = shouldInterrupt(value)
        }
        if (emit) emitConfirmed()
    }

    private fun durationReached(id: Long, output: PlaybackIdentity) {
        var emit = false
        synchronized(this) {
            val value = segment
            if (closed || value?.id != id || value.reference?.identity != output) return
            timer = null
            value.durationReached = true
            debug("VAD segment=$id output=$output duration=${thresholdMillis}ms reached")
            emit = shouldInterrupt(value)
        }
        if (emit) emitConfirmed()
    }

    /** Must be called under this gate's monitor. */
    private fun shouldInterrupt(value: Segment): Boolean {
        if (!value.durationReached || value.classification != Classification.HUMAN || value.interrupted) return false
        val identity = value.reference?.identity ?: return false
        if (currentOutput() != identity) return false
        value.interrupted = true
        debug("VAD segment=${value.id} output=$identity OutputInterrupted emitted")
        return true
    }

    private fun cancelSegmentTimer() { timer?.cancel(); timer = null }

    companion object {
        const val AEC_CONFIRMATION_MS = 500L
        const val CONSERVATIVE_CONFIRMATION_MS = 1_000L
        const val OUTPUT_TAIL_QUARANTINE_MS = 1_500L
        private const val DIVERGENT_RUN_MIN_TOKENS = 5
        private const val DIVERGENT_RESIDUE_MIN_TOKENS = 6
        private const val FINAL_FALLBACK_MIN_TOKENS = 5

        private fun normalize(text: String): List<String> {
            val operators = text.lowercase()
                .replace(Regex("(?<=\\d),(?=\\d{3}(?:\\D|$))"), "")
                .replace(Regex("(?<=\\d)\\s*-\\s*(?=\\d)"), " minus ")
                .replace(Regex("(?<=\\d)\\s*/\\s*(?=\\d)"), " divided by ")
                .replace("+", " plus ")
                .replace("=", " equals ")
                .replace("×", " times ")
                .replace("*", " times ")
            return operators
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()
                .split(Regex("\\s+"))
                .filter(String::isNotEmpty)
                .flatMap { token -> token.toLongOrNull()?.takeIf { it in 0L..999_999_999L }?.let(::englishInteger) ?: listOf(token) }
        }

        private fun englishInteger(value: Long): List<String> = when {
            value < 20 -> listOf(
                "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
                "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
                "eighteen", "nineteen",
            )[value.toInt()].let(::listOf)
            value < 100 -> {
                val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
                listOf(tens[(value / 10).toInt()]) + if (value % 10 == 0L) emptyList() else englishInteger(value % 10)
            }
            value < 1_000 -> englishInteger(value / 100) + "hundred" + if (value % 100 == 0L) emptyList() else englishInteger(value % 100)
            value < 1_000_000 -> englishInteger(value / 1_000) + "thousand" + if (value % 1_000 == 0L) emptyList() else englishInteger(value % 1_000)
            else -> englishInteger(value / 1_000_000) + "million" + if (value % 1_000_000 == 0L) emptyList() else englishInteger(value % 1_000_000)
        }

        private fun analyze(reference: List<String>?, transcript: List<String>): Analysis {
            if (reference == null) return Analysis(Classification.HUMAN, 0.0, transcript.size, transcript.size)
            if (transcript.isEmpty()) return Analysis(Classification.UNKNOWN, 0.0, 0, 0)
            val contiguous = transcript.size >= 2 && reference.windowed(transcript.size).any { it == transcript }
            val remaining = reference.groupingBy { it }.eachCount().toMutableMap()
            var run = 0
            var longestRun = 0
            val overlapCount = transcript.count { token ->
                val count = remaining[token] ?: 0
                if (count > 0) {
                    remaining[token] = count - 1
                    run = 0
                    true
                } else {
                    run++
                    longestRun = maxOf(longestRun, run)
                    false
                }
            }
            val overlap = overlapCount.toDouble() / transcript.size
            val residue = transcript.size - overlapCount
            // A substantial consecutive residue overrides global overlap; isolated ASR errors do not.
            if (longestRun >= DIVERGENT_RUN_MIN_TOKENS || residue >= DIVERGENT_RESIDUE_MIN_TOKENS) return Analysis(Classification.HUMAN, overlap, residue, longestRun)
            if (contiguous || overlap >= 0.75) return Analysis(Classification.ECHO, overlap, residue, longestRun)
            // Short or mixed evidence stays unknown rather than risking an echo interruption.
            val classification = if (transcript.size >= 3 && overlap < 0.5) Classification.HUMAN else Classification.UNKNOWN
            return Analysis(classification, overlap, residue, longestRun)
        }
    }
}
