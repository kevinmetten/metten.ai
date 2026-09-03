package com.mobileclaw.realtime

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * The selected ChatGPT-backend wire adapter. This is deliberately explicit: fields from the
 * AVAS V1 session must never be mixed with the Frameless Bidi/V3 adapter.
 *
 * Mirrored from openai/codex `realtime_call.rs`, `realtime_websocket/methods.rs`, and
 * `realtime_websocket/protocol_v1.rs`. Current Codex explicitly rejects AVAS calls unless the
 * conversational realtime V1 adapter is selected (`quicksilver=v1`).
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
    val voice: String = "sage",
    val transcriptionModel: String = "gpt-4o-mini-transcribe",
)

/** Pure mapper for the current conversational realtime V1 AVAS call-create session shape. */
object CodexRealtimeProtocolMapper {
    fun callBody(offerSdp: String, config: CodexRealtimeSessionConfig): JsonObject = JsonObject().apply {
        addProperty("sdp", offerSdp)
        add("session", session(config))
    }

    internal fun session(config: CodexRealtimeSessionConfig): JsonObject = JsonObject().apply {
        addProperty("model", config.protocol.model)
        add("output_modalities", JsonArray().apply { add("audio") })
        add("audio", JsonObject().apply {
            add("input", JsonObject().apply {
                add("format", pcm24Khz())
                add("transcription", JsonObject().apply { addProperty("model", config.transcriptionModel) })
                add("turn_detection", JsonObject().apply {
                    addProperty("type", "server_vad")
                    addProperty("create_response", true)
                    addProperty("interrupt_response", true)
                    addProperty("silence_duration_ms", 500)
                })
                add("noise_reduction", JsonObject().apply { addProperty("type", "near_field") })
            })
            add("output", JsonObject().apply {
                add("format", pcm24Khz())
                addProperty("voice", config.voice)
            })
        })
    }

    private fun pcm24Khz() = JsonObject().apply {
        addProperty("type", "audio/pcm")
        addProperty("rate", 24_000)
    }
}
