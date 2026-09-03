package com.mobileclaw.realtime

import com.google.gson.JsonParser

sealed interface VoiceControlEnvelope {
    data class Start(val goal: String) : VoiceControlEnvelope
    data object Status : VoiceControlEnvelope
    data object Cancel : VoiceControlEnvelope
    data class Replace(val goal: String) : VoiceControlEnvelope
}

object VoiceControlEnvelopeParser {
    fun parse(raw: String): VoiceControlEnvelope? = runCatching {
        val value = JsonParser.parseString(raw)
        if (!value.isJsonObject) return null
        val json = value.asJsonObject
        val op = json["op"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
        fun goal(): String? = json["goal"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString?.trim()?.takeIf(String::isNotBlank)
        when (op) {
            "start_phone_task" -> goal()?.let(VoiceControlEnvelope::Start)
            "get_phone_task_status" -> VoiceControlEnvelope.Status.takeIf { json.size() == 1 }
            "cancel_phone_task" -> VoiceControlEnvelope.Cancel.takeIf { json.size() == 1 }
            "replace_phone_task" -> goal()?.let(VoiceControlEnvelope::Replace)
            else -> null
        }
    }.getOrNull()
}
