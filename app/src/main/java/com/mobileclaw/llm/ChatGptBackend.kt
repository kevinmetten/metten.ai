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
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

internal const val CHATGPT_BACKEND_ROOT = "https://chatgpt.com/backend-api/codex"
internal const val RESIDENCY_HEADER = "x-openai-internal-codex-residency"

internal fun Request.Builder.chatGptHeaders(credentials: ChatGptBackendCredentials): Request.Builder =
    header("Authorization", "Bearer ${credentials.accessToken}")
        .header("User-Agent", "MobileClaw/${BuildConfig.VERSION_NAME}")
        .header("originator", ChatGptOAuth.ORIGINATOR)
        .apply {
            credentials.accountId?.takeIf(String::isNotBlank)?.let { header("ChatGPT-Account-Id", it) }
            credentials.computeResidency?.takeIf { it.isNotBlank() && !it.equals("no_constraint", true) }
                ?.let { header(RESIDENCY_HEADER, it) }
        }

internal suspend fun <T> Call.awaitAndUse(block: suspend (okhttp3.Response) -> T): T = withContext(Dispatchers.IO) {
    val call = this@awaitAndUse
    val handle = coroutineContext.job.invokeOnCompletion { if (it != null) call.cancel() }
    try {
        call.execute().use { block(it) }
    } finally {
        handle.dispose()
    }
}

data class ChatGptModel(
    val slug: String,
    val displayName: String = slug,
    val description: String? = null,
    val defaultReasoningLevel: String? = null,
    val supportedReasoningLevels: List<String> = emptyList(),
    val visibility: String? = null,
    val supportedInApi: Boolean? = null,
    val priority: Int = Int.MAX_VALUE,
    val inputModalities: List<String> = emptyList(),
    val contextWindow: Int? = null,
)

class ChatGptModelService(
    private val auth: ChatGptAuthManager,
    private val client: OkHttpClient = defaultChatGptClient(),
    private val root: String = CHATGPT_BACKEND_ROOT,
    private val clientVersion: String = semanticVersion(BuildConfig.VERSION_NAME),
) {
    suspend fun fetchModels(): List<ChatGptModel> {
        val credentials = auth.getValidBackendCredentials()
        val url = root.toHttpUrl().newBuilder().addPathSegment("models")
            .addQueryParameter("client_version", clientVersion).build()
        val request = Request.Builder().url(url).get().chatGptHeaders(credentials).build()
        return client.newCall(request).awaitAndUse { response ->
            if (!response.isSuccessful) throw backendFailure(response.code)
            val rootJson = JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
            rootJson["models"]?.asJsonArray.orEmpty().mapNotNull { element ->
                runCatching {
                    val o = element.asJsonObject
                    ChatGptModel(
                        slug = o.string("slug") ?: return@runCatching null,
                        displayName = o.string("display_name") ?: o.string("slug").orEmpty(),
                        description = o.string("description"),
                        defaultReasoningLevel = o.string("default_reasoning_level"),
                        supportedReasoningLevels = o.strings("supported_reasoning_levels"),
                        visibility = o.string("visibility"),
                        supportedInApi = o["supported_in_api"]?.takeUnless { it.isJsonNull }?.asBoolean,
                        priority = o["priority"]?.takeUnless { it.isJsonNull }?.asInt ?: Int.MAX_VALUE,
                        inputModalities = o.strings("input_modalities"),
                        contextWindow = o["context_window"]?.takeUnless { it.isJsonNull }?.asInt,
                    )
                }.getOrNull()
            }.sortedBy(ChatGptModel::priority)
        }
    }

    fun pickerModels(models: List<ChatGptModel>) = models.filter { it.visibility.equals("list", true) }.sortedBy(ChatGptModel::priority)
}

