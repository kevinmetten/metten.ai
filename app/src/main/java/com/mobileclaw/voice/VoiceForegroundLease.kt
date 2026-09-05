package com.mobileclaw.voice

/** Generation-aware owner which serializes the real Android foreground operations. */
fun interface VoiceForegroundLease {
    fun acquire(generation: Long): Boolean

    fun release(generation: Long) = Unit
}

class SerializedVoiceForegroundLease(
    private val startForeground: () -> Unit,
    private val stopForeground: () -> Unit,
) : VoiceForegroundLease {
    private val monitor = Any()
    private var highestGeneration = 0L
    private var owner: Long? = null

    override fun acquire(generation: Long): Boolean = synchronized(monitor) {
        if (generation < highestGeneration) return false
        if (generation == highestGeneration) return owner == generation
        owner?.let { stopForeground() }
        highestGeneration = generation
        owner = generation
        startForeground()
        true
    }

    override fun release(generation: Long) = synchronized(monitor) {
        if (owner != generation) return
        owner = null
        stopForeground()
    }
}
