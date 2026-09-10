package com.mobileclaw.voice

import android.content.Context

object MettenSpeechOutputFactory {
    fun create(context: Context): SpeechOutputEngine = AndroidOfflineTextToSpeechOutput(context)
}
