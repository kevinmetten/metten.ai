package com.mobileclaw.realtime

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mobileclaw.auth.chatgpt.ChatGptAuthManager
import com.mobileclaw.auth.chatgpt.ChatGptBackendCredentials
import com.mobileclaw.llm.chatGptHeaders
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

object FramelessDelegationProtocol {
    fun parse(text: String, generation: Long): RealtimeDelegationRequest? = runCatching {
        val root = JsonParser.parseString(text).asJsonObject
        if (root["type"]?.asString != "delegation.created") return null
        val item = root.getAsJsonObject("item") ?: return null
        if (item["type"]?.asString != "delegation" || item["target"]?.asString != "client") return null
        val id = item["id"]?.asString?.takeIf(String::isNotBlank) ?: return null
        val input = item.getAsJsonArray("content")?.mapNotNull {
            it.takeIf { entry -> entry.isJsonObject }?.asJsonObject?.takeIf { entry -> entry["type"]?.asString == "input_text" }
                ?.get("text")?.takeIf { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString }?.asString
        }.orEmpty().joinToString("")
        RealtimeDelegationRequest(generation, id, input)
    }.getOrNull()

    fun contextAppend(update: RealtimeDelegationUpdate): List<String> = utf8Chunks(update.text).map { chunk ->
        JsonObject().apply {
            addProperty("type", "delegation.context.append")
            addProperty("delegation_item_id", update.delegationId)
            addProperty("channel", update.channel.name.lowercase())
            add("content", JsonArray().apply { add(JsonObject().apply { addProperty("type", "input_text"); addProperty("text", chunk) }) })
        }.toString()
    }

    internal fun utf8Chunks(text: String, maxBytes: Int = 500): List<String> {
        if (text.isEmpty()) return listOf("")
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var bytes = 0
        text.codePoints().forEach { codePoint ->
            val chars = String(Character.toChars(codePoint))
            val size = chars.toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes && current.isNotEmpty()) { result += current.toString(); current.clear(); bytes = 0 }
            current.append(chars); bytes += size
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }
}

/** Control-only WebSocket attached to the exact WebRTC call; it never owns media. */
class ChatGptRealtimeSidebandClient internal constructor(
    private val scope: CoroutineScope,
    private val credentials: RealtimeCredentialProvider,
    private val http: OkHttpClient = OkHttpClient(),
    private val baseUrl: HttpUrl = HttpUrl.Builder().scheme("https").host("api.openai.com").addPathSegment("v1").build(),
) : RealtimeDelegationSink {
    constructor(scope: CoroutineScope, auth: ChatGptAuthManager) : this(scope, RealtimeCredentialProvider { auth.getValidBackendCredentials() })
    private val active = AtomicBoolean(false)
    private var generation = -1L
    private var callId: String? = null
    private var listener: ((RealtimeDelegationRequest) -> Unit)? = null
    private var socket: WebSocket? = null
    private var reconnect: Job? = null
    private var attempts = 0

    fun connect(callId: String, generation: Long, listener: (RealtimeDelegationRequest) -> Unit) {
        require(callId.isNotBlank() && !callId.any(Char::isWhitespace))
        close()
        this.callId = callId
        this.generation = generation
        this.listener = listener
        attempts = 0
        active.set(true)
        open(generation)
    }

    override fun send(update: RealtimeDelegationUpdate): Boolean {
        if (!active.get() || update.voiceSessionGeneration != generation) return false
        val current = socket ?: return false
        return FramelessDelegationProtocol.contextAppend(update).all(current::send)
    }

    fun close() {
        active.set(false)
        reconnect?.cancel(); reconnect = null
        socket?.close(1000, "Voice session ended"); socket = null
        listener = null; callId = null
    }

    internal fun request(callId: String, credential: ChatGptBackendCredentials): Request {
        val url = baseUrl.newBuilder().addPathSegment("live").addPathSegment(callId).build()
        return Request.Builder().url(url).chatGptHeaders(credential).header("OpenAI-Beta", "realtime=v1").build()
    }

    private fun open(expected: Long) {
        val id = callId ?: return
        scope.launch {
            val credential = runCatching { credentials.credentials() }.getOrNull() ?: return@launch retry(expected)
            if (!active.get() || generation != expected) return@launch
            socket = http.newWebSocket(request(id, credential), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { if (generation == expected) attempts = 0 else webSocket.close(1000, null) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!active.get() || generation != expected) return
                    FramelessDelegationProtocol.parse(text, expected)?.let { listener?.invoke(it) }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (socket === webSocket) { socket = null; retry(expected) } }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (socket === webSocket) { socket = null; retry(expected) } }
            })
        }
    }

    private fun retry(expected: Long) {
        if (!active.get() || generation != expected || attempts >= 3 || reconnect?.isActive == true) return
        val wait = longArrayOf(500, 1_500, 4_000)[attempts++]
        reconnect = scope.launch { delay(wait); if (active.get() && generation == expected) open(expected) }
    }
}
