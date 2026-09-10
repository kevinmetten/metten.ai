package com.mobileclaw.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import audio.soniqo.speech.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * One shared, full-duplex Soniqo session behind Metten's existing input/output seams.
 *
 * v0.0.21 confirms interruption only after speech-core's fixed 1.0 s threshold (0.4 s
 * recovery). Android SpeechConfig cannot tune it, so a short "Stop" remains a physical risk.
 * DeepFilterNet is enhancement, not AEC; this capture seam intentionally permits later AEC.
 */
internal class SoniqoConversationalSpeechSession(
    context: Context,
    private val player: PcmSpeechPlayer,
    private val capture: FloatPcmCapture = SoniqoAudioRecordCapture(),
) : ContinuousSpeechInputEngine, SpeechOutputEngine {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inference = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val outputGeneration = AtomicLong()
    private var pipeline: SpeechPipeline? = null
    private var inputListener: ((SpeechInputEvent) -> Unit)? = null
    private var output: Output? = null
    private var released = false
    private var muted = true
    // Capture API is available synchronously; output initialization owns asynchronous model readiness.
    private var capability = SpeechCapability(true, "Soniqo models are preparing.", true)

    private data class Output(
        val generation: Long,
        val identity: PlaybackIdentity,
        val listener: (SpeechOutputEvent) -> Unit,
        val samples: MutableList<FloatArray> = mutableListOf(),
    )

    override fun capability(): SpeechCapability = synchronized(lock) { capability }

    override fun initialize(listener: (SpeechCapability) -> Unit) {
        synchronized(lock) {
            if (released || pipeline != null || !capability.initializing) { post { listener(capability()) }; return }
        }
        inference.execute {
            val result = runCatching {
                val modelDir = File(app.filesDir, "speech/soniqo-v0.0.21").apply { mkdirs() }
                val config = SpeechConfig(
                    modelDir = modelDir.absolutePath,
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
                )
                // SpeechPipeline.start delegates to v0.0.21 ModelManager/ensureModels and
                // reuses validated files in modelDir; this worker prevents main-thread I/O.
                SpeechPipeline(config).also { it.start() }
            }
            val ready = result.fold(
                onSuccess = { value ->
                    synchronized(lock) { if (!released) pipeline = value }
                    observe(value)
                    SpeechCapability(true)
                },
                onFailure = { SpeechCapability(false, it.message ?: "Soniqo model preparation failed.") },
            )
            synchronized(lock) { if (!released) capability = ready }
            if (!released) {
                Log.i(TAG, if (ready.available) ENGINE_IDENTITY else "Speech engine: SONIQO (preparation failed)")
                post { listener(ready) }
            }
        }
    }

    override fun startListening(listener: (SpeechInputEvent) -> Unit) {
        val value = synchronized(lock) {
            if (released) return
            inputListener = listener
            muted = false
            pipeline
        } ?: return post { listener(SpeechInputEvent.FatalError("Soniqo is not prepared.")) }
        value.resumeListening()
        capture.start { samples -> synchronized(lock) { if (!muted && !released) pipeline }?.pushAudio(samples) }
            .onSuccess { postInput(SpeechInputEvent.Ready) }
            .onFailure { postInput(SpeechInputEvent.FatalError(it.message ?: "Microphone capture failed.")) }
    }

    override fun stopListening() {
        synchronized(lock) { muted = true; inputListener = null }
        capture.stop()
        pipeline?.cancelCurrentTurn()
    }

    override fun speak(text: String, listener: (SpeechOutputEvent) -> Unit) {
        val value: Pair<SpeechPipeline, Output> = synchronized(lock) {
            val pipe = pipeline
            if (released || pipe == null || !capability.available) return post { listener(SpeechOutputEvent.Failed("Soniqo is unavailable.")) }
            val generation = outputGeneration.incrementAndGet()
            val next = Output(generation, PlaybackIdentity(UUID.randomUUID().toString(), generation), listener)
            output = next
            pipe to next
        }
        inference.execute {
            runCatching { value.first.synthesizeStreaming(text) }
                .onFailure { failOutput(value.second, it.message ?: "Pocket TTS synthesis failed.") }
        }
    }

    override fun stop() {
        val old = synchronized(lock) { outputGeneration.incrementAndGet(); output.also { output = null } }
        pipeline?.cancelSynthesis()
        old?.let { player.cancel(it.identity) }
    }

    override fun release() {
        synchronized(lock) { if (released) return; released = true; inputListener = null; output = null }
        capture.release(); player.release(); pipeline?.cancelSynthesis(); pipeline?.stop(); pipeline?.close(); pipeline = null
        scope.cancel(); inference.shutdownNow()
    }

    private fun observe(value: SpeechPipeline) {
        scope.launch {
            value.events.collect { event ->
                when (event) {
                    is SpeechEvent.SpeechStarted -> postInput(SpeechInputEvent.SpeechStarted)
                    is SpeechEvent.PartialTranscription -> postInput(SpeechInputEvent.Partial(event.text))
                    is SpeechEvent.TranscriptionCompleted -> postInput(SpeechInputEvent.Final(event.text))
                    is SpeechEvent.ResponseInterrupted -> {
                        Log.i(TAG, "Soniqo confirmed barge-in; cancelling exact Voice output (no transcript logged).")
                        postInput(SpeechInputEvent.OutputInterrupted)
                    }
                    is SpeechEvent.ResponseAudioDelta -> appendAudio(event.audio)
                    is SpeechEvent.ResponseDone -> playCompletedSynthesis()
                    is SpeechEvent.Error -> postInput(SpeechInputEvent.FatalError(event.message))
                    else -> Unit
                }
            }
        }
    }

    private fun appendAudio(samples: FloatArray) = synchronized(lock) { output?.samples?.add(samples.copyOf()) }

    private fun playCompletedSynthesis() {
        val pair = synchronized(lock) {
            val current = output ?: return
            val samples = FloatArray(current.samples.sumOf { it.size })
            var offset = 0
            current.samples.forEach { it.copyInto(samples, offset).also { offset += it.size } }
            current to samples
        }
        if (pair.second.isEmpty()) return failOutput(pair.first, "Pocket TTS produced no audio.")
        player.play(pair.first.identity, SynthesizedSpeech(PcmFormat(pipeline?.ttsSampleRate ?: 24_000, 1), pair.second)) { event ->
            when (event) {
                PcmPlaybackEvent.Started -> deliver(pair.first, SpeechOutputEvent.Started, terminal = false)
                PcmPlaybackEvent.Drained -> deliver(pair.first, SpeechOutputEvent.Completed, terminal = true)
                is PcmPlaybackEvent.Failed -> deliver(pair.first, SpeechOutputEvent.Failed(event.reason), terminal = true)
            }
        }
    }

    private fun failOutput(value: Output, reason: String) = deliver(value, SpeechOutputEvent.Failed(reason), terminal = true)
    private fun deliver(value: Output, event: SpeechOutputEvent, terminal: Boolean) {
        val listener = synchronized(lock) {
            if (released || output !== value || outputGeneration.get() != value.generation) return
            if (terminal) output = null
            value.listener
        }
        post { listener(event) }
    }
    private fun postInput(event: SpeechInputEvent) { synchronized(lock) { if (muted || released) null else inputListener }?.let { callback -> post { callback(event) } } }
    private fun post(block: () -> Unit) { main.post(block) }

    private companion object {
        const val TAG = "MettenSoniqo"
        const val ENGINE_IDENTITY = "Speech engine: SONIQO; STT: PARAKEET_EOU; TTS: POCKET"
    }
}
