package com.mobileclaw.voice

import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Provider-neutral neural synthesis orchestration; it has no sherpa dependency. */
class NeuralOfflineSpeechOutput(
    private val synthesizer: LocalTtsSynthesizer,
    private val player: PcmSpeechPlayer,
    private val inference: ExecutorService = Executors.newSingleThreadExecutor(),
    private val callback: (action: () -> Unit) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
) : SpeechOutputEngine {
    private val tracker = SpeechOutputUtteranceTracker()
    private val generation = AtomicLong()
    private val monitor = Any()
    @Volatile private var capability = SpeechCapability(false, "Neural speech is still initializing.", true)
    private var released = false
    private var current: PlaybackIdentity? = null
    private val initializationListeners = mutableListOf<(SpeechCapability) -> Unit>()

    override fun capability() = capability

    override fun initialize(listener: (SpeechCapability) -> Unit) {
        synchronized(monitor) {
            if (!capability.initializing) { callback { listener(capability) }; return }
            initializationListeners += listener
            if (initializationListeners.size != 1) return
        }
        inference.execute {
            val result = synthesizer.initialize()
            val listeners: List<(SpeechCapability) -> Unit>
            synchronized(monitor) {
                if (released) return@execute
                capability = result.fold(
                    { SpeechCapability(true) },
                    { SpeechCapability(false, it.message ?: "Neural speech could not initialize.") },
                )
                listeners = initializationListeners.toList()
                initializationListeners.clear()
            }
            listeners.forEach { value -> callback { value(capability) } }
        }
    }

    override fun speak(text: String, listener: (SpeechOutputEvent) -> Unit) {
        val normalized = text.trim()
        if (normalized.isEmpty() || normalized.length > MAX_TEXT_LENGTH) {
            callback { listener(SpeechOutputEvent.Failed(if (normalized.isEmpty()) "Speech text is empty." else "Speech text is too long.")) }
            return
        }
        val prior: PlaybackIdentity?
        val identity: PlaybackIdentity
        synchronized(monitor) {
            if (released || !capability.available) {
                callback { listener(SpeechOutputEvent.Failed(capability.reason ?: "Neural speech is unavailable.")) }
                return
            }
            prior = current
            tracker.invalidateCurrent()
            identity = PlaybackIdentity(UUID.randomUUID().toString(), generation.incrementAndGet())
            check(tracker.register(identity.utteranceId, listener))
            current = identity
        }
        synthesizer.cancel()
        player.cancel(prior)
        inference.execute {
            if (!isCurrent(identity)) return@execute
            val speech = synthesizer.synthesize(normalized).getOrElse {
                terminal(identity, tracker.failed(identity.utteranceId, it.message ?: "Neural speech synthesis failed.")); return@execute
            }
            if (!valid(speech)) {
                terminal(identity, tracker.failed(identity.utteranceId, "Neural speech produced invalid audio.")); return@execute
            }
            if (!isCurrent(identity)) return@execute
            player.play(identity, speech) { event ->
                when (event) {
                    PcmPlaybackEvent.Started -> deliverIfCurrent(identity) { tracker.started(identity.utteranceId) }
                    PcmPlaybackEvent.Drained -> terminal(identity, tracker.completed(identity.utteranceId))
                    is PcmPlaybackEvent.Failed -> terminal(identity, tracker.failed(identity.utteranceId, event.reason))
                }
            }
        }
    }

    override fun stop() {
        val old = synchronized(monitor) {
            generation.incrementAndGet(); tracker.invalidateCurrent(); current.also { current = null }
        }
        synthesizer.cancel()
        player.cancel(old)
    }

    override fun release() {
        val old = synchronized(monitor) {
            if (released) return
            released = true
            generation.incrementAndGet()
            tracker.release()
            initializationListeners.clear()
            capability = SpeechCapability(false, "Neural speech was released.")
            current.also { current = null }
        }
        synthesizer.cancel()
        player.cancel(old)
        inference.execute { synthesizer.release() }
        inference.shutdown()
        player.release()
    }

    private fun valid(value: SynthesizedSpeech): Boolean = value.format.sampleRateHz > 0 &&
        value.format.channelCount == 1 && value.samples.isNotEmpty() &&
        value.samples.size <= value.format.sampleRateHz * MAX_SECONDS

    private fun isCurrent(identity: PlaybackIdentity) = synchronized(monitor) { !released && current == identity }
    private fun deliverIfCurrent(identity: PlaybackIdentity, event: () -> SpeechOutputUtteranceTracker.Delivery?) {
        val delivery = synchronized(monitor) { if (released || current != identity) null else event() }
        delivery?.let { callback { it.listener(it.event) } }
    }
    private fun terminal(identity: PlaybackIdentity, delivery: SpeechOutputUtteranceTracker.Delivery?) {
        val accepted = synchronized(monitor) {
            if (released || current != identity || delivery == null) null else delivery.also { current = null }
        }
        accepted?.let { callback { it.listener(it.event) } }
    }

    private companion object { const val MAX_TEXT_LENGTH = 2_000; const val MAX_SECONDS = 60 }
}
