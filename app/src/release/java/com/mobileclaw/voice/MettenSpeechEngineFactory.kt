package com.mobileclaw.voice

import android.content.Context

/** The proven sequential implementation remains the release fallback until physical acceptance. */
object MettenSpeechEngineFactory {
    fun create(context: Context) = MettenSpeechEnginePair(
        AndroidOnDeviceSpeechInput(context),
        MettenSpeechOutputFactory.create(context),
    )
}
