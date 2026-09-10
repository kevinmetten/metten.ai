package com.mobileclaw.voice

import android.content.Context

/** Debug builds deliberately have no legacy fallback: the candidate cannot masquerade as Soniqo. */
object MettenSpeechEngineFactory {
    fun create(context: Context): MettenSpeechEnginePair {
        val session = SoniqoConversationalSpeechSession(
            context.applicationContext,
            AndroidAudioTrackPcmPlayer(),
        )
        return MettenSpeechEnginePair(session, session)
    }
}
