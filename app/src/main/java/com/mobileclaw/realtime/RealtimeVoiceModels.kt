package com.mobileclaw.realtime

enum class RealtimeVoicePhase { IDLE, CONNECTING, CONNECTED, MUTED, ENDING, FAILED }

enum class RealtimeVoiceDiagnostic {
    NOT_SIGNED_IN,
    AUTH_REFRESH_FAILED,
    MIC_PERMISSION_MISSING,
    CONNECTING,
    CALL_CREATED,
    CONNECTED,
    ROUTE_UNAVAILABLE,
    ACCESS_DENIED,
    USAGE_LIMIT,
    PROTOCOL_REJECTED,
    SIDEBAND_FAILED,
    NETWORK_FAILED,
    MIC_FAILURE,
    AUDIO_PLAYBACK_FAILURE,
    REMOTE_CLOSED,
    USER_STOPPED,
    UNKNOWN_FAILURE,
}

data class RealtimeVoiceState(
    val phase: RealtimeVoicePhase = RealtimeVoicePhase.IDLE,
    val diagnostic: RealtimeVoiceDiagnostic? = null,
    val message: String? = null,
)

class RealtimeVoiceException(
    val diagnostic: RealtimeVoiceDiagnostic,
    message: String,
) : Exception(message)

/** Non-secret identity needed to attach the control sideband to this exact media call. */
data class RealtimeSidebandAttachment(
    val callId: String,
    val requestContext: RealtimeRequestContext,
)

data class RealtimeCallAnswer(
    val sdp: String,
    val sidebandAttachment: RealtimeSidebandAttachment,
) {
    val callId: String get() = sidebandAttachment.callId
}

/** Ephemeral, process-memory identifiers scoped to one transport/session generation. */
data class RealtimeRequestContext(
    val sessionId: String,
    val threadId: String,
    val realtimeSessionId: String,
) {
    companion object {
        fun create(): RealtimeRequestContext = RealtimeRequestContext(
            sessionId = java.util.UUID.randomUUID().toString(),
            threadId = java.util.UUID.randomUUID().toString(),
            realtimeSessionId = java.util.UUID.randomUUID().toString(),
        )
    }
}

interface RealtimeVoiceTransport {
    suspend fun connect(onDisconnected: (RealtimeVoiceException?) -> Unit)
    fun setMuted(muted: Boolean)
    fun close()
}

fun interface RealtimeVoiceTransportFactory {
    fun create(): RealtimeVoiceTransport
}
