package com.mobileclaw.llm

import java.io.IOException
import java.io.StringReader
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ChatGptResponsesTest {
    @Test fun `cancellation aborts stalled Responses stream without late tokens`() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val tokens = mutableListOf<String>()
            var returned = false
            val call = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
                .newCall(Request.Builder().url(server.url("/backend-api/codex/responses")).build())
            val job = launch {
                call.awaitAndConsume { response, cancelled ->
                    ChatGptResponsesStreamParser().parse(response.body!!.charStream(), ChatRequest(emptyList(), onToken = tokens::add), cancelled)
                }
                returned = true
            }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            job.cancelAndJoin()
            val afterCancel = tokens.size
            Thread.sleep(100)
            assertTrue(job.isCancelled)
            assertTrue(call.isCanceled())
            assertFalse(returned)
            assertEquals(afterCancel, tokens.size)
        } finally { server.shutdown() }
    }

    @Test fun `request maps instructions images tools and history without secrets`() {
        val request = ChatRequest(
            messages = listOf(
                Message("system", "Be concise"),
                Message("user", "look", "data:image/png;base64,AA=="),
                Message("assistant", "working"),
                Message("assistant", toolCalls = listOf(ToolCall("call-1", "tap", mapOf("x" to 2)))),
                Message("tool", "done", toolCallId = "call-1"),
            ),
            tools = listOf(ToolDefinition("tap", "Tap", ToolParameters(properties = mapOf("x" to ToolProperty("number", "X")), required = listOf("x")))),
        )
        val json = ChatGptResponsesRequestMapper.buildResponsesRequest(request, "model-1")
        val wire = json.toString()
        assertEquals("Be concise", json["instructions"].asString)
        assertEquals("model-1", json["model"].asString)
        assertFalse(json["parallel_tool_calls"].asBoolean)
        assertFalse(json["store"].asBoolean)
        assertTrue(json["stream"].asBoolean)
        assertEquals("reasoning.encrypted_content", json["include"].asJsonArray.single().asString)
        assertTrue(wire.contains("input_text")); assertTrue(wire.contains("input_image")); assertTrue(wire.contains("output_text"))
        assertTrue(wire.contains("function_call")); assertTrue(wire.contains("function_call_output")); assertTrue(wire.contains("call-1"))
        assertTrue(json["tools"].asJsonArray[0].asJsonObject.has("strict"))
        listOf("fake-access", "fake-refresh", "fake-id-token").forEach { assertFalse(wire.contains(it)) }
    }

    @Test fun `assistant text and tool call are both replayed`() {
        val json = ChatGptResponsesRequestMapper.buildResponsesRequest(ChatRequest(messages = listOf(
            Message("assistant", "I will inspect that now.", toolCalls = listOf(ToolCall("call_123", "test_tool", mapOf("value" to "abc")))),
            Message("tool", "result", toolCallId = "call_123"),
        )), "model")
        val wire = json["input"].asJsonArray
        assertTrue(wire.any { it.toString().contains("I will inspect that now.") })
        assertEquals(1, wire.count { it.asJsonObject["type"].asString == "function_call" })
        assertTrue(wire.any { it.asJsonObject["type"].asString == "function_call_output" && it.asJsonObject["call_id"].asString == "call_123" })
    }

    @Test fun `opaque reasoning is captured privately and replayed in tool order`() = runBlocking {
        val tokens = mutableListOf<String>(); val thinking = mutableListOf<String>()
        val parsed = ChatGptResponsesStreamParser().parseTurn(StringReader(events(
            """{"type":"response.output_item.done","item":{"type":"reasoning","id":"r1","encrypted_content":"PRIVATE_REASONING_SENTINEL","summary":[{"text":"PRIVATE_SUMMARY"}]}}""",
            """{"type":"response.output_item.done","item":{"type":"function_call","id":"fc1","call_id":"call_123","name":"test_tool","arguments":"{\"value\":\"abc\"}"}}""",
            """{"type":"response.completed"}""",
        )), ChatRequest(emptyList(), onToken = tokens::add, onThinkToken = thinking::add))
        assertNull(parsed.response.content)
        assertEquals("call_123", parsed.response.toolCall!!.id)
        assertFalse(parsed.response.toolCall!!.params.toString().contains("PRIVATE_REASONING_SENTINEL"))
        assertTrue(tokens.isEmpty()); assertTrue(thinking.isEmpty())

        val next = ChatGptResponsesRequestMapper.buildResponsesRequest(ChatRequest(messages = listOf(
            Message("assistant", "I will inspect that now.", toolCalls = listOf(parsed.response.toolCall!!)),
            Message("tool", "ok", toolCallId = "call_123"),
        )), "model", parsed.providerReplayItems)
        val input = next["input"].asJsonArray
        val types = input.map { it.asJsonObject["type"].asString }
        assertEquals(listOf("reasoning", "message", "function_call", "function_call_output"), types)
        assertEquals(1, types.count { it == "function_call" })
        assertEquals("fc1", input[2].asJsonObject["id"].asString)
        assertEquals("call_123", input[2].asJsonObject["call_id"].asString)
        assertTrue(next.toString().contains("PRIVATE_REASONING_SENTINEL"))
        assertFalse(parsed.response.toString().contains("PRIVATE_REASONING_SENTINEL"))
    }

    @Test fun `SSE framing concatenates text ignores reasoning and completes`() = runBlocking {
        val visible = mutableListOf<String>(); val thinking = mutableListOf<String>()
        val stream = """
            event: response.output_text.delta
            data: {"type":"response.output_text.delta",
            data: "delta":"Hello "}

            data: {"type":"response.reasoning_summary_text.delta","delta":"PRIVATE_SENTINEL"}

            data: {"type":"response.output_text.delta","delta":"world"}

            data: {"type":"unknown.future"}

            data: {"type":"response.completed"}

        """.trimIndent()
        val result = ChatGptResponsesStreamParser().parse(StringReader(stream), ChatRequest(emptyList(), stream = true, onToken = visible::add, onThinkToken = thinking::add))
        assertEquals("Hello world", result.content)
        assertEquals(listOf("Hello ", "world"), visible)
        assertTrue(thinking.isEmpty())
        assertFalse(result.content!!.contains("PRIVATE_SENTINEL"))
    }

    @Test fun `non-stream caller receives accumulated content without callbacks`() = runBlocking {
        val tokens = mutableListOf<String>()
        val result = ChatGptResponsesStreamParser().parse(StringReader(events(
            """{"type":"response.output_text.delta","delta":"done"}""",
            """{"type":"response.completed"}""",
        )), ChatRequest(emptyList(), stream = false, onToken = tokens::add))
        assertEquals("done", result.content); assertTrue(tokens.isEmpty())
    }

    @Test fun `function deltas and completed item preserve call id`() = runBlocking {
        val result = ChatGptResponsesStreamParser().parse(StringReader(events(
            """{"type":"response.function_call_arguments.delta","delta":"{\"x\":"}""",
            """{"type":"response.function_call_arguments.delta","delta":"2}"}""",
            """{"type":"response.output_item.done","item":{"type":"function_call","call_id":"call-7","name":"tap"}}""",
            """{"type":"response.completed"}""",
        )), ChatRequest(emptyList()))
        assertEquals("call-7", result.toolCall!!.id); assertEquals("tap", result.toolCall!!.skillId); assertEquals(2.0, result.toolCall!!.params["x"])
    }

    @Test fun `failed incomplete and malformed tool output fail safely`() = runBlocking {
        listOf("response.failed", "response.incomplete").forEach { type ->
            assertThrows(IOException::class.java) { runBlocking { ChatGptResponsesStreamParser().parse(StringReader(events("""{"type":"$type"}""")), ChatRequest(emptyList())) } }
        }
        val malformed = events(
            """{"type":"response.output_item.done","item":{"type":"function_call","call_id":"c","name":"tap","arguments":"{\""}}""",
            """{"type":"response.completed"}""",
        )
        assertThrows(IOException::class.java) { runBlocking { ChatGptResponsesStreamParser().parse(StringReader(malformed), ChatRequest(emptyList())) } }
    }

    private fun events(vararg json: String) = json.joinToString("") { "data: $it\n\n" }
}
