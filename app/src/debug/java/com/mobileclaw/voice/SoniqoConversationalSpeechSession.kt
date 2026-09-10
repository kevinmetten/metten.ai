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
    private val capture: FloatPcmCapture = SoniqoAudioRecordCapture(),
) : ContinuousSpeechInputEngine, SpeechOutputEngine {
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
        emitConfirmed = { postInput(SpeechInputEvent.OutputInterrupted) },
        debug = { message -> Log.d(TAG, message) },
    )

    private data class Output(
        val generation: Long,
        val identity: PlaybackIdentity,
        val text: String,
        val listener: (SpeechOutputEvent) -> Unit,
        var playback: PocketStreamingPlayback? = null,
    ) {
        val reference = SoniqoInterruptionGate.OutputReference(identity, text)
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
        val value: Pair<SpeechPipeline, Output> = synchronized(lock) {
            val pipe = pipeline
            if (initialization.isReleased() || pipe == null || !capability.available) return post { listener(SpeechOutputEvent.Failed("Soniqo is unavailable.")) }
            val generation = outputGeneration.incrementAndGet()
            val next = Output(generation, PlaybackIdentity(UUID.randomUUID().toString(), generation), text, listener)
            output = next
            next.playback = PocketStreamingPlayback(player, next.identity, pipe.ttsSampleRate) { event ->
                when (event) {
                    StreamingPlaybackEvent.Started -> deliver(next, SpeechOutputEvent.Started, terminal = false)
                    StreamingPlaybackEvent.Drained -> deliver(next, SpeechOutputEvent.Completed, terminal = true)
                    is StreamingPlaybackEvent.Failed -> deliver(next, SpeechOutputEvent.Failed(event.reason), terminal = true)
                }
            }
            pipe to next
        }
        inference.execute {
            runCatching {
                value.first.synthesizeStreaming(text, "en") { result, isFinal ->
                    if (isCurrent(value.second)) value.second.playback?.accept(result.pcm16, result.sampleRate, isFinal)
                }
            }
                .onFailure { failOutput(value.second, it.message ?: "Pocket TTS synthesis failed.") }
        }
    }

    override fun stop() {
        val old = synchronized(lock) { outputGeneration.incrementAndGet(); output.also { output = null } }
        pipeline?.cancelSynthesis()
        old?.let {
            Log.d(TAG, "output=${it.identity} cancel requested")
            interruption.outputEnded(it.reference, naturallyDrained = false)
            player.cancel(it.identity)
        }
    }

    override fun release() {
        val owned = synchronized(lock) {
            if (initialization.isReleased()) return
            inputListener = null; output = null; initializationListeners.clear()
            initialization.release()
        }
        interruption.close()
        capture.release(); player.release(); owned?.cancelSynthesis(); owned?.stop(); owned?.close()
        scope.cancel(); inference.shutdownNow()
    }

    private fun observe(value: SpeechPipeline) {
        scope.launch {
            value.events.collect { event ->
                when (event) {
                    is SpeechEvent.SpeechStarted -> {
                        val snapshot = synchronized(lock) { output?.reference to muted }
                        interruption.speechStarted(snapshot.first, snapshot.second)
                        postInput(SpeechInputEvent.SpeechStarted)
                    }
                    is SpeechEvent.SpeechEnded -> interruption.speechEnded()
                    is SpeechEvent.PartialTranscription -> {
                        interruption.partial(event.text)
                        postInput(SpeechInputEvent.Partial(event.text))
                    }
                    is SpeechEvent.TranscriptionCompleted -> if (interruption.allowFinal(event.text)) postInput(SpeechInputEvent.Final(event.text))
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
            if (terminal) output = null
            value.listener
        }
        if (terminal) interruption.outputEnded(value.reference, naturallyDrained = event == SpeechOutputEvent.Completed)
        post { listener(event) }
    }
    private fun postInput(event: SpeechInputEvent) { synchronized(lock) { if (muted || initialization.isReleased()) null else inputListener }?.let { callback -> post { callback(event) } } }
    private fun post(block: () -> Unit) { main.post(block) }

    private companion object {
        const val TAG = "MettenSoniqo"
        const val ENGINE_IDENTITY = "Speech engine: SONIQO; STT: PARAKEET_EOU; TTS: POCKET"
    }
}
