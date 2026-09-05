package com.mobileclaw.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** Android's explicitly on-device recognizer. It never falls back to the generic recognizer. */
class AndroidOnDeviceSpeechInput(context: Context) : SpeechInputEngine {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var released = false

    override fun capability(): SpeechCapability = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> SpeechCapability(false, "On-device recognition requires Android 12 or later.")
        !SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) -> SpeechCapability(false, "This device has no on-device speech recognizer.")
        else -> SpeechCapability(true)
    }

    override fun startListening(listener: (SpeechInputEvent) -> Unit) = onMain {
        if (released) { listener(SpeechInputEvent.FatalError("Speech recognition has been released.")); return@onMain }
        val readiness = capability()
        if (!readiness.available) { listener(SpeechInputEvent.FatalError(readiness.reason.orEmpty())); return@onMain }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        try {
            val current = recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext).also { recognizer = it }
            current.setRecognitionListener(AndroidListener(listener))
            current.startListening(intent)
        } catch (_: SecurityException) {
            listener(SpeechInputEvent.FatalError("Microphone permission is missing."))
        } catch (_: UnsupportedOperationException) {
            listener(SpeechInputEvent.FatalError("On-device speech recognition is not supported by this device."))
        } catch (_: IllegalStateException) {
            listener(SpeechInputEvent.RecoverableError("The on-device recognizer could not start.", 1_200))
        } catch (_: android.content.ActivityNotFoundException) {
            listener(SpeechInputEvent.FatalError("The on-device recognition service is unavailable."))
        } catch (_: RuntimeException) {
            listener(SpeechInputEvent.RecoverableError("The on-device recognizer could not start.", 1_200))
        }
    }

    override fun stopListening() = onMain { recognizer?.cancel() }
    override fun release() = onMain { released = true; recognizer?.destroy(); recognizer = null }
    private fun onMain(action: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action) }

    private class AndroidListener(private val emit: (SpeechInputEvent) -> Unit) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = emit(SpeechInputEvent.Ready)
        override fun onBeginningOfSpeech() = emit(SpeechInputEvent.SpeechStarted)
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) emit(SpeechInputEvent.Final(text)) else emit(SpeechInputEvent.RecoverableError("No speech was recognized."))
        }
        override fun onPartialResults(results: Bundle?) {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf(String::isNotEmpty)
                ?.let { emit(SpeechInputEvent.Partial(it)) }
        }
        override fun onError(error: Int) {
            val benign = error in setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT)
            val reason = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The on-device recognizer is temporarily busy."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> "No speech was recognized."
                SpeechRecognizer.ERROR_CLIENT -> "Recognition was cancelled."
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "The device language is not supported by on-device recognition."
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "On-device recognition is unavailable for the device language."
                else -> "On-device recognition failed ($error)."
            }
            if (benign) emit(SpeechInputEvent.RecoverableError(reason, if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1_200 else 700))
            else emit(SpeechInputEvent.FatalError(reason))
        }
        override fun onEndOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
