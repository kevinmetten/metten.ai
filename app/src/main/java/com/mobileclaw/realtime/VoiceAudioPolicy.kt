package com.mobileclaw.realtime

import android.media.AudioAttributes
import android.media.AudioManager

object VoiceAudioPolicy {
    internal enum class VolumeTarget { ASSISTANT_PLAYBACK, ACTIVITY_DEFAULT }
    internal data class PlaybackPlan(val audioMode: Int, val requestCommunicationDevice: Boolean)

    const val playbackUsage: Int = AudioAttributes.USAGE_ASSISTANT
    const val playbackContentType: Int = AudioAttributes.CONTENT_TYPE_SPEECH

    val playbackAttributes: AudioAttributes
        get() = AudioAttributes.Builder()
            .setUsage(playbackUsage)
            .setContentType(playbackContentType)
            .build()

    val volumeControlStream: Int
        get() = playbackAttributes.volumeControlStream

    fun usesAssistantVolume(phase: RealtimeVoicePhase): Boolean = phase in setOf(
        RealtimeVoicePhase.CONNECTING,
        RealtimeVoicePhase.CONNECTED,
        RealtimeVoicePhase.MUTED,
    )

    internal fun builtInAssistantPlaybackPlan() = PlaybackPlan(
        audioMode = AudioManager.MODE_NORMAL,
        requestCommunicationDevice = false,
    )

    internal fun volumeStreamFor(phase: RealtimeVoicePhase): Int =
        if (volumeTarget(phase) == VolumeTarget.ASSISTANT_PLAYBACK) volumeControlStream else AudioManager.USE_DEFAULT_STREAM_TYPE

    internal fun volumeTarget(phase: RealtimeVoicePhase): VolumeTarget =
        if (usesAssistantVolume(phase)) VolumeTarget.ASSISTANT_PLAYBACK else VolumeTarget.ACTIVITY_DEFAULT
}
