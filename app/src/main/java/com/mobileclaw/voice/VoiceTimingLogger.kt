package com.mobileclaw.voice

/** Platform-neutral sink for non-sensitive, monotonic Voice latency diagnostics. */
fun interface VoiceTimingLogger {
    fun log(message: String)

    companion object {
        val NONE = VoiceTimingLogger { }
    }
}
