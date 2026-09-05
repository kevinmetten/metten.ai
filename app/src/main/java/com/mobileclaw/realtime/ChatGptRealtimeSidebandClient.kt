package com.mobileclaw.realtime

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mobileclaw.auth.chatgpt.ChatGptAuthManager
import com.mobileclaw.auth.chatgpt.ChatGptBackendCredentials
import com.mobileclaw.llm.chatGptHeaders
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

interface RealtimeSideband : RealtimeDelegationSink {
    fun connect(callId: String, generation: Long, listener: (RealtimeDelegationRequest) -> Unit)
    fun close()
}

/** Serializes transport close against sideband attachment after call creation. */
internal class TransportSidebandAttachment(private val sideband: RealtimeSideband?) : RealtimeDelegationSink {
    private val lock = Any()
    private var closed = false
    fun attach(callId: String, generation: Long, listener: (RealtimeDelegationRequest) -> Unit): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        sideband?.connect(callId, generation, listener)
        true
    }
    fun close() {
        val firstClose = synchronized(lock) {
            if (closed) false else { closed = true; true }
        }
        if (firstClose) sideband?.close()
    }
    override fun send(update: RealtimeDelegationUpdate): Boolean = synchronized(lock) {
        if (closed) false else sideband?.send(update) == true
    }
}

internal fun interface SidebandSocketFactory {
    fun open(request: Request, listener: WebSocketListener): WebSocket
}

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

internal class SidebandReconnectPolicy(private val maximumAttempts: Int = 3) {
    private var generation = -1L
    private var attempts = 0
    private var active = false
    fun begin(value: Long) { generation = value; attempts = 0; active = true }
    fun close() { active = false }
    fun nextDelay(value: Long): Long? {
        if (!active || generation != value || attempts >= maximumAttempts) return null
        return longArrayOf(500, 1_500, 4_000)[attempts++].coerceAtMost(4_000)
    }
}

