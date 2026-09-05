package com.mobileclaw.realtime

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class RealtimeVoiceRuntimeReason {
    USER_STOPPED,
    WEBRTC_PEER_FAILED,
    WEBRTC_PEER_CLOSED,
    EVENTS_DATACHANNEL_CLOSED,
    SIDEBAND_FAILED,
    CONNECTION_TIMEOUT,
    AUDIO_FAILURE,
    SESSION_REPLACED,
    PROCESS_RESTARTED_WHILE_ACTIVE,
    UNKNOWN_FAILURE,
}

data class RealtimeVoiceRuntimeDiagnosticState(
    val processEpoch: String = "uninitialized",
    val generation: Long = 0,
    val peerState: String = "NEW",
    val eventsDataChannelState: String = "NEW",
    val lastReason: RealtimeVoiceRuntimeReason? = null,
    val terminalWasLocal: Boolean? = null,
)

/** Privacy-safe process/transport state. It deliberately contains no request or user data. */
object RealtimeVoiceRuntimeDiagnostics {
    private const val PREFS = "realtime_voice_runtime"
    private const val ACTIVE = "voice_active"
    private const val EPOCH = "process_epoch"
    private const val LAST_REASON = "last_reason"
    private const val LAST_LOCAL = "last_local"
    private val _state = MutableStateFlow(RealtimeVoiceRuntimeDiagnosticState())
    val state: StateFlow<RealtimeVoiceRuntimeDiagnosticState> = _state.asStateFlow()
    private var context: Context? = null

    fun initialize(context: Context) {
        val app = context.applicationContext
        val preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val interrupted = preferences.getBoolean(ACTIVE, false)
        val priorReason = preferences.getString(LAST_REASON, null)?.let { runCatching { RealtimeVoiceRuntimeReason.valueOf(it) }.getOrNull() }
        val epoch = UUID.randomUUID().toString().take(8)
        this.context = app
        _state.value = RealtimeVoiceRuntimeDiagnosticState(
            processEpoch = epoch,
            lastReason = if (interrupted) RealtimeVoiceRuntimeReason.PROCESS_RESTARTED_WHILE_ACTIVE else priorReason,
            terminalWasLocal = if (interrupted) false else preferences.takeIf { priorReason != null }?.getBoolean(LAST_LOCAL, false),
        )
        preferences.edit().putString(EPOCH, epoch).putBoolean(ACTIVE, false).commit()
    }

    fun voiceStarted(generation: Long) {
        _state.value = _state.value.copy(generation = generation, peerState = "NEW", eventsDataChannelState = "NEW", lastReason = null, terminalWasLocal = null)
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(ACTIVE, true)?.commit()
    }

    fun peerState(value: String) { _state.value = _state.value.copy(peerState = value.take(40)) }
    fun dataChannelState(value: String) { _state.value = _state.value.copy(eventsDataChannelState = value.take(40)) }

    fun event(reason: RealtimeVoiceRuntimeReason, terminal: Boolean, local: Boolean = false) {
        _state.value = _state.value.copy(lastReason = reason, terminalWasLocal = local.takeIf { terminal })
        if (terminal) context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean(ACTIVE, false)?.putString(LAST_REASON, reason.name)?.putBoolean(LAST_LOCAL, local)?.commit()
    }
}

/** Makes native terminal callbacks idempotent and suppresses callbacks caused by local close(). */
internal class RealtimeVoiceTerminalGate {
    private val localClose = java.util.concurrent.atomic.AtomicBoolean(false)
    private val terminalDelivered = java.util.concurrent.atomic.AtomicBoolean(false)
    fun beginLocalClose(): Boolean = localClose.compareAndSet(false, true)
    fun claimRemoteTerminal(): Boolean = !localClose.get() && terminalDelivered.compareAndSet(false, true)
}

internal enum class RealtimeNativeTransportEvent { WEBRTC_PEER_FAILED, WEBRTC_PEER_CLOSED, EVENTS_DATACHANNEL_CLOSED }

internal object RealtimeVoiceTransportPolicy {
    fun isFatal(event: RealtimeNativeTransportEvent): Boolean = when (event) {
        RealtimeNativeTransportEvent.WEBRTC_PEER_FAILED,
        RealtimeNativeTransportEvent.WEBRTC_PEER_CLOSED -> true
        RealtimeNativeTransportEvent.EVENTS_DATACHANNEL_CLOSED -> false
    }
}
