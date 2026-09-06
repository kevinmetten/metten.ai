package com.mobileclaw.voice

data class PcmFormat(val sampleRateHz: Int, val channelCount: Int = 1)
data class SynthesizedSpeech(val format: PcmFormat, val samples: FloatArray)

/** Provider-neutral, blocking synthesis boundary. Callers must invoke it off the main thread. */
interface LocalTtsSynthesizer {
    fun initialize(): Result<Unit>
    fun synthesize(text: String): Result<SynthesizedSpeech>
    fun cancel()
    fun release()
}

/** One-chunk look-behind for a future verified streaming synthesizer. */
internal class FinalPcmHoldback {
    private var tail: PcmChunk? = null
    var releasedFrames: Long = 0
        private set

    fun accept(chunk: PcmChunk): PcmChunk? {
        require(chunk.frameCount > 0 && chunk.frameCount == chunk.samples.size)
        val prior = tail
        tail = PcmChunk(chunk.samples.copyOf(), chunk.frameCount)
        if (prior != null) releasedFrames += prior.frameCount
        return prior
    }

    fun finish(): Pair<Long, PcmChunk> {
        val final = checkNotNull(tail) { "Synthesis produced no PCM." }
        tail = null
        return (releasedFrames + final.frameCount) to final
    }

    fun discard() { tail = null; releasedFrames = 0 }
}
