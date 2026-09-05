package com.mobileclaw.agent

sealed interface VoiceControlCommand {
    data class Start(val goal: String) : VoiceControlCommand
    data class Replace(val goal: String) : VoiceControlCommand
    data object Cancel : VoiceControlCommand
    data object Status : VoiceControlCommand
}

data class VoiceControlRequest(val generation: Long, val requestId: String, val command: VoiceControlCommand)

sealed interface VoiceControlEvent {
    val generation: Long
    val requestId: String
    data class Accepted(override val generation: Long, override val requestId: String, val taskId: String) : VoiceControlEvent
    data class Status(override val generation: Long, override val requestId: String, val status: VoicePhoneTaskStatus) : VoiceControlEvent
    data class Completed(override val generation: Long, override val requestId: String, val taskId: String, val success: Boolean, val summary: String) : VoiceControlEvent
    data class Rejected(override val generation: Long, override val requestId: String, val reason: String) : VoiceControlEvent
}

fun interface VoiceControlEventSink { fun send(event: VoiceControlEvent) }
