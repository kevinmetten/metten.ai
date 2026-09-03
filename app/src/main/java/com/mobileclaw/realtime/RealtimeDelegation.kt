package com.mobileclaw.realtime

data class RealtimeDelegationRequest(
    val voiceSessionGeneration: Long,
    val delegationId: String,
    val instructionText: String,
)

enum class RealtimeDelegationChannel { COMMENTARY, SPEAKABLE }

data class RealtimeDelegationUpdate(
    val voiceSessionGeneration: Long,
    val delegationId: String,
    val text: String,
    val channel: RealtimeDelegationChannel,
)

interface RealtimeDelegationSink {
    fun send(update: RealtimeDelegationUpdate): Boolean
}

interface RealtimeControlTransport : RealtimeVoiceTransport, RealtimeDelegationSink {
    fun bindControl(generation: Long, listener: (RealtimeDelegationRequest) -> Unit)
}