/** Control-only WebSocket attached to the exact WebRTC call; it never owns media. */
class ChatGptRealtimeSidebandClient internal constructor(
    private val scope: CoroutineScope,
    private val credentials: RealtimeCredentialProvider,
    private val http: OkHttpClient = OkHttpClient(),
    private val baseUrl: HttpUrl = HttpUrl.Builder().scheme("https").host("api.openai.com").addPathSegment("v1").build(),
    private val socketFactory: SidebandSocketFactory = SidebandSocketFactory(http::newWebSocket),
    private val beforeSocketOpen: suspend () -> Unit = {},
) : RealtimeSideband {
    constructor(scope: CoroutineScope, auth: ChatGptAuthManager) : this(scope, RealtimeCredentialProvider { auth.getValidBackendCredentials() })
    private val lock = Any()
    private var active = false
    private var lifecycleEpoch = 0L
    private var openAttempt = 0L
    private var generation = -1L
    private var callId: String? = null
    private var listener: ((RealtimeDelegationRequest) -> Unit)? = null
    private var socket: WebSocket? = null
    private var reconnect: Job? = null
    private val reconnectPolicy = SidebandReconnectPolicy()

    override fun connect(callId: String, generation: Long, listener: (RealtimeDelegationRequest) -> Unit) {
        require(callId.isNotBlank() && !callId.any(Char::isWhitespace))
        val (epoch, previous) = synchronized(lock) {
            lifecycleEpoch += 1
            val old = socket to reconnect
            socket = null
            reconnect = null
            this.callId = callId
            this.generation = generation
            this.listener = listener
            reconnectPolicy.begin(generation)
            active = true
            lifecycleEpoch to old
        }
        previous.second?.cancel()
        previous.first?.close(1000, "Voice sideband replaced")
        open(epoch, generation)
    }

    override fun send(update: RealtimeDelegationUpdate): Boolean {
        return synchronized(lock) {
            if (!active || update.voiceSessionGeneration != generation) return@synchronized false
            val current = socket ?: return@synchronized false
            FramelessDelegationProtocol.contextAppend(update).all(current::send)
        }
    }

    override fun close() {
        val (oldSocket, oldReconnect) = synchronized(lock) {
            lifecycleEpoch += 1
            active = false
            reconnectPolicy.close()
            val result = socket to reconnect
            socket = null
            reconnect = null
            listener = null
            callId = null
            result
        }
        oldReconnect?.cancel()
        oldSocket?.close(1000, "Voice session ended")
    }

    internal fun request(callId: String, credential: ChatGptBackendCredentials): Request {
        val url = baseUrl.newBuilder().addPathSegment("live").addPathSegment(callId).build()
        return Request.Builder().url(url).chatGptHeaders(credential).build()
    }

    private fun open(epoch: Long, expectedGeneration: Long) {
        val (id, attempt) = synchronized(lock) {
            if (!isCurrentLocked(epoch, expectedGeneration)) return
            requireNotNull(callId) to ++openAttempt
        }
        scope.launch {
            val credential = runCatching { credentials.credentials() }.getOrNull() ?: return@launch retry(epoch, expectedGeneration, attempt)
            if (!synchronized(lock) { isCurrentAttemptLocked(epoch, expectedGeneration, attempt) }) return@launch
            beforeSocketOpen()
            if (!synchronized(lock) { isCurrentAttemptLocked(epoch, expectedGeneration, attempt) }) return@launch
            val callback = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val keep = synchronized(lock) {
                        if (!isCurrentAttemptLocked(epoch, expectedGeneration, attempt)) false
                        else { socket = webSocket; true }
                    }
                    if (!keep) webSocket.close(1000, "Stale Voice sideband")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val target = synchronized(lock) {
                        listener.takeIf { isCurrentAttemptLocked(epoch, expectedGeneration, attempt) && socket === webSocket }
                    } ?: return
                    FramelessDelegationProtocol.parse(text, expectedGeneration)?.let(target)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = socketEnded(webSocket, epoch, expectedGeneration, attempt)
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = socketEnded(webSocket, epoch, expectedGeneration, attempt)
            }
            val opened = socketFactory.open(request(id, credential), callback)
            val keep = synchronized(lock) {
                if (!isCurrentAttemptLocked(epoch, expectedGeneration, attempt)) false
                else { socket = opened; true }
            }
            if (!keep) opened.close(1000, "Stale Voice sideband")
        }
    }

    private fun socketEnded(webSocket: WebSocket, epoch: Long, expectedGeneration: Long, attempt: Long) {
        val retry = synchronized(lock) {
            if (!isCurrentAttemptLocked(epoch, expectedGeneration, attempt) || socket !== webSocket) false
            else { socket = null; true }
        }
        if (retry) retry(epoch, expectedGeneration, attempt)
    }

    private fun retry(epoch: Long, expectedGeneration: Long, attempt: Long) {
        val wait = synchronized(lock) {
            if (!isCurrentAttemptLocked(epoch, expectedGeneration, attempt) || reconnect?.isActive == true) return
            reconnectPolicy.nextDelay(expectedGeneration)
        } ?: return
        val job = scope.launch {
            delay(wait)
            if (synchronized(lock) { isCurrentAttemptLocked(epoch, expectedGeneration, attempt) }) open(epoch, expectedGeneration)
        }
        synchronized(lock) {
            if (isCurrentAttemptLocked(epoch, expectedGeneration, attempt)) reconnect = job else job.cancel()
        }
    }

    private fun isCurrentLocked(epoch: Long, expectedGeneration: Long) = active && lifecycleEpoch == epoch && generation == expectedGeneration
    private fun isCurrentAttemptLocked(epoch: Long, expectedGeneration: Long, attempt: Long) =
        isCurrentLocked(epoch, expectedGeneration) && openAttempt == attempt
}
