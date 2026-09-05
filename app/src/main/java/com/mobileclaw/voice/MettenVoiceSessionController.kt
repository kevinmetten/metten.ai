package com.mobileclaw.voice

import com.mobileclaw.agent.VoiceAgentCoordinator
import com.mobileclaw.agent.VoiceControlCancellationDisposition
import com.mobileclaw.agent.VoiceControlEvent
import com.mobileclaw.agent.VoiceControlEventSink
import com.mobileclaw.agent.VoiceControlRequest
import com.mobileclaw.agent.VoiceControlTerminalState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class MettenVoicePhase { IDLE, STARTING, LISTENING, THINKING, SPEAKING, MUTED, ENDING, FAILED }
data class MettenVoiceState(val phase: MettenVoicePhase = MettenVoicePhase.IDLE, val message: String? = null)
data class MettenVoiceReadiness(
    val microphonePermission: Boolean,
    val onDeviceSpeech: SpeechCapability,
    val offlineTts: SpeechCapability,
    val chatGptTextReady: Boolean,
) {
    val ready get() = microphonePermission && onDeviceSpeech.available && offlineTts.available && chatGptTextReady
    val reason get() = when {
        !microphonePermission -> "Microphone permission is required for Metten Voice."
        !onDeviceSpeech.available -> onDeviceSpeech.reason
        !offlineTts.available && !offlineTts.initializing -> offlineTts.reason
        !chatGptTextReady -> "ChatGPT text is not ready. Sign in and select ChatGPT."
        else -> null
    }
}

