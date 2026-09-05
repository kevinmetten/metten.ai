package com.mobileclaw.realtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.mobileclaw.agent.VoiceAgentCoordinator

/** Application-owned single-session state machine. Generation checks reject stale WebRTC callbacks. */
class ChatGptRealtimeSessionController(
    private val scope: CoroutineScope,
    private val transports: RealtimeVoiceTransportFactory,
    private val coordinator: VoiceAgentCoordinator? = null,
) {
    private val _state = MutableStateFlow(RealtimeVoiceState())
    val state: StateFlow<RealtimeVoiceState> = _state.asStateFlow()
    private var generation = 0L
    private var transport: RealtimeVoiceTransport? = null
    private var connectJob: Job? = null

    @Synchronized fun start(): Boolean {
        if (_state.value.phase in ACTIVE_PHASES) return false
        val currentGeneration = ++generation
        RealtimeVoiceRuntimeDiagnostics.voiceStarted(currentGeneration)
        val currentTransport = transports.create()
        if (currentTransport is RealtimeControlTransport) {
            currentTransport.bindControl(currentGeneration, coordinator?.let { owner -> owner::accept } ?: {})
            coordinator?.beginSession(currentGeneration, currentTransport)
        }
        transport = currentTransport
        _state.value = RealtimeVoiceState(RealtimeVoicePhase.CONNECTING, RealtimeVoiceDiagnostic.CONNECTING)
        connectJob = scope.launch {
            try {
                currentTransport.connect { failure -> disconnected(currentGeneration, currentTransport, failure) }
                synchronized(this@ChatGptRealtimeSessionController) {
                    if (isCurrent(currentGeneration, currentTransport)) {
                        _state.value = RealtimeVoiceState(RealtimeVoicePhase.CONNECTED, RealtimeVoiceDiagnostic.CONNECTED)
                    }
                }
            } catch (_: CancellationException) {
                synchronized(this@ChatGptRealtimeSessionController) {
                    if (isCurrent(currentGeneration, currentTransport)) finishCurrent(currentTransport)
                }
            } catch (failure: RealtimeVoiceException) {
                fail(currentGeneration, currentTransport, failure)
            } catch (_: Throwable) {
                fail(currentGeneration, currentTransport, RealtimeVoiceException(RealtimeVoiceDiagnostic.UNKNOWN_FAILURE, "Live Voice could not connect."))
            }
        }
        return true
    }

    @Synchronized fun setMuted(muted: Boolean) {
        if (_state.value.phase !in setOf(RealtimeVoicePhase.CONNECTED, RealtimeVoicePhase.MUTED)) return
        transport?.setMuted(muted)
        _state.value = RealtimeVoiceState(if (muted) RealtimeVoicePhase.MUTED else RealtimeVoicePhase.CONNECTED, RealtimeVoiceDiagnostic.CONNECTED)
    }

    @Synchronized fun microphonePermissionMissing() {
        if (_state.value.phase !in ACTIVE_PHASES) {
            _state.value = RealtimeVoiceState(
                RealtimeVoicePhase.FAILED,
                RealtimeVoiceDiagnostic.MIC_PERMISSION_MISSING,
                "Microphone permission is required for Live Voice.",
            )
        }
    }

    @Synchronized fun stop() {
        if (_state.value.phase == RealtimeVoicePhase.IDLE) return
        val endedGeneration = generation
        ++generation
        RealtimeVoiceRuntimeDiagnostics.event(RealtimeVoiceRuntimeReason.USER_STOPPED, terminal = true, local = true)
        coordinator?.endSession(endedGeneration)
        _state.value = RealtimeVoiceState(RealtimeVoicePhase.ENDING)
        connectJob?.cancel()
        connectJob = null
        transport?.close()
        transport = null
        _state.value = RealtimeVoiceState()
    }

    @Synchronized private fun disconnected(id: Long, source: RealtimeVoiceTransport, failure: RealtimeVoiceException?) {
        if (!isCurrent(id, source)) return
        coordinator?.endSession(id)
        source.close()
        transport = null
        connectJob = null
        val reason = failure ?: RealtimeVoiceException(
            RealtimeVoiceDiagnostic.REMOTE_CLOSED,
            "The remote Live Voice session ended.",
        )
        recordTerminalIfTransportDidNot(reason)
        _state.value = RealtimeVoiceState(RealtimeVoicePhase.FAILED, reason.diagnostic, reason.message)
    }

    @Synchronized private fun fail(id: Long, source: RealtimeVoiceTransport, failure: RealtimeVoiceException) {
        if (!isCurrent(id, source)) return
        coordinator?.endSession(id)
        source.close()
        transport = null
        connectJob = null
        recordTerminalIfTransportDidNot(failure)
        _state.value = RealtimeVoiceState(RealtimeVoicePhase.FAILED, failure.diagnostic, failure.message)
    }

    private fun isCurrent(id: Long, source: RealtimeVoiceTransport) = generation == id && transport === source
    private fun finishCurrent(source: RealtimeVoiceTransport) {
        source.close()
        transport = null
        connectJob = null
        _state.value = RealtimeVoiceState()
    }

    private fun recordTerminalIfTransportDidNot(failure: RealtimeVoiceException) {
        if (RealtimeVoiceRuntimeDiagnostics.state.value.terminalWasLocal != null) return
        val reason = when (failure.diagnostic) {
            RealtimeVoiceDiagnostic.MIC_FAILURE, RealtimeVoiceDiagnostic.AUDIO_PLAYBACK_FAILURE -> RealtimeVoiceRuntimeReason.AUDIO_FAILURE
            RealtimeVoiceDiagnostic.REMOTE_CLOSED -> RealtimeVoiceRuntimeReason.WEBRTC_PEER_CLOSED
            else -> RealtimeVoiceRuntimeReason.UNKNOWN_FAILURE
        }
        RealtimeVoiceRuntimeDiagnostics.event(reason, terminal = true, local = false)
    }

    private companion object {
        val ACTIVE_PHASES = setOf(RealtimeVoicePhase.CONNECTING, RealtimeVoicePhase.CONNECTED, RealtimeVoicePhase.MUTED, RealtimeVoicePhase.ENDING)
    }
}
