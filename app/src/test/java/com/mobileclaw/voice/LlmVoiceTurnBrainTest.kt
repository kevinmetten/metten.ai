package com.mobileclaw.voice

import com.mobileclaw.agent.VoiceControlCommand
import com.mobileclaw.llm.*
import org.junit.Assert.*
import org.junit.Test

class LlmVoiceTurnBrainTest {
    private val brain = LlmVoiceTurnBrain(object : LlmGateway {
        override suspend fun chat(request: ChatRequest) = ChatResponse(null)
        override suspend fun embed(text: String) = floatArrayOf()
    })

    @Test fun `strict structured decisions route conversation and phone control`() {
        assertEquals("Ten.", brain.parse("""{"action":"conversation","spoken_text":"Ten.","goal":null}""").spokenText)
        assertEquals(VoiceControlCommand.Start("Open Settings"), brain.parse("""{"action":"start","spoken_text":null,"goal":"Open Settings"}""").phoneCommand)
        assertEquals(VoiceControlCommand.Replace("Use alarm toggle"), brain.parse("""{"action":"replace","spoken_text":"Okay.","goal":"Use alarm toggle"}""").phoneCommand)
    }

    @Test fun `malformed unknown or incomplete output never becomes a phone command`() {
        listOf("not json", "{}", """{"action":"start","spoken_text":null,"goal":null}""", """{"action":"start","goal":"Open","coordinates":[1,2]}""")
            .forEach { assertThrows(IllegalArgumentException::class.java) { brain.parse(it) } }
    }
}
