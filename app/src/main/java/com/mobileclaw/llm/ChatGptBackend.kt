package com.mobileclaw.llm

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mobileclaw.BuildConfig
import com.mobileclaw.auth.chatgpt.ChatGptAuthManager
import com.mobileclaw.auth.chatgpt.ChatGptBackendCredentials
import com.mobileclaw.auth.chatgpt.ChatGptOAuth
import com.mobileclaw.config.AgentConfig
import com.mobileclaw.vpn.AppHttpProxy
import java.io.BufferedReader
import java.io.IOException
import java.io.Reader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val CHATGPT_BACKEND_ROOT = "https://chatgpt.com/backend-api/codex"
internal const val RESIDENCY_HEADER = "x-openai-internal-codex-residency"

internal fun interface ChatGptBackendCredentialProvider {
    suspend fun credentials(): ChatGptBackendCredentials
}

internal fun ChatGptAuthManager.backendCredentialProvider() =
    ChatGptBackendCredentialProvider { getValidBackendCredentials() }

internal fun Request.Builder.chatGptHeaders(credentials: ChatGptBackendCredentials): Request.Builder =
    header("Authorization", "Bearer ${credentials.accessToken}")
        .header("User-Agent", "MobileClaw/${BuildConfig.VERSION_NAME}")
        .header("originator", ChatGptOAuth.ORIGINATOR)
        .apply {
            credentials.accountId?.takeIf(String::isNotBlank)?.let { header("ChatGPT-Account-Id", it) }
            credentials.computeResidency?.takeIf { it.isNotBlank() && !it.equals("no_constraint", true) }
                ?.let { header(RESIDENCY_HEADER, it) }
        }

