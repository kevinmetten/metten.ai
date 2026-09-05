package com.mobileclaw.realtime

import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.StringReader

sealed interface VoiceControlEnvelope {
    data class Start(val goal: String) : VoiceControlEnvelope
    data object Status : VoiceControlEnvelope
    data object Cancel : VoiceControlEnvelope
    data class Replace(val goal: String) : VoiceControlEnvelope
}

object VoiceControlEnvelopeParser {
    fun parse(raw: String): VoiceControlEnvelope? = runCatching {
        if (hasDuplicateTopLevelKeys(raw)) return null
        val value = JsonParser.parseString(raw)
        if (!value.isJsonObject) return null
        val json = value.asJsonObject
        val op = json["op"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
        fun goal(): String? = json["goal"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString?.trim()?.takeIf(String::isNotBlank)
        when (op) {
            "start_phone_task" -> goal()?.let(VoiceControlEnvelope::Start).takeIf { json.size() == 2 }
            "get_phone_task_status" -> VoiceControlEnvelope.Status.takeIf { json.size() == 1 }
            "cancel_phone_task" -> VoiceControlEnvelope.Cancel.takeIf { json.size() == 1 }
            "replace_phone_task" -> goal()?.let(VoiceControlEnvelope::Replace).takeIf { json.size() == 2 }
            else -> null
        }
    }.getOrNull()

    /**
     * Normalizes content from a provider-authenticated client delegation. Frameless input_text
     * is a handoff transcript, not a JSON wire schema, so natural language is valid here. This
     * entry point must only be used after the adapter validates delegation.created/client.
     */
    fun parseDelegated(raw: String, hasActivePhoneTask: Boolean): VoiceControlEnvelope? {
        parse(raw)?.let { return it }
        val goal = raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_DELEGATED_GOAL_CHARS } ?: return null
        // A malformed attempted envelope must not become an Android goal.
        if (goal.startsWith('{') || goal.startsWith('[')) return null
        val normalized = goal.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        if (normalized.isEmpty()) return null
        if (CANCEL_PATTERNS.any(normalized::contains)) return VoiceControlEnvelope.Cancel
        if (STATUS_PATTERNS.any(normalized::contains)) return VoiceControlEnvelope.Status
        return if (hasActivePhoneTask) VoiceControlEnvelope.Replace(goal) else VoiceControlEnvelope.Start(goal)
    }

    private fun hasDuplicateTopLevelKeys(raw: String): Boolean {
        val names = mutableSetOf<String>()
        JsonReader(StringReader(raw)).use { reader ->
            reader.isLenient = false
            reader.beginObject()
            while (reader.hasNext()) {
                if (!names.add(reader.nextName())) return true
                reader.skipValue()
            }
            reader.endObject()
        }
        return false
    }

    private const val MAX_DELEGATED_GOAL_CHARS = 2_000
    private val CANCEL_PATTERNS = listOf(
        "cancel the phone task", "cancel phone task", "stop what you re doing on my phone",
        "stop what you are doing on my phone", "stop the phone task", "stop phone task",
    )
    private val STATUS_PATTERNS = listOf(
        "what are you doing on my phone", "phone task status", "status of the phone task",
        "what is happening on my phone", "what s happening on my phone",
    )
}
