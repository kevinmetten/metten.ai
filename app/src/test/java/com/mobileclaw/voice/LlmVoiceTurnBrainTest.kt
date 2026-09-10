package com.mobileclaw.voice

import com.mobileclaw.agent.VoiceControlCommand
import com.mobileclaw.agent.VoicePhoneTaskState
import com.mobileclaw.agent.VoicePhoneTaskStatus
import com.mobileclaw.llm.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LlmVoiceTurnBrainTest {
    private fun parser() = LlmVoiceTurnBrain(object : LlmGateway {
        override suspend fun chat(request: ChatRequest) = ChatResponse(null)
        override suspend fun embed(text: String) = floatArrayOf()
    })

    @Test fun `strict structured decisions route conversation and phone control`() {
        assertEquals("Ten.", parser().parse("""{"action":"conversation","spoken_text":"Ten.","goal":null}""").spokenText)
        assertEquals(VoiceControlCommand.Start("Open Settings"), parser().parse("""{"action":"start","spoken_text":null,"goal":"Open Settings"}""").phoneCommand)
        assertEquals(VoiceControlCommand.Replace("Use alarm toggle"), parser().parse("""{"action":"replace","spoken_text":"Okay.","goal":"Use alarm toggle"}""").phoneCommand)
    }

    @Test fun `request explicitly includes running phone state and high-level goal`() = runTest {
        var captured: ChatRequest? = null
        val brain = LlmVoiceTurnBrain(object : LlmGateway {
            override suspend fun chat(request: ChatRequest): ChatResponse { captured = request; return ChatResponse("""{"action":"replace","spoken_text":null,"goal":"Use alarm row toggle"}""") }
            override suspend fun embed(text: String) = floatArrayOf()
        })
        val decision = brain.decide("No, use the toggle", VoiceTurnContext(emptyList(), VoicePhoneTaskStatus("task", VoicePhoneTaskState.RUNNING, "Find the 12 PM alarm")))
        val system = captured!!.messages.first().content.orEmpty()
        assertTrue("state=RUNNING" in system); assertTrue("Find the 12 PM alarm" in system)
        assertEquals(VoiceControlCommand.Replace("Use alarm row toggle"), decision.phoneCommand)

        val conversational = LlmVoiceTurnBrain(object : LlmGateway {
            override suspend fun chat(request: ChatRequest) = ChatResponse("""{"action":"conversation","spoken_text":"Ten.","goal":null}""")
            override suspend fun embed(text: String) = floatArrayOf()
        }).decide("What is five plus five?", VoiceTurnContext(emptyList(), VoicePhoneTaskStatus("task", VoicePhoneTaskState.RUNNING, "Find the 12 PM alarm")))
        assertEquals("Ten.", conversational.spokenText); assertNull(conversational.phoneCommand)
    }

    @Test fun `malformed unknown or incomplete output never becomes a phone command`() {
        listOf("not json", "{}", """{"action":"start","spoken_text":null,"goal":null}""", """{"action":"start","goal":"Open","coordinates":[1,2]}""")
            .forEach { assertThrows(VoiceTurnProcessingException.InvalidDecision::class.java) { parser().parse(it) } }
    }
}