/** Application-owned turn loop. Phone execution remains orthogonal in [VoiceAgentCoordinator]. */
class MettenVoiceSessionController(
    private val scope: CoroutineScope,
    private val inputFactory: () -> SpeechInputEngine,
    private val outputFactory: () -> SpeechOutputEngine,
    private val brain: VoiceTurnBrain,
    private val coordinator: VoiceAgentCoordinator,
    private val microphonePermission: () -> Boolean,
    private val chatGptTextReady: () -> Boolean,
    private val foreground: (Boolean) -> Unit = {},
) {
    private val _state = MutableStateFlow(MettenVoiceState())
    val state: StateFlow<MettenVoiceState> = _state.asStateFlow()
    private var generation = 0L
    private var listenAttempt = 0L
    private var input: SpeechInputEngine? = null
    private var output: SpeechOutputEngine? = null
    private var turnJob: Job? = null
    private var retryJob: Job? = null
    private var muted = false
    private val context = ArrayDeque<VoiceConversationTurn>()
    private val pendingPhoneTurns = mutableMapOf<String, String>()

    @Synchronized fun readiness(): MettenVoiceReadiness {
        val probeInput = input ?: inputFactory().also { input = it }
        val probeOutput = output ?: outputFactory().also { output = it }
        return MettenVoiceReadiness(microphonePermission(), probeInput.capability(), probeOutput.capability(), chatGptTextReady())
    }

    fun start(): Boolean {
        val startup = synchronized(this) {
            if (_state.value.phase !in setOf(MettenVoicePhase.IDLE, MettenVoicePhase.FAILED)) return false
            val ready = readiness()
            if (!ready.microphonePermission || !ready.onDeviceSpeech.available || !ready.chatGptTextReady ||
                (!ready.offlineTts.available && !ready.offlineTts.initializing)) {
                _state.value = MettenVoiceState(MettenVoicePhase.FAILED, ready.reason)
                return false
            }
            val id = ++generation
            invalidateListeningLocked()
            muted = false
            context.clear()
            pendingPhoneTurns.clear()
            _state.value = MettenVoiceState(MettenVoicePhase.STARTING)
            id to requireNotNull(output)
        }
        val (id, engine) = startup
        engine.initialize { capability -> onOutputInitialized(id, capability) }
        return true // Accepted startup; Android TTS initialization completes asynchronously.
    }

    private fun onOutputInitialized(id: Long, capability: SpeechCapability) {
        if (!capability.available) { fail(id, capability.reason ?: "Offline Text-to-Speech is unavailable."); return }
        if (!synchronized(this) { id == generation && _state.value.phase == MettenVoicePhase.STARTING }) return
        coordinator.beginSession(id, VoiceControlEventSink { event -> onPhoneEvent(id, event) })
        val activated = synchronized(this) { id == generation && _state.value.phase == MettenVoicePhase.STARTING }
        if (!activated) { coordinator.endSession(id); return }
        foreground(true)
        listen(id)
    }

    fun setMuted(value: Boolean) {
        val shouldListen = synchronized(this) {
            if (_state.value.phase in setOf(MettenVoicePhase.IDLE, MettenVoicePhase.ENDING, MettenVoicePhase.FAILED)) return
            muted = value
            if (value) {
                invalidateListeningLocked(); input?.stopListening(); _state.value = MettenVoiceState(MettenVoicePhase.MUTED)
            }
            !value
        }
        if (shouldListen) listen(currentGeneration())
    }

    @Synchronized fun microphonePermissionMissing() {
        if (_state.value.phase == MettenVoicePhase.IDLE || _state.value.phase == MettenVoicePhase.FAILED)
            _state.value = MettenVoiceState(MettenVoicePhase.FAILED, "Microphone permission is required for Metten Voice.")
    }

    fun stop() {
        val ended = synchronized(this) {
            if (_state.value.phase == MettenVoicePhase.IDLE) return
            _state.value = MettenVoiceState(MettenVoicePhase.ENDING)
            generation
        }
        terminateExact(ended, MettenVoiceState())
    }

    private fun listen(id: Long) {
        val attemptAndInput = synchronized(this) {
            if (id != generation || muted || input == null || _state.value.phase in setOf(MettenVoicePhase.THINKING, MettenVoicePhase.SPEAKING, MettenVoicePhase.ENDING, MettenVoicePhase.FAILED)) return
            retryJob?.cancel(); retryJob = null
            val attempt = ++listenAttempt
            _state.value = MettenVoiceState(MettenVoicePhase.LISTENING)
            attempt to requireNotNull(input)
        }
        val (attempt, engine) = attemptAndInput
        engine.startListening { event -> onInput(id, attempt, event) }
    }

    private fun onInput(id: Long, attempt: Long, event: SpeechInputEvent) {
        if (!synchronized(this) { id == generation && attempt == listenAttempt && !muted && _state.value.phase == MettenVoicePhase.LISTENING }) return
        when (event) {
            is SpeechInputEvent.Final -> {
                synchronized(this) { if (id != generation || attempt != listenAttempt) return; invalidateListeningLocked() }
                processTurn(id, event.text)
            }
            is SpeechInputEvent.RecoverableError -> scheduleRetry(id, attempt, event.retryDelayMillis)
            is SpeechInputEvent.FatalError -> fail(id, event.reason)
            else -> Unit // Partial text is deliberately not logged or persisted.
        }
    }

    private fun scheduleRetry(id: Long, attempt: Long, delayMillis: Long) {
        synchronized(this) {
            if (id != generation || attempt != listenAttempt || _state.value.phase != MettenVoicePhase.LISTENING) return
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(delayMillis.coerceIn(300, 2_000))
                val rearm = synchronized(this@MettenVoiceSessionController) {
                    id == generation && attempt == listenAttempt && !muted && _state.value.phase == MettenVoicePhase.LISTENING
                }
                if (rearm) listen(id)
            }
        }
    }

    private fun processTurn(id: Long, text: String) {
        synchronized(this) {
            if (id != generation) return
            input?.stopListening()
            _state.value = MettenVoiceState(MettenVoicePhase.THINKING)
        }
        turnJob = scope.launch {
            try {
                val turnContext = synchronized(this@MettenVoiceSessionController) {
                    VoiceTurnContext(context.toList(), coordinator.status.value)
                }
                val decision = brain.decide(text, turnContext)
                if (!synchronized(this@MettenVoiceSessionController) { id == generation }) return@launch
                val command = decision.phoneCommand
                if (command != null) {
                    val requestId = UUID.randomUUID().toString()
                    synchronized(this@MettenVoiceSessionController) { if (id == generation) pendingPhoneTurns[requestId] = text else return@launch }
                    coordinator.accept(VoiceControlRequest(id, requestId, command))
                } else if (decision.spokenText != null) {
                    synchronized(this@MettenVoiceSessionController) { remember(text, decision.spokenText) }
                    speak(id, decision.spokenText)
                } else listen(id)
            } catch (_: CancellationException) {
            } catch (_: Throwable) { fail(id, "ChatGPT text could not process that turn.") }
        }
    }

    private fun onPhoneEvent(id: Long, event: VoiceControlEvent) {
        if (!synchronized(this) { id == generation && event.generation == id }) return
        when (event) {
            is VoiceControlEvent.Accepted -> {
                synchronized(this) { pendingPhoneTurns.remove(event.requestId)?.let { remember(it, ACKNOWLEDGEMENT) } }
                speak(id, ACKNOWLEDGEMENT)
            }
            is VoiceControlEvent.Rejected -> {
                synchronized(this) { pendingPhoneTurns.remove(event.requestId) }
                speak(id, if (event.reason == "PHONE_CONTROL_NOT_READY") "Phone control is not ready." else "Another phone task is already active.")
            }
            is VoiceControlEvent.Status -> {
                synchronized(this) { pendingPhoneTurns.remove(event.requestId)?.let { remember(it, event.status.summary) } }
                speak(id, event.status.summary)
            }
            is VoiceControlEvent.Completed -> {
                val spoken = when (event.state) {
                    VoiceControlTerminalState.SUCCEEDED -> event.summary
                    VoiceControlTerminalState.FAILED -> "The phone task did not complete: ${event.summary}"
                    VoiceControlTerminalState.CANCELLED -> if (event.cancellation == VoiceControlCancellationDisposition.USER_REQUEST) "Phone task cancelled." else null
                }
                synchronized(this) { pendingPhoneTurns.remove(event.requestId)?.let { user -> spoken?.let { remember(user, it) } } }
                spoken?.let { speak(id, it) }
            }
        }
    }

    private fun speak(id: Long, text: String) {
        val engine = synchronized(this) {
            if (id != generation || muted) return
            invalidateListeningLocked()
            input?.stopListening() // Conservative self-loop policy.
            _state.value = MettenVoiceState(MettenVoicePhase.SPEAKING)
            output
        } ?: return
        engine.speak(text) callback@{ event ->
            if (!synchronized(this) { id == generation }) return@callback
            when (event) {
                SpeechOutputEvent.Completed -> listen(id)
                is SpeechOutputEvent.Failed -> fail(id, event.reason)
                SpeechOutputEvent.Started -> Unit
            }
        }
    }

    private fun fail(id: Long, reason: String) = terminateExact(id, MettenVoiceState(MettenVoicePhase.FAILED, reason))

    /** Invalidates under our lock, then invokes coordinator/engine/FGS callbacks without holding it. */
    private fun terminateExact(id: Long, terminal: MettenVoiceState) {
        val resources = synchronized(this) {
            if (id != generation) return
            generation++
            invalidateListeningLocked()
            val job = turnJob.also { turnJob = null }
            val speechInput = input.also { input = null }
            val speechOutput = output.also { output = null }
            muted = false
            context.clear()
            pendingPhoneTurns.clear()
            _state.value = terminal
            Triple(job, speechInput, speechOutput)
        }
        resources.first?.cancel()
        resources.second?.stopListening(); resources.second?.release()
        resources.third?.stop(); resources.third?.release()
        coordinator.endSession(id)
        foreground(false)
    }

    private fun invalidateListeningLocked() {
        listenAttempt++
        retryJob?.cancel(); retryJob = null
    }
    @Synchronized private fun currentGeneration() = generation
    private fun remember(user: String, assistant: String) { context += VoiceConversationTurn(user, assistant); while (context.size > MAX_TURNS) context.removeFirst() }
    private companion object { const val MAX_TURNS = 8; const val ACKNOWLEDGEMENT = "I'm working on that." }
}
