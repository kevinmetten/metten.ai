package com.mobileclaw.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

internal fun <T> selectCompatibleOfflineVoice(
    requested: Locale,
    voices: Collection<T>,
    localeOf: (T) -> Locale,
    requiresNetwork: (T) -> Boolean,
): T? = voices.asSequence()
    .filterNot(requiresNetwork)
    .filter { localeOf(it).language == requested.language }
    .sortedByDescending { localeOf(it) == requested }
    .firstOrNull()

/** TextToSpeech adapter which selects only installed, non-network voices for the device locale. */
class AndroidOfflineTextToSpeechOutput(context: Context) : SpeechOutputEngine {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var capability = SpeechCapability(false, "Offline speech is still initializing.", initializing = true)
    private var tts: TextToSpeech? = null
    private var released = false
    private val initializationListeners = mutableListOf<(SpeechCapability) -> Unit>()
    private val tracker = SpeechOutputUtteranceTracker()
    private val lifecycle = AndroidSpeechOutputLifecycle(
        tracker = tracker,
        clock = AndroidSpeechOutputLifecycle.Clock { android.os.SystemClock.uptimeMillis() },
        scheduler = object : AndroidSpeechOutputLifecycle.Scheduler {
            override fun schedule(delayMillis: Long, action: () -> Unit): Any = Runnable(action).also {
                main.postDelayed(it, delayMillis)
            }
            override fun cancel(handle: Any) { main.removeCallbacks(handle as Runnable) }
        },
        playback = AndroidSpeechOutputLifecycle.PlaybackStateProvider { tts?.isSpeaking ?: false },
        deliver = { delivery -> delivery?.let { it.listener(it.event) } },
    )
    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = postCallback(utteranceId, lifecycle::onStart)
        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) =
            postCallback(utteranceId, lifecycle::onRangeStart)
        override fun onDone(utteranceId: String?) = postCallback(utteranceId, lifecycle::onDone)
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = postCallback(utteranceId) {
            lifecycle.onError(it, "Offline speech playback failed.")
        }
        override fun onError(utteranceId: String?, errorCode: Int) = postCallback(utteranceId) {
            lifecycle.onError(it, "Offline speech playback failed ($errorCode).")
        }
        override fun onStop(utteranceId: String?, interrupted: Boolean) = postCallback(utteranceId, lifecycle::onStop)
    }

    init { onMain { tts = TextToSpeech(appContext) { status -> configure(status) } } }

    private fun configure(status: Int) {
        if (released) return
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            capability = SpeechCapability(false, "Android Text-to-Speech could not initialize.")
            notifyInitialized()
            return
        }
        val locale = Locale.getDefault()
        val voice = selectCompatibleOfflineVoice(locale, engine.voices.orEmpty(), { it.locale }, { it.isNetworkConnectionRequired })
        capability = if (voice == null) SpeechCapability(false, "No offline Text-to-Speech voice is installed.")
        else if (engine.setVoice(voice) == TextToSpeech.ERROR) SpeechCapability(false, "The offline Text-to-Speech voice could not be selected.")
        else if (engine.setOnUtteranceProgressListener(progressListener) == TextToSpeech.ERROR) SpeechCapability(false, "Offline speech callbacks could not be configured.")
        else SpeechCapability(true)
        notifyInitialized()
    }

    override fun capability() = capability
    override fun initialize(listener: (SpeechCapability) -> Unit) = onMain {
        if (!capability.initializing) listener(capability)
        else initializationListeners += listener
    }
    override fun speak(text: String, listener: (SpeechOutputEvent) -> Unit) = onMain {
        val engine = tts
        if (released || !capability.available || engine == null) { listener(SpeechOutputEvent.Failed(capability.reason ?: "Offline speech is unavailable.")); return@onMain }
        val id = UUID.randomUUID().toString()
        lifecycle.invalidate()
        check(tracker.register(id, listener))
        val token = lifecycle.replace(id)
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) == TextToSpeech.ERROR) {
            lifecycle.rejected(token, "Offline speech playback could not start.")
        } else lifecycle.accepted(token)
    }
    override fun stop() = onMain { lifecycle.invalidate(); tts?.stop() }
    override fun release() = onMain {
        if (released) return@onMain
        released = true
        lifecycle.release()
        capability = SpeechCapability(false, "Offline speech was released.")
        initializationListeners.clear()
        val engine = tts
        tts = null
        engine?.setOnUtteranceProgressListener(null)
        engine?.stop()
        engine?.shutdown()
    }
    private fun notifyInitialized() = initializationListeners.toList().also { initializationListeners.clear() }.forEach { it(capability) }
    private fun onMain(action: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action) }
    private fun postCallback(utteranceId: String?, action: (String) -> Unit) {
        val exactId = utteranceId ?: return
        onMain { if (!released) action(exactId) }
    }
}
