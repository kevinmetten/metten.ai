package com.mobileclaw.voice

/** Pinned Silero VAD consumes whole 512-sample windows; native push_audio discards remainders. */
internal class SoniqoPcmFramer(private val consume: (FloatArray) -> Unit) {
    private val frame = FloatArray(512)
    private var used = 0
    fun accept(samples: FloatArray) {
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(frame.size - used, samples.size - offset)
            samples.copyInto(frame, used, offset, offset + count)
            offset += count; used += count
            if (used == frame.size) { used = 0; consume(frame.copyOf()) }
        }
    }
}
