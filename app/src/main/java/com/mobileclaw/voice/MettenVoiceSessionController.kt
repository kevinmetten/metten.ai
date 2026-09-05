package com.mobileclaw.voice

import com.mobileclaw.agent.VoiceAgentCoordinator
import com.mobileclaw.agent.VoiceControlEvent
import com.mobileclaw.agent.VoiceControlEventSink
import com.mobileclaw.agent.VoiceControlRequest
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
        !offlineTts.available -> offlineTts.reason
        !chatGptTextReady -> "ChatGPT text is not ready. Sign in and select ChatGPT."
        else -> null
    }
}

/** Application-owned, turn-based Voice state machine. Phone execution remains orthogonal in VoiceAgentCoordinator. */
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
    private var input: SpeechInputEngine? = null
    private var output: SpeechOutputEngine? = null
    private var turnJob: Job? = null
    private var muted = false
    private val context = ArrayDeque<VoiceConversationTurn>()

    @Synchronized fun readiness(): MettenVoiceReadiness {
        val probeInput = input ?: inputFactory().also { input = it }
        val probeOutput = output ?: outputFactory().also { output = it }
        return MettenVoiceReadiness(microphonePermission(), probeInput.capability(), probeOutput.capability(), chatGptTextReady())
    }

    @Synchronized fun start(): Boolean {
        if (_state.value.phase !in setOf(MettenVoicePhase.IDLE, MettenVoicePhase.FAILED)) return false
        val ready = readiness()
        if (!ready.microphonePermission || !ready.onDeviceSpeech.available || !ready.chatGptTextReady) {
            _state.value = MettenVoiceState(MettenVoicePhase.FAILED, ready.reason); return false
        }
        val id = ++generation
        muted = false
        context.clear()
        _state.value = MettenVoiceState(MettenVoicePhase.STARTING)
        var accepted = true
        output?.initialize { tts ->
            synchronized(this) {
                if (id != generation) return@synchronized
                if (!tts.available) { accepted = false; _state.value = MettenVoiceState(MettenVoicePhase.FAILED, tts.reason); return@synchronized }
                coordinator.beginSession(id, VoiceControlEventSink { event -> onPhoneEvent(id, event) })
                foreground(true)
                listen(id)
            }
        }
        return accepted
    }

    @Synchronized fun setMuted(value: Boolean) {
        if (_state.value.phase in setOf(MettenVoicePhase.IDLE, MettenVoicePhase.ENDING, MettenVoicePhase.FAILED)) return
        muted = value
        if (value) { input?.stopListening(); _state.value = MettenVoiceState(MettenVoicePhase.MUTED) }
        else listen(generation)
    }

    @Synchronized fun microphonePermissionMissing() {
        if (_state.value.phase == MettenVoicePhase.IDLE || _state.value.phase == MettenVoicePhase.FAILED)
            _state.value = MettenVoiceState(MettenVoicePhase.FAILED, "Microphone permission is required for Metten Voice.")
    }

    @Synchronized fun stop() {
        if (_state.value.phase == MettenVoicePhase.IDLE) return
        val ended = generation
        ++generation
        _state.value = MettenVoiceState(MettenVoicePhase.ENDING)
        muted = false
        turnJob?.cancel(); turnJob = null
        input?.stopListening(); input?.release(); input = null
        output?.stop(); output?.release(); output = null
        context.clear()
        coordinator.endSession(ended)
        foreground(false)
        _state.value = MettenVoiceState()
    }

    @Synchronized private fun listen(id: Long) {
        if (id != generation || muted || input == null) return
        _state.value = MettenVoiceState(MettenVoicePhase.LISTENING)
        input?.startListening { event -> onInput(id, event) }
    }

    private fun onInput(id: Long, event: SpeechInputEvent) {
        synchronized(this) { if (id != generation || muted || _state.value.phase != MettenVoicePhase.LISTENING) return }
        when (event) {
            is SpeechInputEvent.Final -> processTurn(id, event.text)
            is SpeechInputEvent.RecoverableError -> scope.launch { delay(event.retryDelayMillis.coerceIn(300, 2_000)); synchronized(this@MettenVoiceSessionController) { if (id == generation && !muted) listen(id) } }
            is SpeechInputEvent.FatalError -> fail(id, event.reason)
            else -> Unit // Partial text is intentionally neither logged nor persisted.
        }
    }

    private fun processTurn(id: Long, text: String) {
        synchronized(this) { if (id != generation) return; input?.stopListening(); _state.value = MettenVoiceState(MettenVoicePhase.THINKING) }
        turnJob = scope.launch {
            try {
                val decision = brain.decide(text, synchronized(this@MettenVoiceSessionController) { context.toList() })
                synchronized(this@MettenVoiceSessionController) {
                    if (id != generation) return@launch
                    decision.spokenText?.let { remember(text, it) }
                }
                decision.phoneCommand?.let { coordinator.accept(VoiceControlRequest(id, UUID.randomUUID().toString(), it)) }
                    ?: decision.spokenText?.let { speak(id, it) }
                    ?: listen(id)
            } catch (_: CancellationException) {
            } catch (_: Throwable) { fail(id, "ChatGPT text could not process that turn.") }
        }
    }

    private fun onPhoneEvent(id: Long, event: VoiceControlEvent) {
        synchronized(this) { if (id != generation || event.generation != id) return }
        when (event) {
            is VoiceControlEvent.Accepted -> speak(id, "I'm working on that.")
            is VoiceControlEvent.Rejected -> speak(id, if (event.reason == "PHONE_CONTROL_NOT_READY") "Phone control is not ready." else "Another phone task is already active.")
            is VoiceControlEvent.Status -> speak(id, event.status.summary)
            is VoiceControlEvent.Completed -> speak(id, if (event.success) event.summary else "The phone task did not complete: ${event.summary}")
        }
    }

    @Synchronized private fun speak(id: Long, text: String) {
        if (id != generation || muted) return
        input?.stopListening() // Conservative self-loop policy: recognition stays off during TTS.
        _state.value = MettenVoiceState(MettenVoicePhase.SPEAKING)
        output?.speak(text) { event ->
            synchronized(this) {
                if (id != generation) return@synchronized
                when (event) {
                    SpeechOutputEvent.Completed -> if (!muted) listen(id)
                    is SpeechOutputEvent.Failed -> fail(id, event.reason)
                    SpeechOutputEvent.Started -> Unit
                }
            }
        }
    }

    @Synchronized private fun fail(id: Long, reason: String) {
        if (id != generation) return
        input?.stopListening()
        _state.value = MettenVoiceState(MettenVoicePhase.FAILED, reason)
        foreground(false)
    }
    private fun remember(user: String, assistant: String) { context += VoiceConversationTurn(user, assistant); while (context.size > MAX_TURNS) context.removeFirst() }
    private companion object { const val MAX_TURNS = 8 }
}
