package com.mobileclaw.ui.settings

import com.mobileclaw.ui.AppPage
import com.mobileclaw.ui.shell.classicMeAiBasicsDestination
import com.mobileclaw.realtime.RealtimeVoicePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiBasicsReachabilityTest {
    @Test fun `Me AI Basics action opens the split AI Basics page`() {
        assertEquals(AppPage.AI_BASIC_SETTINGS, classicMeAiBasicsDestination())
    }

    @Test fun `AI Basics root renders provider then shared ChatGPT account before configuration`() {
        val sections = aiBasicsRootSections()
        assertEquals(AiBasicsRootSection.CLOUD_PROVIDER, sections[0])
        assertEquals(AiBasicsRootSection.CHATGPT_ACCOUNT, sections[1])
        assertEquals(2, sections.size)
        assertTrue(sections.contains(AiBasicsRootSection.CHATGPT_ACCOUNT))
    }

    @Test fun `shared ChatGPT card exposes sign in while signed out`() {
        val actions = chatGptAccountActions(signedIn = false, voicePhase = RealtimeVoicePhase.IDLE)
        assertTrue(ChatGptAccountAction.SIGN_IN in actions)
        assertTrue(ChatGptAccountAction.DEVICE_CODE in actions)
    }

    @Test fun `shared ChatGPT card exposes live voice controls while signed in`() {
        assertTrue(ChatGptAccountAction.START_VOICE in chatGptAccountActions(true, RealtimeVoicePhase.IDLE))
        val liveActions = chatGptAccountActions(true, RealtimeVoicePhase.CONNECTED)
        assertTrue(ChatGptAccountAction.END_VOICE in liveActions)
        assertTrue(ChatGptAccountAction.MUTE_VOICE in liveActions)
        assertTrue(ChatGptAccountAction.SIGN_OUT in liveActions)
    }
}
