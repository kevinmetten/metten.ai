package com.mobileclaw.realtime

import com.google.gson.JsonObject

/**
 * The selected ChatGPT-backend wire adapter. This is deliberately explicit: fields from the
 * AVAS V1 session must never be mixed with the Frameless Bidi/V3 adapter.
 *
 * Mirrored from openai/codex `realtime_call.rs`, `realtime_websocket/methods_v1.rs`, and
 * `realtime_websocket/protocol.rs`. AVAS rejects V2 but permits V1 and V3; Voice V1 deliberately
 * selects V1 because that is the current WebRTC default.
 */
enum class CodexRealtimeProtocol(
    val model: String,
    val intent: String?,
    val architecture: String?,
) {
    AVAS_REALTIME_V1(
        model = "gpt-realtime-1.5",
        intent = "quicksilver",
        architecture = "avas",
    ),
}

data class CodexRealtimeSessionConfig(
    val protocol: CodexRealtimeProtocol = CodexRealtimeProtocol.AVAS_REALTIME_V1,
    val voice: String = "cove",
    val instructions: String = DEFAULT_VOICE_INSTRUCTIONS,
) {
    init {
        require(instructions.isNotBlank()) { "Realtime voice instructions must not be blank." }
    }
}

const val DEFAULT_VOICE_INSTRUCTIONS =
    "You are a helpful conversational voice assistant. Respond naturally and concisely."

/** Pure mapper for the current conversational realtime V1 AVAS call-create session shape. */
object CodexRealtimeProtocolMapper {
    fun callBody(offerSdp: String, config: CodexRealtimeSessionConfig): JsonObject = JsonObject().apply {
        addProperty("sdp", offerSdp)
        add("session", session(config))
    }

    internal fun session(config: CodexRealtimeSessionConfig): JsonObject = JsonObject().apply {
        addProperty("type", "quicksilver")
        addProperty("model", config.protocol.model)
        addProperty("instructions", config.instructions)
        add("audio", JsonObject().apply {
            add("input", JsonObject().apply {
                add("format", pcm24Khz())
            })
            add("output", JsonObject().apply {
                addProperty("voice", config.voice)
            })
        })
    }

    private fun pcm24Khz() = JsonObject().apply {
        addProperty("type", "audio/pcm")
        addProperty("rate", 24_000)
    }
}
