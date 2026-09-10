package com.mobileclaw.voice

import com.mobileclaw.agent.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val foregroundLease: VoiceForegroundLease = object : VoiceForegroundLease {
        override fun acquire(generation: Long) = VoiceForegroundAcquireResult.Acquired
        override fun release(generation: Long) = VoiceForegroundReleaseResult.NoOp
    },
) {
    private data class StartupToken(val generation: Long, val epoch: Long)
    private data class OutputToken(val generation: Long, val outputId: Long)
    private data class TurnToken(val generation: Long, val turnId: Long)
    private data class TurnJobHandle(val token: TurnToken, val job: Job)
    private data class PreparedListen(val generation: Long, val attempt: Long, val engine: SpeechInputEngine)
    private data class PreparedSpeech(val token: OutputToken, val engine: SpeechOutputEngine, val input: SpeechInputEngine?, val text: String)
    private sealed interface StartupActivationOutcome {
        data object Stale : StartupActivationOutcome
        data object ActivatedMuted : StartupActivationOutcome
        data class ActivatedListening(val prepared: PreparedListen) : StartupActivationOutcome
        data object Inconsistent : StartupActivationOutcome
    }
    private sealed interface ListenCause {
        data class Startup(val token: StartupToken) : ListenCause
        data class OutputCompleted(val token: OutputToken) : ListenCause
        data object Unmuted : ListenCause
        data class Retry(val priorAttempt: Long) : ListenCause
        data class EchoConsumed(val priorAttempt: Long) : ListenCause
        data class TurnCompletedWithoutSpeech(val token: TurnToken) : ListenCause
        data object OwnerSettled : ListenCause
    }
    private enum class MutedResumeOwner { STARTUP, LISTENING, THINKING, OUTPUT }
    private data class TerminationToken(val invalidatedGeneration: Long, val epoch: Long, val original: MettenVoiceState)
    private data class TerminationResources(
        val job: TurnJobHandle?, val input: SpeechInputEngine?, val output: SpeechOutputEngine?, val token: TerminationToken,
    )

    private val _state = MutableStateFlow(MettenVoiceState())
    val state: StateFlow<MettenVoiceState> = _state.asStateFlow()
    private val inputEngineGate = Any()
    private var generation = 0L
    private var listenAttempt = 0L
    private var nextTurnId = 0L
    private var input: SpeechInputEngine? = null
    private var output: SpeechOutputEngine? = null
    private var activeTurn: TurnToken? = null
    private var turnJob: TurnJobHandle? = null
    private var retryJob: Job? = null
    private var startupEpoch = 0L
    private var lifecycleEpoch = 0L
    private var startupToken: StartupToken? = null
    private var outputId = 0L
    private var activeOutput: OutputToken? = null
    private var pendingEchoText: String? = null
    private var startFailures = 0
    private var muted = false
    private var mutedResumeOwner = MutedResumeOwner.LISTENING
    private val context = ArrayDeque<VoiceConversationTurn>()
    private val pendingPhoneTurns = mutableMapOf<String, String>()
    private val acceptedPhoneTurns = mutableMapOf<String, String>()

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
                _state.value = MettenVoiceState(MettenVoicePhase.FAILED, ready.reason); return false
            }
            val id = ++generation
            lifecycleEpoch++
            val token = StartupToken(id, ++startupEpoch)
            startupToken = token
            invalidateListeningLocked()
            activeTurn = null; turnJob = null; activeOutput = null
            muted = false
            context.clear(); pendingPhoneTurns.clear(); acceptedPhoneTurns.clear(); pendingEchoText = null
            _state.value = MettenVoiceState(MettenVoicePhase.STARTING)
            token to requireNotNull(output)
        }
        val (token, engine) = startup
        when (val result = foregroundLease.acquire(token.generation)) {
            VoiceForegroundAcquireResult.Acquired, VoiceForegroundAcquireResult.AlreadyOwned -> Unit
            VoiceForegroundAcquireResult.StaleOrEnded -> { terminateExact(token.generation, MettenVoiceState(MettenVoicePhase.FAILED, "Voice foreground service rejected an ended generation.")); return false }
            is VoiceForegroundAcquireResult.Failed -> { terminateExact(token.generation, MettenVoiceState(MettenVoicePhase.FAILED, result.reason)); return false }
        }
        if (!synchronized(this) { startupToken == token && generation == token.generation }) { foregroundLease.release(token.generation); return false }
        try { engine.initialize { capability -> onOutputInitialized(token, capability) } }
        catch (failure: Exception) { fail(token.generation, failure.message ?: "Offline Text-to-Speech could not initialize."); return false }
        return true
    }

    private fun onOutputInitialized(token: StartupToken, capability: SpeechCapability) {
        if (!synchronized(this) { startupToken == token && token.generation == generation }) return
        if (!capability.available) { fail(token.generation, capability.reason ?: "Offline Text-to-Speech is unavailable."); return }
        when (coordinator.beginSession(token.generation, VoiceControlEventSink { onPhoneEvent(token.generation, it) })) {
            VoiceSessionAdmission.ACTIVATED -> {
                val outcome = synchronized(this) {
                    if (startupToken != token || token.generation != generation) StartupActivationOutcome.Stale
                    else {
                        startupToken = null
                        if (muted) {
                            refreshMutedOwnerLocked()
                            _state.value = MettenVoiceState(MettenVoicePhase.MUTED)
                            StartupActivationOutcome.ActivatedMuted
                        } else {
                            prepareListenLocked(token.generation, ListenCause.Startup(token))
                                ?.let { StartupActivationOutcome.ActivatedListening(it) }
                                ?: StartupActivationOutcome.Inconsistent
                        }
                    }
                }
                when (outcome) {
                    StartupActivationOutcome.Stale -> coordinator.endSession(token.generation)
                    StartupActivationOutcome.ActivatedMuted -> Unit
                    is StartupActivationOutcome.ActivatedListening -> submitPreparedListen(outcome.prepared)
                    StartupActivationOutcome.Inconsistent -> fail(token.generation, "Voice could not start its first listening attempt.")
                }
            }
            VoiceSessionAdmission.ALREADY_ACTIVE, VoiceSessionAdmission.STALE_OR_ENDED -> Unit
        }
    }

    fun setMuted(value: Boolean) {
        var stop: SpeechInputEngine? = null
        var prepared: PreparedListen? = null
        var resumeContinuous: Triple<Long, Long, ContinuousSpeechInputEngine>? = null
        synchronized(this) {
            if (_state.value.phase in setOf(MettenVoicePhase.IDLE, MettenVoicePhase.ENDING, MettenVoicePhase.FAILED) || muted == value) return
            if (value) {
                muted = true; invalidateListeningLocked(); stop = input
                refreshMutedOwnerLocked(); _state.value = MettenVoiceState(MettenVoicePhase.MUTED)
            } else {
                refreshMutedOwnerLocked(); val owner = mutedResumeOwner; muted = false
                when (owner) {
                    MutedResumeOwner.OUTPUT -> if (activeOutput != null) {
                        _state.value = MettenVoiceState(MettenVoicePhase.SPEAKING)
                        (input as? ContinuousSpeechInputEngine)?.let { resumeContinuous = Triple(generation, listenAttempt, it) }
                    } else prepared = prepareListenLocked(generation, ListenCause.Unmuted)
                    MutedResumeOwner.STARTUP -> _state.value = MettenVoiceState(MettenVoicePhase.STARTING)
                    MutedResumeOwner.THINKING -> _state.value = MettenVoiceState(MettenVoicePhase.THINKING)
                    MutedResumeOwner.LISTENING -> prepared = prepareListenLocked(generation, ListenCause.Unmuted)
                }
            }
        }
        stop?.let(::submitStop); prepared?.let(::submitPreparedListen)
        resumeContinuous?.let { (id, attempt, engine) ->
            engine.startListening { event -> onInput(id, attempt, event) }
        }
    }

    @Synchronized fun microphonePermissionMissing() {
        if (_state.value.phase in setOf(MettenVoicePhase.IDLE, MettenVoicePhase.FAILED))
            _state.value = MettenVoiceState(MettenVoicePhase.FAILED, "Microphone permission is required for Metten Voice.")
    }

    fun stop() {
        val ended = synchronized(this) { if (_state.value.phase == MettenVoicePhase.IDLE) return; _state.value = MettenVoiceState(MettenVoicePhase.ENDING); generation }
        terminateExact(ended, MettenVoiceState())
    }

    private fun prepareListenLocked(id: Long, cause: ListenCause): PreparedListen? {
        if (id != generation || muted || activeOutput != null || input == null) return null
        val valid = when (cause) {
            is ListenCause.Startup -> cause.token.generation == id && startupToken == null
            is ListenCause.OutputCompleted -> activeOutput == null && cause.token.generation == id
            ListenCause.Unmuted -> !hasThinkingOwnershipLocked() && startupToken == null
            is ListenCause.Retry -> cause.priorAttempt == listenAttempt && _state.value.phase == MettenVoicePhase.LISTENING
            is ListenCause.EchoConsumed -> cause.priorAttempt + 1 == listenAttempt
            is ListenCause.TurnCompletedWithoutSpeech -> activeTurn != cause.token && !hasThinkingOwnershipLocked()
            ListenCause.OwnerSettled -> !hasThinkingOwnershipLocked() && startupToken == null
        }
        if (!valid) return null
        retryJob?.cancel(); retryJob = null
        val attempt = ++listenAttempt
        _state.value = MettenVoiceState(MettenVoicePhase.LISTENING)
        return PreparedListen(id, attempt, requireNotNull(input))
    }

    private fun submitPreparedListen(prepared: PreparedListen) = synchronized(inputEngineGate) {
        val valid = synchronized(this) {
            prepared.generation == generation && prepared.attempt == listenAttempt && _state.value.phase == MettenVoicePhase.LISTENING &&
                !muted && activeOutput == null && input === prepared.engine
        }
        if (valid) prepared.engine.startListening { event -> onInput(prepared.generation, prepared.attempt, event) }
    }
    private fun submitStop(engine: SpeechInputEngine) = synchronized(inputEngineGate) { engine.stopListening() }

    private fun onInput(id: Long, attempt: Long, event: SpeechInputEvent) {
        if (event == SpeechInputEvent.OutputInterrupted) {
            interruptExactOutput(id, attempt)
            return
        }
        if (!synchronized(this) {
                id == generation && attempt == listenAttempt && !muted &&
                    (_state.value.phase == MettenVoicePhase.LISTENING ||
                        input is ContinuousSpeechInputEngine && activeOutput == null && !hasThinkingOwnershipLocked())
            }) return
        when (event) {
            SpeechInputEvent.Ready, SpeechInputEvent.SpeechStarted -> synchronized(this) { if (id == generation && attempt == listenAttempt) startFailures = 0 }
            is SpeechInputEvent.Final -> acceptFinal(id, attempt, event.text)
            is SpeechInputEvent.RecoverableError -> {
                val exhausted = synchronized(this) { if (event.kind == SpeechInputFailureKind.START_FAILURE) ++startFailures >= MAX_START_FAILURES else false }
                if (exhausted) fail(id, "The on-device recognizer repeatedly failed to start.") else scheduleRetry(id, attempt, event.retryDelayMillis)
            }
            is SpeechInputEvent.FatalError -> fail(id, event.reason)
            is SpeechInputEvent.Partial -> Unit
            SpeechInputEvent.OutputInterrupted -> Unit
        }
    }

    /** Audio interruption never reaches the coordinator; only a later final transcript may control phone work. */
    private fun interruptExactOutput(id: Long, attempt: Long) {
        val engine = synchronized(this) {
            if (id != generation || attempt != listenAttempt || muted || input !is ContinuousSpeechInputEngine) return
            val token = activeOutput ?: return
            if (token.generation != id) return
            activeOutput = null
            pendingEchoText = null
            _state.value = MettenVoiceState(MettenVoicePhase.LISTENING)
            output
        }
        engine?.stop()
    }

    private fun acceptFinal(id: Long, attempt: Long, text: String) {
        var echoListen: PreparedListen? = null
        var turn: TurnToken? = null
        var stop: SpeechInputEngine? = null
        synchronized(this) {
            if (id != generation || attempt != listenAttempt || muted || _state.value.phase != MettenVoicePhase.LISTENING) return
            startFailures = 0
            val normalized = normalizeForEchoGuard(text)
            val echoed = normalized.isNotEmpty() && normalized == pendingEchoText
            pendingEchoText = null
            if (input !is ContinuousSpeechInputEngine) invalidateListeningLocked()
            if (echoed) echoListen = prepareListenLocked(id, ListenCause.EchoConsumed(attempt))
            else {
                val token = TurnToken(id, ++nextTurnId)
                activeTurn = token; _state.value = MettenVoiceState(MettenVoicePhase.THINKING)
                turn = token; if (input !is ContinuousSpeechInputEngine) stop = input
            }
        }
        echoListen?.let(::submitPreparedListen)
        turn?.let { token -> stop?.let(::submitStop); launchTurn(token, text) }
    }

    private fun scheduleRetry(id: Long, attempt: Long, delayMillis: Long) {
        synchronized(this) {
            if (id != generation || attempt != listenAttempt || muted || _state.value.phase != MettenVoicePhase.LISTENING) return
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(delayMillis.coerceIn(300, 2_000))
                val prepared = synchronized(this@MettenVoiceSessionController) { prepareListenLocked(id, ListenCause.Retry(attempt)) }
                prepared?.let(::submitPreparedListen)
            }
        }
    }

    private fun launchTurn(token: TurnToken, text: String) {
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) { runExactTurn(token, text, job) }
        val installed = synchronized(this) {
            if (generation == token.generation && activeTurn == token) { turnJob = TurnJobHandle(token, job); true } else false
        }
        if (installed) job.start() else job.cancel()
    }

    private suspend fun runExactTurn(token: TurnToken, text: String, job: Job) {
        try {
            val turnContext = synchronized(this) { VoiceTurnContext(context.toList(), coordinator.status.value) }
            val decision = brain.decide(text, turnContext)
            val command = decision.phoneCommand
            when {
                command != null -> transferTurnToPhone(token, text, command)
                decision.spokenText != null -> prepareTurnSpeech(token, text, decision.spokenText)?.let(::submitSpeech)
                else -> settleTurnWithoutSpeech(token)
            }
        } catch (_: CancellationException) {
        } catch (_: VoiceTurnProcessingException) {
            prepareTurnSpeech(token, text, RECOVERABLE_TURN_FAILURE)?.let(::submitSpeech)
        } finally {
            synchronized(this) {
                if (turnJob?.token == token && turnJob?.job === job) turnJob = null
                // A result path transfers/settles ownership. Only an exceptional stale path may still own it.
                if (activeTurn == token && token.generation != generation) activeTurn = null
            }
        }
    }

    private fun transferTurnToPhone(token: TurnToken, text: String, command: VoiceControlCommand) {
        val request = synchronized(this) {
            if (token.generation != generation || activeTurn != token) return
            val requestId = UUID.randomUUID().toString()
            pendingPhoneTurns[requestId] = text
            activeTurn = null
            if (muted) refreshMutedOwnerLocked()
            VoiceControlRequest(token.generation, requestId, command)
        }
        coordinator.accept(request)
    }

    private fun prepareTurnSpeech(token: TurnToken, userText: String, spoken: String): PreparedSpeech? = synchronized(this) {
        if (token.generation != generation || activeTurn != token) return null
        remember(userText, spoken)
        val prepared = installSpeechLocked(token.generation, spoken)
        activeTurn = null
        if (muted) { refreshMutedOwnerLocked(); null } else prepared
    }

    private fun settleTurnWithoutSpeech(token: TurnToken) {
        val prepared = synchronized(this) {
            if (token.generation != generation || activeTurn != token) return
            activeTurn = null
            if (muted) { refreshMutedOwnerLocked(); null }
            else if (hasThinkingOwnershipLocked()) { _state.value = MettenVoiceState(MettenVoicePhase.THINKING); null }
            else prepareListenLocked(token.generation, ListenCause.TurnCompletedWithoutSpeech(token))
        }
        prepared?.let(::submitPreparedListen)
    }

    private fun onPhoneEvent(id: Long, event: VoiceControlEvent) {
        var preparedSpeech: PreparedSpeech? = null
        var preparedListen: PreparedListen? = null
        synchronized(this) {
            if (id != generation || event.generation != id) return
            val pendingUser = pendingPhoneTurns.remove(event.requestId)
            val user = when (event) {
                is VoiceControlEvent.Accepted -> pendingUser?.also { acceptedPhoneTurns[event.requestId] = it }
                is VoiceControlEvent.Completed -> pendingUser ?: acceptedPhoneTurns.remove(event.requestId)
                else -> pendingUser
            } ?: return
            val spoken = when (event) {
                is VoiceControlEvent.Accepted -> ACKNOWLEDGEMENT
                is VoiceControlEvent.Rejected -> if (event.reason == "PHONE_CONTROL_NOT_READY") "Phone control is not ready." else "Another phone task is already active."
                is VoiceControlEvent.Status -> event.status.summary
                is VoiceControlEvent.Completed -> when (event.state) {
                    VoiceControlTerminalState.SUCCEEDED -> event.summary
                    VoiceControlTerminalState.FAILED -> "The phone task did not complete: ${event.summary}"
                    VoiceControlTerminalState.CANCELLED -> if (event.cancellation == VoiceControlCancellationDisposition.USER_REQUEST) "Phone task cancelled." else null
                }
            }
            if (spoken != null) remember(user, spoken)
            if (muted) refreshMutedOwnerLocked()
            else if (spoken != null) preparedSpeech = installSpeechLocked(id, spoken)
            else if (hasThinkingOwnershipLocked()) _state.value = MettenVoiceState(MettenVoicePhase.THINKING)
            else preparedListen = prepareListenLocked(id, ListenCause.OwnerSettled)
        }
        preparedSpeech?.let(::submitSpeech); preparedListen?.let(::submitPreparedListen)
    }

    private fun installSpeechLocked(id: Long, text: String): PreparedSpeech? {
        if (id != generation || muted) return null
        val engine = output ?: return null
        if (input !is ContinuousSpeechInputEngine) invalidateListeningLocked()
        val token = OutputToken(id, ++outputId)
        activeOutput = token
        pendingEchoText = normalizeForEchoGuard(text)
        _state.value = MettenVoiceState(MettenVoicePhase.SPEAKING)
        return PreparedSpeech(token, engine, input, text)
    }

    private fun submitSpeech(speech: PreparedSpeech) {
        if (speech.input !is ContinuousSpeechInputEngine) speech.input?.let(::submitStop)
        if (!synchronized(this) {
                speech.token.generation == generation && activeOutput == speech.token && output === speech.engine
            }) return
        speech.engine.speak(speech.text) callback@{ event ->
            when (event) {
                SpeechOutputEvent.Completed -> completeSpeech(speech.token)
                is SpeechOutputEvent.Failed -> {
                    val exact = synchronized(this) { if (activeOutput == speech.token && generation == speech.token.generation) { activeOutput = null; true } else false }
                    if (exact) fail(speech.token.generation, event.reason)
                }
                SpeechOutputEvent.Started -> Unit
            }
        }
    }

    private fun completeSpeech(token: OutputToken) {
        val prepared = synchronized(this) {
            if (token.generation != generation || activeOutput != token) return
            activeOutput = null
            when {
                muted -> { refreshMutedOwnerLocked(); _state.value = MettenVoiceState(MettenVoicePhase.MUTED); null }
                startupToken?.generation == generation -> { _state.value = MettenVoiceState(MettenVoicePhase.STARTING); null }
                hasThinkingOwnershipLocked() -> { _state.value = MettenVoiceState(MettenVoicePhase.THINKING); null }
                else -> prepareListenLocked(token.generation, ListenCause.OutputCompleted(token))
            }
        }
        prepared?.let(::submitPreparedListen)
    }

    private fun fail(id: Long, reason: String) = terminateExact(id, MettenVoiceState(MettenVoicePhase.FAILED, reason))

    private fun terminateExact(id: Long, terminal: MettenVoiceState) {
        val resources = synchronized(this) {
            if (id != generation) return
            generation++
            val terminationEpoch = ++lifecycleEpoch
            startupToken = null; activeOutput = null; activeTurn = null; startFailures = 0
            invalidateListeningLocked()
            val job = turnJob.also { turnJob = null }
            val speechInput = input.also { input = null }
            val speechOutput = output.also { output = null }
            muted = false; context.clear(); pendingPhoneTurns.clear(); acceptedPhoneTurns.clear(); pendingEchoText = null
            _state.value = terminal
            TerminationResources(job, speechInput, speechOutput, TerminationToken(generation, terminationEpoch, terminal))
        }
        resources.job?.job?.cancel()
        resources.input?.let { synchronized(inputEngineGate) { it.stopListening(); it.release() } }
        resources.output?.stop(); resources.output?.release()
        coordinator.endSession(id)
        val release = foregroundLease.release(id)
        if (release is VoiceForegroundReleaseResult.Failed) synchronized(this) {
            if (generation == resources.token.invalidatedGeneration && lifecycleEpoch == resources.token.epoch && _state.value == resources.token.original) {
                val message = if (resources.token.original.phase == MettenVoicePhase.FAILED)
                    listOfNotNull(resources.token.original.message, release.reason).distinct().joinToString(" ").take(320) else release.reason
                _state.value = MettenVoiceState(MettenVoicePhase.FAILED, message)
            }
        }
    }

    private fun hasThinkingOwnershipLocked() = activeTurn?.generation == generation || pendingPhoneTurns.isNotEmpty()
    private fun refreshMutedOwnerLocked() {
        mutedResumeOwner = when {
            activeOutput != null -> MutedResumeOwner.OUTPUT
            startupToken?.generation == generation -> MutedResumeOwner.STARTUP
            hasThinkingOwnershipLocked() -> MutedResumeOwner.THINKING
            else -> MutedResumeOwner.LISTENING
        }
    }
    private fun invalidateListeningLocked() { listenAttempt++; retryJob?.cancel(); retryJob = null }
    private fun remember(user: String, assistant: String) { context += VoiceConversationTurn(user, assistant); while (context.size > MAX_TURNS) context.removeFirst() }
    private fun normalizeForEchoGuard(text: String) = text.trim().lowercase().replace(Regex("\\s+"), " ")
    private companion object {
        const val MAX_TURNS = 8
        const val MAX_START_FAILURES = 3
        const val ACKNOWLEDGEMENT = "I'm working on that."
        const val RECOVERABLE_TURN_FAILURE = "I couldn't process that request. Please try again."
    }
}
