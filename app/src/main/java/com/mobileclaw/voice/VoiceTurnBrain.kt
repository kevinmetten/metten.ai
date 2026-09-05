package com.mobileclaw.voice

import com.google.gson.JsonParser
import com.mobileclaw.agent.VoiceControlCommand
import com.mobileclaw.llm.ChatRequest
import com.mobileclaw.llm.LlmGateway
import com.mobileclaw.llm.Message

data class VoiceConversationTurn(val userText: String, val assistantText: String)
data class VoiceTurnDecision(val spokenText: String? = null, val phoneCommand: VoiceControlCommand? = null)

fun interface VoiceTurnBrain {
    suspend fun decide(userText: String, context: List<VoiceConversationTurn>): VoiceTurnDecision
}

/** Structured text-only reasoning through the same selected LlmGateway used by the rest of MobileClaw. */
class LlmVoiceTurnBrain(private val llm: LlmGateway) : VoiceTurnBrain {
    override suspend fun decide(userText: String, context: List<VoiceConversationTurn>): VoiceTurnDecision {
        val history = context.flatMap { listOf(Message("user", it.userText), Message("assistant", it.assistantText)) }
        val response = llm.chat(ChatRequest(messages = listOf(Message("system", SYSTEM)) + history + Message("user", userText), stream = false))
        return parse(response.content ?: throw IllegalArgumentException("The text model returned no Voice decision."))
    }

    internal fun parse(raw: String): VoiceTurnDecision {
        val value = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            ?: throw IllegalArgumentException("The text model returned malformed Voice JSON.")
        if (value.keySet() - ALLOWED_KEYS != emptySet<String>()) throw IllegalArgumentException("The Voice decision contained unsupported fields.")
        val action = value.get("action")?.takeIf { it.isJsonPrimitive }?.asString ?: throw IllegalArgumentException("Voice action is missing.")
        val spoken = value.get("spoken_text")?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf(String::isNotEmpty)
        val goal = value.get("goal")?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf(String::isNotEmpty)
        val command = when (action) {
            "conversation" -> null
            "start" -> VoiceControlCommand.Start(goal ?: throw IllegalArgumentException("Start requires a goal."))
            "replace" -> VoiceControlCommand.Replace(goal ?: throw IllegalArgumentException("Replace requires a goal."))
            "cancel" -> VoiceControlCommand.Cancel
            "status" -> VoiceControlCommand.Status
            else -> throw IllegalArgumentException("Unsupported Voice action.")
        }
        if (action == "conversation" && spoken == null) throw IllegalArgumentException("Conversation requires spoken text.")
        return VoiceTurnDecision(spoken, command)
    }

    private companion object {
        val ALLOWED_KEYS = setOf("action", "spoken_text", "goal")
        const val SYSTEM = """You are Metten's conversational control plane. Return exactly one JSON object and no markdown: {"action":"conversation|start|replace|cancel|status","spoken_text":string|null,"goal":string|null}. Use conversation for ordinary questions even while phone work runs. Use start only for a new phone/UI goal, replace only for an actionable correction to current phone work, cancel/status when asked. The goal is natural language for AgentRuntime; never emit coordinates or UI operations. Never claim an action succeeded. A phone acknowledgement is spoken by the application only after canonical acceptance."""
    }
}
