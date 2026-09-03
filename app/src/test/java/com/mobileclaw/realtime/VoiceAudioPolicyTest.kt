package com.mobileclaw.realtime

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAudioPolicyTest {
    @Test fun `assistant playback does not use voice communication`() {
        assertEquals(AudioAttributes.USAGE_ASSISTANT, VoiceAudioPolicy.playbackUsage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, VoiceAudioPolicy.playbackContentType)
        assertFalse(VoiceAudioPolicy.playbackUsage == AudioAttributes.USAGE_VOICE_COMMUNICATION)
    }

    @Test fun `speaker replaces absent or earpiece route when available`() {
        val available = setOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        assertTrue(VoiceAudioPolicy.shouldSelectSpeaker(null, available))
        assertTrue(VoiceAudioPolicy.shouldSelectSpeaker(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, available))
    }

    @Test fun `external selected route is preserved`() {
        val available = setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        assertFalse(VoiceAudioPolicy.shouldSelectSpeaker(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, available))
    }

    @Test fun `cleanup clears only a route selected by the app`() {
        assertEquals(VoiceAudioPolicy.CleanupAction.CLEAR_EXPLICIT_ROUTE, VoiceAudioPolicy.cleanupAction(true))
        assertEquals(VoiceAudioPolicy.CleanupAction.NONE, VoiceAudioPolicy.cleanupAction(false))
    }

    @Test fun `active phases target assistant stream and terminal phases restore default`() {
        listOf(RealtimeVoicePhase.CONNECTING, RealtimeVoicePhase.CONNECTED, RealtimeVoicePhase.MUTED).forEach {
            assertEquals(VoiceAudioPolicy.VolumeTarget.ASSISTANT_PLAYBACK, VoiceAudioPolicy.volumeTarget(it))
        }
        listOf(RealtimeVoicePhase.IDLE, RealtimeVoicePhase.ENDING, RealtimeVoicePhase.FAILED).forEach {
            assertEquals(VoiceAudioPolicy.VolumeTarget.ACTIVITY_DEFAULT, VoiceAudioPolicy.volumeTarget(it))
        }
    }
}
