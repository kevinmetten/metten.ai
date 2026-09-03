package com.mobileclaw.realtime

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager

object VoiceAudioPolicy {
    internal enum class RouteAction { KEEP_CURRENT, SELECT_SPEAKER }
    internal enum class CleanupAction { NONE, CLEAR_EXPLICIT_ROUTE }
    internal enum class VolumeTarget { ASSISTANT_PLAYBACK, ACTIVITY_DEFAULT }

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

    internal fun shouldSelectSpeaker(selectedType: Int?, availableTypes: Set<Int>): Boolean =
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER in availableTypes &&
            (selectedType == null || selectedType == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE || selectedType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)

    internal fun routeAction(selectedType: Int?, availableTypes: Set<Int>): RouteAction =
        if (shouldSelectSpeaker(selectedType, availableTypes)) RouteAction.SELECT_SPEAKER else RouteAction.KEEP_CURRENT

    internal fun cleanupAction(explicitRouteSelected: Boolean): CleanupAction =
        if (explicitRouteSelected) CleanupAction.CLEAR_EXPLICIT_ROUTE else CleanupAction.NONE

    internal fun volumeStreamFor(phase: RealtimeVoicePhase): Int =
        if (volumeTarget(phase) == VolumeTarget.ASSISTANT_PLAYBACK) volumeControlStream else AudioManager.USE_DEFAULT_STREAM_TYPE

    internal fun volumeTarget(phase: RealtimeVoicePhase): VolumeTarget =
        if (usesAssistantVolume(phase)) VolumeTarget.ASSISTANT_PLAYBACK else VolumeTarget.ACTIVITY_DEFAULT
}
