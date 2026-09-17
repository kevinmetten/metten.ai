package com.mobileclaw.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import audio.soniqo.speech.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * One shared, full-duplex Soniqo session behind Metten's existing input/output seams.
 *
 * Direct TRANSCRIBE_ONLY synthesis does not arm Soniqo's native ResponseInterrupted path.
 * Metten therefore requires divergent transcript evidence plus 500 ms of VAD speech with
 * working Android AEC, otherwise 1,000 ms. A short "Stop" remains exploratory. DeepFilterNet is not AEC.
 */
internal class SoniqoConversationalSpeechSession(
    context: Context,
    private val player: StreamingPcm16Player,
    private val capture: FloatPcmCapture = SoniqoAudioRecordCapture(context),
) : ContinuousSpeechInputEngine, StreamingSpeechOutputEngine {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inference = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val outputGeneration = AtomicLong()
    private val initialization = ExactInitializationOwnership<SpeechPipeline>()
    private val pipeline get() = initialization.current()
    private var inputListener: ((SpeechInputEvent) -> Unit)? = null
    private var output: Output? = null
    /** Bridges terminal output removal to the gate without nesting the session and gate locks. */
    private var tailHandoff: SoniqoInterruptionGate.OutputReference? = null
    private val initializationListeners = mutableListOf<(SpeechCapability) -> Unit>()
    private var muted = true
    // Capture API is available synchronously; output initialization owns asynchronous model readiness.
    private var capability = SpeechCapability(true, "Soniqo models are preparing.", true)
    private val interruption = SoniqoInterruptionGate(
        scheduler = InterruptionScheduler { delay, action ->
            val runnable = Runnable(action)
            main.postDelayed(runnable, delay)
            CancellableTimer { main.removeCallbacks(runnable) }
        },
        currentOutput = { synchronized(lock) { output?.identity } },
        emitConfirmed = { identity ->
            // Validate the backend identity again after the Android main-thread hop.
            post {
                val listener = synchronized(lock) {
                    if (!muted && output?.identity == identity && !initialization.isReleased()) inputListener else null
                }
                listener?.invoke(SpeechInputEvent.OutputInterrupted)
            }
        },
        debug = { message ->
            Log.d(TAG, message)
            val event = when {
                " partial " in message -> "STT_PARTIAL"
                " final " in message || message.startsWith("final ") -> "STT_FINAL"
                "duration=" in message && "reached" in message -> "INTERRUPT_THRESHOLD_REACHED"
                "OutputInterrupted emitted" in message -> "OUTPUT_INTERRUPTED_EMITTED"
                message.startsWith("VAD segment=") && "ended" in message -> "SONIQO_SPEECH_ENDED"
                else -> null
            }
            event?.let { VoiceDiagnostics.event(it, message) }
        },
    )

    private data class Output(
        val generation: Long,
        val identity: PlaybackIdentity,
        val text: String,
        val listener: (SpeechOutputEvent) -> Unit,
        var playback: PocketStreamingPlayback? = null,
    ) {
        val reference = SoniqoInterruptionGate.OutputReference(identity, text)
        val chunks = PendingSpeechText()
    }

    override fun capability(): SpeechCapability = synchronized(lock) { capability }

    override fun initialize(listener: (SpeechCapability) -> Unit) {
        val begin = synchronized(lock) {
            if (initialization.isReleased()) return
            if (pipeline != null || !capability.initializing) { post { listener(capability) }; return }
            initializationListeners += listener
            initialization.begin() == InitializationAdmission.START
        }
        if (!begin) return
        inference.execute {
            val result = runCatching {
                ProvisionedResourceFactory(
                    ensureModels = {
                        runBlocking {
                            ModelManager.ensureModels(
                                context = app,
                                precision = ModelPrecision.INT8,
                                sttModel = SttModel.PARAKEET_EOU,
                                ttsModel = TtsModel.POCKET,
                                enableSmartTurn = false,
                            )
                        }
                    },
                    construct = { modelDir ->
                        SpeechPipeline(SpeechConfig(
                            modelDir = modelDir,
                            useNnapi = false,
                            sttModel = SttModel.PARAKEET_EOU,
                            ttsModel = TtsModel.POCKET,
                            pipelineMode = PipelineMode.TRANSCRIBE_ONLY,
                            language = "auto",
                            precision = ModelPrecision.INT8,
                            emitPartialTranscriptions = true,
                            partialTranscriptionInterval = 0.5f,
                            beamSize = 4,
                            endOfSpeechSilenceSec = 0.8f,
                            enableSmartTurn = false,
                        )).also { it.start() }
                    },
                ).create()
            }
            result.fold(
                onSuccess = { value ->
                    val listeners = synchronized(lock) {
                        if (!initialization.accept(value)) null else {
                            capability = SpeechCapability(true)
                            initializationListeners.toList().also { initializationListeners.clear() }
                        }
                    }
                    if (listeners == null) { runCatching { value.stop() }; runCatching { value.close() } }
                    else {
                        observe(value)
                        Log.i(TAG, ENGINE_IDENTITY)
                        listeners.forEach { callback -> post { callback(SpeechCapability(true)) } }
                    }
                },
                onFailure = { failure ->
                    val ready = SpeechCapability(false, failure.message ?: "Soniqo model preparation failed.")
                    val listeners = synchronized(lock) {
                        initialization.failed()
                        if (initialization.isReleased()) emptyList() else {
                            capability = ready
                            initializationListeners.toList().also { initializationListeners.clear() }
                        }
                    }
                    if (listeners.isNotEmpty()) {
                        Log.i(TAG, "Speech engine: SONIQO (preparation failed)")
                        listeners.forEach { callback -> post { callback(ready) } }
                    }
                },
            )
        }
    }

    override fun startListening(listener: (SpeechInputEvent) -> Unit) {
        val value = synchronized(lock) {
            if (initialization.isReleased()) return
            inputListener = listener
            muted = false
            pipeline
        } ?: return post { listener(SpeechInputEvent.FatalError("Soniqo is not prepared.")) }
        value.resumeListening()
        capture.start { samples ->
            val activePipeline: SpeechPipeline? = synchronized(lock) {
                if (!muted && !initialization.isReleased()) pipeline else null
            }
            activePipeline?.pushAudio(samples)
        }
            .onSuccess { started ->
                interruption.configure(started.acousticEchoCancelerEnabled)
                VoiceDiagnostics.aecEnabled = started.acousticEchoCancelerEnabled
                VoiceDiagnostics.thresholdMillis = interruption.thresholdMillis()
                Log.i(TAG, if (started.acousticEchoCancelerEnabled) "Barge-in confirmation: 500ms, AEC enabled" else "Barge-in confirmation: 1000ms, AEC unavailable")
                postInput(SpeechInputEvent.Ready)
            }
            .onFailure { postInput(SpeechInputEvent.FatalError(it.message ?: "Microphone capture failed.")) }
    }

    override fun stopListening() {
        synchronized(lock) { muted = true; inputListener = null }
        interruption.muted()
        capture.stop()
        pipeline?.cancelCurrentTurn()
    }

    override fun speak(text: String, listener: (SpeechOutputEvent) -> Unit) {
        beginStream(listener).also { it.append(text); it.finish() }
    }

    @Synchronized override fun beginStream(listener: (SpeechOutputEvent) -> Unit): SpeechTextStream {
        stop()
        val value = synchronized(lock) {
            check(!initialization.isReleased() && pipeline != null && capability.available) { "Soniqo is unavailable." }
            val generation = outputGeneration.incrementAndGet()
            Output(generation, PlaybackIdentity(UUID.randomUUID().toString(), generation), "", listener).also { next ->
                output = next
                VoiceDiagnostics.event("OUTPUT_INSTALLED", "identity=${next.identity}")
                next.playback = PocketStreamingPlayback(player, next.identity, checkNotNull(pipeline).ttsSampleRate) { event ->
                    when (event) {
                        StreamingPlaybackEvent.Started -> deliver(next, SpeechOutputEvent.Started, terminal = false)
                        StreamingPlaybackEvent.Drained -> deliver(next, SpeechOutputEvent.Completed, terminal = true)
                        is StreamingPlaybackEvent.Failed -> deliver(next, SpeechOutputEvent.Failed(event.reason), terminal = true)
                    }
                }
            }
        }
        return object : SpeechTextStream {
            override fun append(text: String) {
                if (!isCurrent(value)) return
                if (value.chunks.append(text)) inference.execute { drainText(value) }
            }
            override fun finish() {
                if (isCurrent(value) && value.chunks.finish()) inference.execute { drainText(value) }
            }
            override fun cancel() { stopExact(value) }
        }
    }

    private fun drainText(value: Output) {
        if (!isCurrent(value)) return
        try {
            while (isCurrent(value)) {
                when (val next = value.chunks.next()) {
                    PendingSpeechText.Item.Wait -> return
                    PendingSpeechText.Item.End -> { value.playback?.finishLogical(); return }
                    is PendingSpeechText.Item.Text -> {
                        synchronized(lock) {
                            if (!isCurrent(value)) return
                            // The SAME reference object follows the VAD segment as more spoken text arrives.
                            value.reference.text += next.value
                        }
                        VoiceDiagnostics.event("POCKET_SYNTH_START", "identity=${value.identity} chars=${next.value.length}")
                        PocketSegmentedSynthesis(
                            playback = checkNotNull(value.playback),
                            isCurrent = { isCurrent(value) },
                            synthesize = { segment, callback ->
                                if (isCurrent(value)) pipeline?.synthesizeStreaming(segment, "en") { result, final ->
                                    callback(result.pcm16, result.sampleRate, final)
                                }
                            },
                            debug = {
                                if ("logical first PCM" in it) VoiceDiagnostics.event("POCKET_FIRST_PCM", "identity=${value.identity}")
                            },
                        ).run(next.value, finishLogical = false)
                    }
                }
            }
        } catch (failure: Throwable) {
            failOutput(value, "Pocket streaming synthesis failed.")
        }
    }

    @Synchronized private fun stopExact(value: Output) {
        val accepted = synchronized(lock) {
            if (output !== value) false else {
                outputGeneration.incrementAndGet(); tailHandoff = value.reference; output = null
                value.chunks.cancel()
                true
            }
        }
        if (!accepted) return
        VoiceDiagnostics.event("CANCEL_SYNTHESIS_CALLED", "identity=${value.identity}")
        pipeline?.cancelSynthesis()
        completeTailHandoff(value.reference, naturallyDrained = false)
        player.cancel(value.identity)
        VoiceDiagnostics.event("OUTPUT_CANCELLED", "identity=${value.identity}")
    }

    override fun stop() {
        synchronized(lock) { output }?.let(::stopExact)
    }

    @Synchronized override fun release() {
        val owned = synchronized(lock) {
            if (initialization.isReleased()) return
            inputListener = null; output?.chunks?.cancel(); output = null; tailHandoff = null; initializationListeners.clear()
            initialization.release()
        }
        interruption.close()
        capture.release(); player.release(); owned?.cancelSynthesis()
        // Native handles must outlive in-flight synthesis callbacks.
        inference.execute { owned?.stop(); owned?.close() }
        scope.cancel(); inference.shutdown()
    }

    private fun observe(value: SpeechPipeline) {
        scope.launch {
            value.events.collect { event ->
                when (event) {
                    is SpeechEvent.SpeechStarted -> {
                        val snapshot = synchronized(lock) { Triple(output?.reference, tailHandoff, muted) }
                        Log.d(TAG, "Soniqo SpeechStarted output=${snapshot.first?.identity} active=${snapshot.first != null}")
                        VoiceDiagnostics.event("SONIQO_SPEECH_STARTED", "output=${snapshot.first?.identity ?: "none"} aec=${if (VoiceDiagnostics.aecEnabled) "enabled" else "disabled"}")
                        interruption.speechStarted(snapshot.first, snapshot.third, snapshot.second)
                        postInput(SpeechInputEvent.SpeechStarted)
                    }
                    is SpeechEvent.SpeechEnded -> { Log.d(TAG, "Soniqo SpeechEnded"); interruption.speechEnded() }
                    is SpeechEvent.PartialTranscription -> {
                        Log.d(TAG, "Soniqo partial tokens=${event.text.trim().split(Regex("\\s+")).filter(String::isNotEmpty).size} output=${synchronized(lock) { output?.identity }}")
                        interruption.partial(event.text)
                        postInput(SpeechInputEvent.Partial(event.text))
                    }
                    is SpeechEvent.TranscriptionCompleted -> {
                        val active = synchronized(lock) { output?.reference }
                        Log.d(TAG, "Soniqo final tokens=${event.text.trim().split(Regex("\\s+")).filter(String::isNotEmpty).size} output=${active?.identity}")
                        if (interruption.allowFinal(event.text, active)) postInput(SpeechInputEvent.Final(event.text))
                    }
                    is SpeechEvent.Error -> postInput(SpeechInputEvent.FatalError(event.message))
                    else -> Unit
                }
            }
        }
    }

    private fun failOutput(value: Output, reason: String) = deliver(value, SpeechOutputEvent.Failed(reason), terminal = true)
    private fun isCurrent(value: Output) = synchronized(lock) { !initialization.isReleased() && output === value && outputGeneration.get() == value.generation }
    private fun deliver(value: Output, event: SpeechOutputEvent, terminal: Boolean) {
        val listener = synchronized(lock) {
            if (initialization.isReleased() || output !== value || outputGeneration.get() != value.generation) return
            if (terminal) { tailHandoff = value.reference; output = null }
            value.listener
        }
        if (terminal) {
            value.chunks.cancel()
            if (event is SpeechOutputEvent.Failed) player.cancel(value.identity)
            if (event == SpeechOutputEvent.Completed) VoiceDiagnostics.event("OUTPUT_NATURAL_DRAIN", "identity=${value.identity}")
            completeTailHandoff(value.reference, naturallyDrained = event == SpeechOutputEvent.Completed)
        }
        post { listener(event) }
    }
    private fun completeTailHandoff(reference: SoniqoInterruptionGate.OutputReference, naturallyDrained: Boolean) {
        // Never hold [lock] while entering the gate: the gate's exact-current check acquires [lock].
        interruption.outputEnded(reference, naturallyDrained)
        synchronized(lock) { if (tailHandoff == reference) tailHandoff = null }
    }
    private fun postInput(event: SpeechInputEvent) { synchronized(lock) { if (muted || initialization.isReleased()) null else inputListener }?.let { callback -> post { callback(event) } } }
    private fun post(block: () -> Unit) { main.post(block) }

    private companion object {
        const val TAG = "MettenSoniqo"
        const val ENGINE_IDENTITY = "Speech engine: SONIQO; STT: PARAKEET_EOU; TTS: POCKET"
    }
}
