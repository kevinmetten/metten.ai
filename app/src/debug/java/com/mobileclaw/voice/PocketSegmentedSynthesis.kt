package com.mobileclaw.voice

/** Runs many native Pocket requests as one exact, continuous logical playback. */
internal class PocketSegmentedSynthesis(
    private val playback: PocketStreamingPlayback,
    private val isCurrent: () -> Boolean,
    private val synthesize: (String, (ByteArray, Int, Boolean) -> Unit) -> Unit,
    private val debug: (String) -> Unit = {},
) {
    fun run(text: String) {
        val pending = ArrayDeque(PocketTextSegmentation.initial(text).map { Work(it, 0) })
        debug("Pocket logical chars=${text.length} segments=${pending.size}")
        var ordinal = 0
        while (pending.isNotEmpty() && isCurrent()) {
            val work = pending.removeFirst(); ordinal++
            val segmentId = playback.beginSegment()
            var emitted = false
            debug("Pocket segment=$ordinal chars=${work.text.length}")
            try {
                synthesize(work.text) { pcm, rate, isFinal ->
                    if (!isCurrent()) return@synthesize
                    if (pcm.isNotEmpty() && !emitted) { emitted = true; debug("Pocket segment=$ordinal first PCM") }
                    playback.accept(segmentId, pcm, rate)
                    if (isFinal) debug("Pocket segment=$ordinal provider final")
                }
            } catch (failure: Throwable) {
                val retry = if (!emitted && PocketTextSegmentation.isCacheOverflow(failure)) PocketTextSegmentation.retry(work.text, work.depth) else null
                if (retry == null) throw failure
                debug("Pocket segment=$ordinal cache overflow; split/retry pieces=${retry.size}")
                retry.asReversed().forEach { pending.addFirst(Work(it, work.depth + 1)) }
            }
        }
        if (isCurrent()) { debug("Pocket logical playback finish"); playback.finishLogical() }
    }

    private data class Work(val text: String, val depth: Int)
}
