package com.mobileclaw.realtime

import android.media.AudioAttributes
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VoiceAudioPolicyTest {
    @Test fun `assistant playback does not use voice communication`() {
        assertEquals(AudioAttributes.USAGE_ASSISTANT, VoiceAudioPolicy.playbackUsage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, VoiceAudioPolicy.playbackContentType)
        assertFalse(VoiceAudioPolicy.playbackUsage == AudioAttributes.USAGE_VOICE_COMMUNICATION)
    }

    @Test fun `built in assistant playback uses normal mode without communication routing`() {
        val plan = VoiceAudioPolicy.builtInAssistantPlaybackPlan()
        assertEquals(AudioManager.MODE_NORMAL, plan.audioMode)
        assertFalse(plan.requestCommunicationDevice)
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
