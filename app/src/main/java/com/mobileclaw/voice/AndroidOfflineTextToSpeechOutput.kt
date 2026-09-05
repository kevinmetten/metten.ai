package com.mobileclaw.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/** TextToSpeech adapter which selects only installed, non-network voices for the device locale. */
class AndroidOfflineTextToSpeechOutput(context: Context) : SpeechOutputEngine {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var capability = SpeechCapability(false, "Offline speech is still initializing.")
    private var tts: TextToSpeech? = null
    private var released = false
    private val initializationListeners = mutableListOf<(SpeechCapability) -> Unit>()

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
        val voice = engine.voices.orEmpty().filterNot { it.isNetworkConnectionRequired }
            .sortedByDescending { it.locale == locale }.firstOrNull { it.locale.language == locale.language }
            ?: engine.voices.orEmpty().firstOrNull { !it.isNetworkConnectionRequired }
        capability = if (voice == null) SpeechCapability(false, "No offline Text-to-Speech voice is installed.")
        else if (engine.setVoice(voice) == TextToSpeech.ERROR) SpeechCapability(false, "The offline Text-to-Speech voice could not be selected.")
        else SpeechCapability(true)
        notifyInitialized()
    }

    override fun capability() = capability
    override fun initialize(listener: (SpeechCapability) -> Unit) = onMain {
        if (capability.available || capability.reason != "Offline speech is still initializing.") listener(capability)
        else initializationListeners += listener
    }
    override fun speak(text: String, listener: (SpeechOutputEvent) -> Unit) = onMain {
        val engine = tts
        if (released || !capability.available || engine == null) { listener(SpeechOutputEvent.Failed(capability.reason ?: "Offline speech is unavailable.")); return@onMain }
        val id = UUID.randomUUID().toString()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { if (utteranceId == id) listener(SpeechOutputEvent.Started) }
            override fun onDone(utteranceId: String?) { if (utteranceId == id) listener(SpeechOutputEvent.Completed) }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { if (utteranceId == id) listener(SpeechOutputEvent.Failed("Offline speech playback failed.")) }
            override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) listener(SpeechOutputEvent.Failed("Offline speech playback failed ($errorCode).")) }
        })
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) == TextToSpeech.ERROR) listener(SpeechOutputEvent.Failed("Offline speech playback could not start."))
    }
    override fun stop() = onMain { tts?.stop() }
    override fun release() = onMain { released = true; tts?.stop(); tts?.shutdown(); tts = null }
    private fun notifyInitialized() = initializationListeners.toList().also { initializationListeners.clear() }.forEach { it(capability) }
    private fun onMain(action: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action) }
}