class ChatGptOAuthGateway(
    private val auth: ChatGptAuthManager,
    private val config: AgentConfig,
    private val client: OkHttpClient = defaultChatGptClient(),
    private val root: String = CHATGPT_BACKEND_ROOT,
) : LlmGateway {
    private val gson = Gson()

    override suspend fun chat(request: ChatRequest): ChatResponse {
        val credentials = auth.getValidBackendCredentials()
        val model = request.callOptions.model?.takeIf(String::isNotBlank)
            ?: config.snapshot().chatGptModel.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Choose a ChatGPT model in Settings.")
        val json = buildResponsesRequest(request, model)
        val httpRequest = Request.Builder().url("$root/responses")
            .chatGptHeaders(credentials)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(httpRequest).awaitAndUse { response ->
            if (!response.isSuccessful) throw backendFailure(response.code)
            parseResponsesStream(response.body?.charStream() ?: throw IOException("ChatGPT returned an empty response."), request)
        }
    }

    override suspend fun embed(text: String): FloatArray =
        throw UnsupportedOperationException("ChatGPT subscription does not provide embeddings.")

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
                        addProperty("type", "input_image"); addProperty("image_url", image)
                    }) }
                    add("content", content)
                })
            }
        }
        return JsonObject().apply {
            addProperty("model", model)
            request.messages.filter { it.role == "system" }.mapNotNull { it.content }.takeIf(List<String>::isNotEmpty)
                ?.let { addProperty("instructions", it.joinToString("\n\n")) }
            add("input", input); addProperty("parallel_tool_calls", false); addProperty("store", false); addProperty("stream", true)
            if (request.tools.isNotEmpty()) {
                add("tools", gson.toJsonTree(request.tools.map { tool -> mapOf(
                    "type" to "function", "name" to tool.name, "description" to tool.description,
                    "parameters" to mapOf("type" to tool.parameters.type,
                        "properties" to tool.parameters.properties.mapValues { (_, p) ->
                            buildMap<String, Any> { put("type", p.type); put("description", p.description); if (p.type == "array") put("items", emptyMap<String, Any>()); if (p.type == "object") put("additionalProperties", true) }
                        }, "required" to tool.parameters.required, "additionalProperties" to false)
                ) }))
                addProperty("tool_choice", "auto")
            }
        }
    }

    private suspend fun parseResponsesStream(reader: java.io.Reader, request: ChatRequest): ChatResponse {
        val text = StringBuilder(); val args = StringBuilder()
        var callId: String? = null; var name: String? = null; var completed = false
        reader.buffered().useLines { lines -> lines.forEach { line ->
            currentCoroutineContext().ensureActive()
            if (!line.startsWith("data:")) return@forEach
            val data = line.removePrefix("data:").trim(); if (data.isBlank() || data == "[DONE]") return@forEach
            val event = runCatching { JsonParser.parseString(data).asJsonObject }.getOrElse { throw IOException("Malformed ChatGPT response stream.") }
            when (event.string("type")) {
                "response.output_text.delta" -> event.string("delta")?.let { delta -> text.append(delta); if (request.stream) request.onToken?.invoke(delta) }
                "response.function_call_arguments.delta" -> event.string("delta")?.let(args::append)
                "response.function_call_arguments.done" -> event.string("arguments")?.let { args.clear(); args.append(it) }
                "response.output_item.added", "response.output_item.done" -> event["item"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { item ->
                    if (item.string("type") == "function_call") { callId = item.string("call_id") ?: item.string("id") ?: callId; name = item.string("name") ?: name; item.string("arguments")?.let { args.clear(); args.append(it) } }
                }
                "response.completed" -> completed = true
                "response.failed" -> throw IOException("ChatGPT could not complete the response.")
                "response.incomplete" -> throw IOException("ChatGPT response was incomplete.")
                // Reasoning and unknown events are deliberately ignored and never published.
            }
        } }
        if (!completed) throw IOException("ChatGPT response stream ended before completion.")
        val tool = name?.let { toolName ->
            val params = runCatching { @Suppress("UNCHECKED_CAST") (gson.fromJson(args.toString(), Map::class.java) as Map<String, Any>) }
                .getOrElse { throw IOException("ChatGPT returned malformed tool arguments.") }
            ToolCall(callId ?: throw IOException("ChatGPT returned a tool call without an ID."), toolName, params)
        }
        return ChatResponse(text.toString().ifBlank { null }, tool, if (tool != null) "tool_calls" else "stop")
    }
}

internal fun defaultChatGptClient() = OkHttpClient.Builder().proxySelector(AppHttpProxy.proxySelector())
    .connectTimeout(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()

internal fun semanticVersion(raw: String): String = Regex("\\d+\\.\\d+\\.\\d+").find(raw)?.value ?: "0.0.0"
private fun JsonObject.string(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString
private fun JsonObject.strings(key: String) = get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { runCatching { it.asString }.getOrNull() }.orEmpty()
private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray()
private fun backendFailure(code: Int): Exception = when (code) {
    401, 403 -> IOException("ChatGPT session expired. Please sign in again.")
    429 -> IOException("ChatGPT is temporarily rate limited. Please try again later.")
    in 400..499 -> IOException("ChatGPT rejected the request or model.")
    else -> IOException("ChatGPT service is temporarily unavailable.")
}
