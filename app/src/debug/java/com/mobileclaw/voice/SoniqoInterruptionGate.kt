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
    private data class Analysis(
        val classification: Classification,
        val sequenceScore: Double,
        val divergentResidue: Int,
        val divergentRun: Int,
        val trailingDivergent: Int,
        val trailingSize: Int,
    )
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
        debug("VAD segment=${value.id} ended threshold=${if (value.durationReached) "after" else "before"} confirmationMs=$thresholdMillis classification=${value.classification}")
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
                    debug("final without VAD output=${activeOutput.identity} tokens=${tokens.size} sequenceScore=${analysis.sequenceScore} divergentResidue=${analysis.divergentResidue} divergentRun=${analysis.divergentRun} trailingDivergent=${analysis.trailingDivergent}/${analysis.trailingSize} HUMAN OutputInterrupted emitted; allowed")
                    return@synchronized true
                }
                debug("final without VAD output=${reference?.identity} tokens=${tokens.size} sequenceScore=${analysis.sequenceScore} divergentResidue=${analysis.divergentResidue} divergentRun=${analysis.divergentRun} trailingDivergent=${analysis.trailingDivergent}/${analysis.trailingSize} classified=${analysis.classification} ${if (analysis.classification == Classification.ECHO || activeOutput != null) "discarded" else "allowed"}")
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
            debug("VAD segment=${value.id} final tokens=${normalize(text).size} sequenceScore=${finalAnalysis.sequenceScore} divergentResidue=${finalAnalysis.divergentResidue} divergentRun=${finalAnalysis.divergentRun} trailingDivergent=${finalAnalysis.trailingDivergent}/${finalAnalysis.trailingSize} classified=$finalClassification duration=${value.durationReached} interrupted=${value.interrupted} ${if (accept) "allowed" else "discarded"}")
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
            debug("VAD segment=${value.id} output=${value.reference?.identity} ${if (final) "final" else "partial"} tokens=${normalize(text).size} sequenceScore=${analysis.sequenceScore} divergentResidue=${analysis.divergentResidue} divergentRun=${analysis.divergentRun} trailingDivergent=${analysis.trailingDivergent}/${analysis.trailingSize} classified=${value.classification}")
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
        private const val TRAILING_WINDOW_TOKENS = 12
        private const val TRAILING_DIVERGENT_MIN_TOKENS = 7
        private const val TRAILING_DIVERGENT_MIN_RATIO = 0.58
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
            if (reference == null) return Analysis(Classification.HUMAN, 0.0, transcript.size, transcript.size, transcript.size, transcript.size)
            if (transcript.isEmpty()) return Analysis(Classification.UNKNOWN, 0.0, 0, 0, 0, 0)

            // Fit the complete transcript to one contiguous region of the logical output. Skipping output
            // before/after the region is free, while jumping within it, inserting, or substituting costs one.
            // Backtracking therefore identifies words supported by one plausible, ordered echo passage rather
            // than consuming matching words from arbitrary positions in a long response.
            val rows = transcript.size + 1
            val columns = reference.size + 1
            val cost = Array(rows) { IntArray(columns) }
            val move = Array(rows) { ByteArray(columns) }
            for (column in 0 until columns) cost[0][column] = 0
            for (row in 1 until rows) { cost[row][0] = row; move[row][0] = INSERT }
            for (row in 1 until rows) {
                for (column in 1 until columns) {
                    val exact = transcript[row - 1] == reference[column - 1]
                    val diagonal = cost[row - 1][column - 1] + if (exact) 0 else 1
                    val deletion = cost[row][column - 1] + 1
                    val insertion = cost[row - 1][column] + 1
                    val best = minOf(diagonal, deletion, insertion)
                    cost[row][column] = best
                    move[row][column] = when {
                        diagonal == best -> if (exact) MATCH else SUBSTITUTE
                        insertion == best -> INSERT
                        else -> DELETE
                    }
                }
            }
            var row = transcript.size
            var column = (0 until columns).minBy { cost[row][it] }
            val divergent = MutableList(transcript.size) { true }
            while (row > 0) {
                when (move[row][column]) {
                    MATCH -> { divergent[row - 1] = false; row--; column-- }
                    SUBSTITUTE -> { row--; column-- }
                    INSERT -> row--
                    DELETE -> column--
                }
            }
            var run = 0
            var longestRun = 0
            divergent.forEach { isDivergent ->
                if (isDivergent) { run++; longestRun = maxOf(longestRun, run) } else run = 0
            }
            val overlapCount = divergent.count { !it }
            val sequenceScore = overlapCount.toDouble() / transcript.size
            val residue = transcript.size - overlapCount
            val trailing = divergent.takeLast(TRAILING_WINDOW_TOKENS)
            val trailingDivergent = trailing.count { it }
            val localizedDivergence = trailingDivergent >= TRAILING_DIVERGENT_MIN_TOKENS &&
                trailingDivergent.toDouble() / trailing.size >= TRAILING_DIVERGENT_MIN_RATIO
            // Only concentrated divergence overrides strong global echo; scattered substitutions cannot.
            if (longestRun >= DIVERGENT_RUN_MIN_TOKENS || localizedDivergence) {
                return Analysis(Classification.HUMAN, sequenceScore, residue, longestRun, trailingDivergent, trailing.size)
            }
            if (sequenceScore >= 0.75) return Analysis(Classification.ECHO, sequenceScore, residue, longestRun, trailingDivergent, trailing.size)
            // Short or mixed evidence stays unknown rather than risking an echo interruption.
            val classification = if (transcript.size >= 3 && sequenceScore < 0.5) Classification.HUMAN else Classification.UNKNOWN
            return Analysis(classification, sequenceScore, residue, longestRun, trailingDivergent, trailing.size)
        }

        private const val MATCH: Byte = 1
        private const val SUBSTITUTE: Byte = 2
        private const val INSERT: Byte = 3
        private const val DELETE: Byte = 4
    }
}
