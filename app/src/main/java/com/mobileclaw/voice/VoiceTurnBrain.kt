package com.mobileclaw.voice

import com.google.gson.JsonParser
import com.mobileclaw.agent.VoiceControlCommand
import com.mobileclaw.agent.VoicePhoneTaskStatus
import com.mobileclaw.llm.ChatRequest
import com.mobileclaw.llm.LlmGateway
import com.mobileclaw.llm.Message
import kotlinx.coroutines.CancellationException

data class VoiceConversationTurn(val userText: String, val assistantText: String)
data class VoiceTurnContext(val conversation: List<VoiceConversationTurn>, val phoneTask: VoicePhoneTaskStatus)
data class VoiceTurnDecision(val spokenText: String? = null, val phoneCommand: VoiceControlCommand? = null)

fun interface VoiceTurnBrain {
    suspend fun decide(userText: String, context: VoiceTurnContext): VoiceTurnDecision
}

sealed class VoiceTurnProcessingException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Transport(cause: Exception) : VoiceTurnProcessingException("The text request failed.", cause)
    class EmptyResponse : VoiceTurnProcessingException("The text model returned no Voice decision.")
    class InvalidDecision(message: String, cause: Throwable? = null) : VoiceTurnProcessingException(message, cause)
}

/** Structured text-only reasoning through the same selected LlmGateway used by the rest of MobileClaw. */
class LlmVoiceTurnBrain(private val llm: LlmGateway) : VoiceTurnBrain {
    override suspend fun decide(userText: String, context: VoiceTurnContext): VoiceTurnDecision {
        val history = context.conversation.flatMap { listOf(Message("user", it.userText), Message("assistant", it.assistantText)) }
        val phoneState = "Current Voice-owned phone task: state=${context.phoneTask.state.name}; high-level goal/summary=${context.phoneTask.summary}"
        val response = try {
            llm.chat(ChatRequest(messages = listOf(Message("system", "$SYSTEM\n$phoneState")) + history + Message("user", userText), stream = false))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw VoiceTurnProcessingException.Transport(failure)
        }
        val content = response.content?.trim()?.takeIf(String::isNotEmpty) ?: throw VoiceTurnProcessingException.EmptyResponse()
        return parse(content)
    }

    internal fun parse(raw: String): VoiceTurnDecision {
        val value = try { JsonParser.parseString(raw).asJsonObject }
            catch (failure: Exception) { throw VoiceTurnProcessingException.InvalidDecision("The text model returned malformed Voice JSON.", failure) }
        if ((value.keySet() - ALLOWED_KEYS).isNotEmpty()) throw VoiceTurnProcessingException.InvalidDecision("The Voice decision contained unsupported fields.")
        val action = value.get("action")?.takeIf { it.isJsonPrimitive }?.asString ?: throw VoiceTurnProcessingException.InvalidDecision("Voice action is missing.")
        val spoken = value.get("spoken_text")?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf(String::isNotEmpty)
        val goal = value.get("goal")?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf(String::isNotEmpty)
        val command = when (action) {
            "conversation" -> null
            "start" -> VoiceControlCommand.Start(goal ?: throw VoiceTurnProcessingException.InvalidDecision("Start requires a goal."))
            "replace" -> VoiceControlCommand.Replace(goal ?: throw VoiceTurnProcessingException.InvalidDecision("Replace requires a goal."))
            "cancel" -> VoiceControlCommand.Cancel
            "status" -> VoiceControlCommand.Status
            else -> throw VoiceTurnProcessingException.InvalidDecision("Unsupported Voice action.")
        }
        if (action == "conversation" && spoken == null) throw VoiceTurnProcessingException.InvalidDecision("Conversation requires spoken text.")
        return VoiceTurnDecision(spoken, command)
    }

    private companion object {
        val ALLOWED_KEYS = setOf("action", "spoken_text", "goal")
        const val SYSTEM = """You are Metten's conversational control plane. Return exactly one JSON object and no markdown: {"action":"conversation|start|replace|cancel|status","spoken_text":string|null,"goal":string|null}. An ordinary question while phone work is RUNNING is conversation and MUST NOT replace it. A status question is status. A stop request is cancel. An actionable correction to current STARTING or RUNNING phone work is replace. A new phone goal when no phone work is active is start. The goal is natural language for AgentRuntime; never emit coordinates or UI operations. Never claim an action succeeded. A phone acknowledgement is spoken by the application only after canonical acceptance."""
    }
}