/** Keeps cancellation connected to the OkHttp call until response-body consumption completes. */
internal suspend fun <T> Call.awaitAndConsume(consume: (Response, isCancelled: () -> Boolean) -> T): T =
    suspendCancellableCoroutine { continuation ->
        val terminal = AtomicBoolean(false)
        val deliveredResponse = AtomicReference<Response?>(null)
        continuation.invokeOnCancellation {
            if (terminal.compareAndSet(false, true)) {
                deliveredResponse.getAndSet(null)?.close()
                cancel()
            } else {
                deliveredResponse.getAndSet(null)?.close()
                cancel()
            }
        }
        enqueue(object : Callback {
            override fun onFailure(call: Call, failure: IOException) {
                if (terminal.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resumeWithException(failure)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                deliveredResponse.set(response)
                if (terminal.get() || !continuation.isActive) {
                    deliveredResponse.getAndSet(null)?.close()
                    call.cancel()
                    return
                }
                try {
                    val value = response.use { consume(it) { terminal.get() || !continuation.isActive } }
                    deliveredResponse.compareAndSet(response, null)
                    if (terminal.compareAndSet(false, true) && continuation.isActive) continuation.resume(value)
                } catch (cancelled: CancellationException) {
                    deliveredResponse.compareAndSet(response, null)
                    response.close()
                    call.cancel()
                    // Cancellation owns the continuation; never translate it into a backend error.
                } catch (failure: Throwable) {
                    deliveredResponse.compareAndSet(response, null)
                    if (terminal.compareAndSet(false, true) && continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }

data class ChatGptReasoningLevel(val effort: String, val description: String? = null)

data class ChatGptModel(
    val slug: String,
    val displayName: String = slug,
    val description: String? = null,
    val defaultReasoningLevel: String? = null,
    val supportedReasoningLevels: List<ChatGptReasoningLevel> = emptyList(),
    val visibility: String? = null,
    val supportedInApi: Boolean? = null,
    val priority: Int = Int.MAX_VALUE,
    val inputModalities: List<String> = emptyList(),
    val contextWindow: Long? = null,
)

class ChatGptModelService internal constructor(
    private val credentials: ChatGptBackendCredentialProvider,
    private val client: OkHttpClient = defaultChatGptClient(),
    private val root: String = CHATGPT_BACKEND_ROOT,
    private val clientVersion: String = semanticVersion(BuildConfig.VERSION_NAME),
) {
    constructor(auth: ChatGptAuthManager) : this(auth.backendCredentialProvider())

    private val refreshMutex = Mutex()
    @Volatile private var cachedModels: List<ChatGptModel> = emptyList()

    fun cachedCatalog(): List<ChatGptModel> = cachedModels
    fun model(slug: String): ChatGptModel? = cachedModels.firstOrNull { it.slug == slug }
    fun pickerModels(models: List<ChatGptModel> = cachedModels) =
        models.filter { it.visibility.equals("list", true) }.sortedBy(ChatGptModel::priority)

    suspend fun fetchModels(force: Boolean = true): List<ChatGptModel> = refreshMutex.withLock {
        if (!force && cachedModels.isNotEmpty()) return@withLock cachedModels
        val backendCredentials = credentials.credentials()
        val url = root.toHttpUrl().newBuilder().addPathSegment("models")
            .addQueryParameter("client_version", clientVersion).build()
        val request = Request.Builder().url(url).get().chatGptHeaders(backendCredentials).build()
        val fetched = client.newCall(request).awaitAndConsume { response, _ ->
            if (!response.isSuccessful) throw backendFailure(response.code)
            parseModels(response.body?.string().orEmpty())
        }
        cachedModels = fetched
        fetched
    }

    internal fun parseModels(json: String): List<ChatGptModel> {
        val rootJson = JsonParser.parseString(json).asJsonObject
        return rootJson["models"]?.asJsonArray.orEmpty().mapNotNull { element ->
            runCatching {
                val o = element.asJsonObject
                ChatGptModel(
                    slug = o.string("slug") ?: return@runCatching null,
                    displayName = o.string("display_name") ?: o.string("slug").orEmpty(),
                    description = o.string("description"),
                    defaultReasoningLevel = o.string("default_reasoning_level"),
                    supportedReasoningLevels = o.reasoningLevels("supported_reasoning_levels"),
                    visibility = o.string("visibility"),
                    supportedInApi = o["supported_in_api"]?.takeUnless { it.isJsonNull }?.asBoolean,
                    priority = o["priority"]?.takeUnless { it.isJsonNull }?.asInt ?: Int.MAX_VALUE,
                    inputModalities = o.strings("input_modalities"),
                    contextWindow = o["context_window"]?.takeUnless { it.isJsonNull }?.asLong,
                )
            }.getOrNull()
        }.sortedBy(ChatGptModel::priority)
    }
}

internal class ChatGptResponsesStreamParser(private val gson: Gson = Gson()) {
    fun parse(reader: Reader, request: ChatRequest, isCancelled: () -> Boolean = { false }): ChatResponse {
        val text = StringBuilder()
        val arguments = StringBuilder()
        var callId: String? = null
        var toolName: String? = null
        var completed = false

        val buffered = if (reader is BufferedReader) reader else reader.buffered()
        val dataLines = mutableListOf<String>()
        fun checkCancellation() { if (isCancelled()) throw CancellationException("ChatGPT request canceled.") }
        fun dispatch() {
            if (dataLines.isEmpty()) return
            checkCancellation()
            val data = dataLines.joinToString("\n")
            dataLines.clear()
            if (data == "[DONE]") return
            val event = runCatching { JsonParser.parseString(data).asJsonObject }
                .getOrElse { throw IOException("Malformed ChatGPT response stream.") }
            when (event.string("type")) {
                "response.output_text.delta" -> event.string("delta")?.let { delta ->
                    checkCancellation()
                    text.append(delta)
                    if (request.stream) request.onToken?.invoke(delta)
                }
                "response.function_call_arguments.delta" -> event.string("delta")?.let(arguments::append)
                "response.function_call_arguments.done" -> event.string("arguments")?.let { arguments.clear(); arguments.append(it) }
                "response.output_item.added", "response.output_item.done" -> event["item"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { item ->
                    if (item.string("type") == "function_call") {
                        callId = item.string("call_id") ?: item.string("id") ?: callId
                        toolName = item.string("name") ?: toolName
                        item.string("arguments")?.let { arguments.clear(); arguments.append(it) }
                    }
                }
                "response.completed" -> completed = true
                "response.failed" -> throw ChatGptResponseException("failed", event.safeResponseMessage() ?: "ChatGPT could not complete the response.")
                "response.incomplete" -> throw ChatGptResponseException("incomplete", event.safeResponseMessage() ?: "ChatGPT response was incomplete.")
                // Reasoning, summaries, encrypted content, and unknown events are intentionally private/ignored.
            }
        }

        while (true) {
            checkCancellation()
            val line = buffered.readLine() ?: break
            when {
                line.isEmpty() -> dispatch()
                line.startsWith("data:") -> dataLines += line.removePrefix("data:").removePrefix(" ")
                line.startsWith(":") || line.startsWith("event:") || line.startsWith("id:") || line.startsWith("retry:") -> Unit
            }
        }
        dispatch()
        checkCancellation()
        if (!completed) throw IOException("ChatGPT response stream ended before completion.")
        val tool = toolName?.let { name ->
            val params = runCatching {
                @Suppress("UNCHECKED_CAST")
                gson.fromJson(arguments.toString(), Map::class.java) as Map<String, Any>
            }.getOrElse { throw IOException("ChatGPT returned malformed tool arguments.") }
            ToolCall(callId ?: throw IOException("ChatGPT returned a tool call without an ID."), name, params)
        }
        return ChatResponse(text.toString().ifBlank { null }, tool, if (tool != null) "tool_calls" else "stop")
    }
}

internal class ChatGptResponseException(val category: String, message: String) : IOException(message)

class ChatGptOAuthGateway internal constructor(
    private val credentials: ChatGptBackendCredentialProvider,
    private val config: AgentConfig,
    private val models: ChatGptModelService,
    private val client: OkHttpClient = defaultChatGptClient(),
    private val root: String = CHATGPT_BACKEND_ROOT,
    private val parser: ChatGptResponsesStreamParser = ChatGptResponsesStreamParser(),
) : LlmGateway {
    constructor(auth: ChatGptAuthManager, config: AgentConfig, models: ChatGptModelService) :
        this(auth.backendCredentialProvider(), config, models)

    override suspend fun chat(request: ChatRequest): ChatResponse {
        val model = resolveModel(request)
        val backendCredentials = credentials.credentials()
        val json = buildResponsesRequest(request, model)
        val httpRequest = Request.Builder().url("$root/responses")
            .chatGptHeaders(backendCredentials)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(httpRequest).awaitAndConsume { response, isCancelled ->
            if (!response.isSuccessful) throw backendFailure(response.code)
            parser.parse(response.body?.charStream() ?: throw IOException("ChatGPT returned an empty response."), request, isCancelled)
        }
    }

    internal suspend fun resolveModel(request: ChatRequest): String {
        val snapshot = config.snapshot()
        ChatGptModelResolver.resolve(request.callOptions.model, snapshot.chatGptModel, models.pickerModels())?.let { return it }
        val selected = ChatGptModelResolver.resolve(null, null, models.pickerModels(models.fetchModels(force = false)))
            ?: throw IOException("Could not discover an available ChatGPT model. Please try again.")
        config.updateChatGptModel(selected)
        return selected
    }

    override suspend fun embed(text: String): FloatArray =
        throw UnsupportedOperationException("ChatGPT subscription does not provide embeddings.")

    internal fun buildResponsesRequest(request: ChatRequest, model: String): JsonObject =
        ChatGptResponsesRequestMapper.buildResponsesRequest(request, model)
}

internal object ChatGptModelResolver {
    fun resolve(requestModel: String?, storedModel: String?, pickerModels: List<ChatGptModel>): String? =
        requestModel?.takeIf(String::isNotBlank)
            ?: storedModel?.takeIf(String::isNotBlank)
            ?: pickerModels.firstOrNull()?.slug
}

internal object ChatGptResponsesRequestMapper {
    private val gson = Gson()
    internal fun buildResponsesRequest(request: ChatRequest, model: String): JsonObject {
        val input = JsonArray()
        request.messages.filterNot { it.role == "system" }.forEach { msg ->
            when {
                msg.toolCalls != null -> msg.toolCalls.forEach { call -> input.add(JsonObject().apply {
                    addProperty("type", "function_call"); addProperty("call_id", call.id)
                    addProperty("name", call.skillId); addProperty("arguments", gson.toJson(call.params))
                }) }
                msg.role == "tool" && msg.toolCallId != null -> input.add(JsonObject().apply {
                    addProperty("type", "function_call_output"); addProperty("call_id", msg.toolCallId)
                    addProperty("output", msg.content.orEmpty())
                })
                else -> input.add(JsonObject().apply {
                    addProperty("type", "message"); addProperty("role", msg.role)
                    val content = JsonArray()
                    if (!msg.content.isNullOrBlank()) content.add(JsonObject().apply {
                        addProperty("type", if (msg.role == "assistant") "output_text" else "input_text")
                        addProperty("text", msg.content)
                    })
                    msg.imageBase64?.takeIf { msg.role == "user" }?.let { image -> content.add(JsonObject().apply {
                        addProperty("type", "input_image"); addProperty("image_url", CloudImagePreparer.prepare(image))
                    }) }
                    add("content", content)
                })
            }
        }
        return JsonObject().apply {
            addProperty("model", model)
            request.messages.filter { it.role == "system" }.mapNotNull { it.content }.takeIf(List<String>::isNotEmpty)
                ?.let { addProperty("instructions", it.joinToString("\n\n")) }
            add("input", input)
            addProperty("parallel_tool_calls", false)
            addProperty("store", false)
            addProperty("stream", true)
            if (request.tools.isNotEmpty()) {
                add("tools", gson.toJsonTree(request.tools.map { tool -> mapOf(
                    "type" to "function", "name" to tool.name, "description" to tool.description, "strict" to false,
                    "parameters" to mapOf("type" to tool.parameters.type,
                        "properties" to tool.parameters.properties.mapValues { (_, p) ->
                            buildMap<String, Any> { put("type", p.type); put("description", p.description); if (p.type == "array") put("items", emptyMap<String, Any>()); if (p.type == "object") put("additionalProperties", true) }
                        }, "required" to tool.parameters.required, "additionalProperties" to false)
                ) }))
                addProperty("tool_choice", "auto")
            }
        }
    }
}

internal fun defaultChatGptClient() = OkHttpClient.Builder().proxySelector(AppHttpProxy.proxySelector())
    .connectTimeout(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()

internal fun semanticVersion(raw: String): String = Regex("\\d+\\.\\d+\\.\\d+").find(raw)?.value ?: "0.0.0"
private fun JsonObject.string(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString
private fun JsonObject.strings(key: String) = get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { runCatching { it.asString }.getOrNull() }.orEmpty()
private fun JsonObject.reasoningLevels(key: String): List<ChatGptReasoningLevel> =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { item ->
        when {
            item.isJsonObject -> item.asJsonObject.string("effort")?.let { ChatGptReasoningLevel(it, item.asJsonObject.string("description")) }
            item.isJsonPrimitive && item.asJsonPrimitive.isString -> ChatGptReasoningLevel(item.asString)
            else -> null
        }
    }.orEmpty()
private fun JsonObject.safeResponseMessage(): String? =
    get("response")?.takeIf { it.isJsonObject }?.asJsonObject?.get("error")?.takeIf { it.isJsonObject }
        ?.asJsonObject?.string("message")?.take(240)
private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray()
private fun backendFailure(code: Int): Exception = when (code) {
    401, 403 -> IOException("ChatGPT session expired. Please sign in again.")
    429 -> IOException("ChatGPT is temporarily rate limited. Please try again later.")
    in 400..499 -> IOException("ChatGPT rejected the request or model.")
    else -> IOException("ChatGPT service is temporarily unavailable.")
}
