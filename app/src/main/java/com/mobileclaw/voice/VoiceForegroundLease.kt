package com.mobileclaw.voice

sealed interface VoiceForegroundAcquireResult {
    data object Acquired : VoiceForegroundAcquireResult
    data object AlreadyOwned : VoiceForegroundAcquireResult
    data object StaleOrEnded : VoiceForegroundAcquireResult
    data class Failed(val reason: String) : VoiceForegroundAcquireResult
}

sealed interface VoiceForegroundReleaseResult {
    data object Released : VoiceForegroundReleaseResult
    data object NoOp : VoiceForegroundReleaseResult
    data class Failed(val reason: String) : VoiceForegroundReleaseResult
}

interface VoiceForegroundLease {
    fun acquire(generation: Long): VoiceForegroundAcquireResult
    fun release(generation: Long): VoiceForegroundReleaseResult
}

class SerializedVoiceForegroundLease(
    private val startForeground: () -> Unit,
    private val stopForeground: () -> Unit,
) : VoiceForegroundLease {
    private val monitor = Any()
    private var highestGeneration = 0L
    internal sealed interface PhysicalState {
        data object None : PhysicalState
        data class Active(val ownerGeneration: Long) : PhysicalState
        data class StopFailed(val ownerGeneration: Long, val reason: String) : PhysicalState
    }
    private var physicalState: PhysicalState = PhysicalState.None

    override fun acquire(generation: Long): VoiceForegroundAcquireResult = synchronized(monitor) {
        if (generation < highestGeneration) return VoiceForegroundAcquireResult.StaleOrEnded
        if (generation == highestGeneration) {
            return if (physicalState == PhysicalState.Active(generation)) VoiceForegroundAcquireResult.AlreadyOwned
            else VoiceForegroundAcquireResult.StaleOrEnded
        }
        highestGeneration = generation
        when (val state = physicalState) {
            PhysicalState.None -> Unit
            is PhysicalState.Active -> stopPrior(state.ownerGeneration)?.let { return failure(it) }
            is PhysicalState.StopFailed -> stopPrior(state.ownerGeneration)?.let { return failure(it) }
        }
        try {
            startForeground()
            physicalState = PhysicalState.Active(generation)
            VoiceForegroundAcquireResult.Acquired
        } catch (_: Exception) {
            physicalState = PhysicalState.None
            failure(safeMessage("Voice foreground service could not start."))
        }
    }

    override fun release(generation: Long): VoiceForegroundReleaseResult = synchronized(monitor) {
        val state = physicalState
        val owner = when (state) {
            is PhysicalState.Active -> state.ownerGeneration
            is PhysicalState.StopFailed -> state.ownerGeneration
            PhysicalState.None -> return VoiceForegroundReleaseResult.NoOp
        }
        if (owner != generation) return VoiceForegroundReleaseResult.NoOp
        try {
            stopForeground()
            physicalState = PhysicalState.None
            VoiceForegroundReleaseResult.Released
        } catch (_: Exception) {
            val reason = safeMessage("Voice foreground service could not stop.")
            physicalState = PhysicalState.StopFailed(generation, reason)
            VoiceForegroundReleaseResult.Failed(reason)
        }
    }

    private fun stopPrior(owner: Long): String? = try {
        stopForeground()
        physicalState = PhysicalState.None
        null
    } catch (_: Exception) {
        val reason = safeMessage("Voice foreground service could not stop.")
        physicalState = PhysicalState.StopFailed(owner, reason)
        reason
    }

    private fun failure(reason: String) = VoiceForegroundAcquireResult.Failed(reason)
    private fun safeMessage(fallback: String) = fallback.take(160)

    internal fun stateSnapshot() = synchronized(monitor) { physicalState }
    internal fun highestGenerationSnapshot() = synchronized(monitor) { highestGeneration }
}
