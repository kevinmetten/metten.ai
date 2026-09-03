package com.mobileclaw.realtime

import com.google.gson.JsonObject

/**
 * The selected ChatGPT-backend wire adapter is explicit because subscription calls use the
 * provider-specific Frameless Bidi/V3 shape rather than the generic AVAS V1 session.
 *
 * This is distinct from the generic AVAS V1 mapper even though both use the AVAS call route.
 */
enum class CodexRealtimeProtocol(
    val model: String,
    val intent: String?,
    val architecture: String?,
) {
    CHATGPT_FRAMELESS_BIDI_V3(
        model = "gpt-live-1-codex",
        intent = "quicksilver",
        architecture = "avas",
    ),
}

data class CodexRealtimeSessionConfig(
    val protocol: CodexRealtimeProtocol = CodexRealtimeProtocol.CHATGPT_FRAMELESS_BIDI_V3,
    val voice: String = "cove",
    val instructions: String = DEFAULT_VOICE_INSTRUCTIONS,
) {
    init {
        require(instructions.isNotBlank()) { "Realtime voice instructions must not be blank." }
    }
}

const val DEFAULT_VOICE_INSTRUCTIONS =
    "You are a helpful conversational voice assistant. Respond naturally and concisely."

/** Pure mapper for the current ChatGPT-authenticated Frameless Bidi/V3 call-create session. */
object CodexRealtimeProtocolMapper {
    fun callBody(offerSdp: String, config: CodexRealtimeSessionConfig): JsonObject = JsonObject().apply {
        addProperty("sdp", offerSdp)
        add("session", session(config))
    }

    internal fun session(config: CodexRealtimeSessionConfig): JsonObject = JsonObject().apply {
        addProperty("instructions", config.instructions)
        add("audio", JsonObject().apply {
            add("output", JsonObject().apply {
                addProperty("voice", config.voice)
            })
        })
        add("delegation", JsonObject().apply { addProperty("type", "client") })
        addProperty("model", config.protocol.model)
    }
}
