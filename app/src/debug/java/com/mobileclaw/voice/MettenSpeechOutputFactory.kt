package com.mobileclaw.voice

import android.content.Context

object MettenSpeechOutputFactory {
    fun create(context: Context): SpeechOutputEngine = PreferredSpeechOutputEngine(
        primaryFactory = { NeuralOfflineSpeechOutput(
            synthesizer = SherpaKittenTtsSynthesizer(context.assets),
            player = AndroidAudioTrackPcmPlayer(),
        ) },
        fallbackFactory = { AndroidOfflineTextToSpeechOutput(context) },
    )
}
