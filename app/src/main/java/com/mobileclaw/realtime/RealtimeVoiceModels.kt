package com.mobileclaw.realtime

enum class RealtimeVoicePhase { IDLE, CONNECTING, CONNECTED, MUTED, ENDING, FAILED }

enum class RealtimeVoiceDiagnostic {
    NOT_SIGNED_IN,
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

data class RealtimeCallAnswer(val sdp: String, val callId: String)

interface RealtimeVoiceTransport {
    suspend fun connect(onDisconnected: (RealtimeVoiceException?) -> Unit)
    fun setMuted(muted: Boolean)
    fun close()
}

fun interface RealtimeVoiceTransportFactory {
    fun create(): RealtimeVoiceTransport
}
