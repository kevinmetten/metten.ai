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
        assertTrue(wire.contains("input_text")); assertTrue(wire.contains("input_image")); assertTrue(wire.contains("output_text"))
        assertTrue(wire.contains("function_call")); assertTrue(wire.contains("function_call_output")); assertTrue(wire.contains("call-1"))
        assertTrue(json["tools"].asJsonArray[0].asJsonObject.has("strict"))
        listOf("fake-access", "fake-refresh", "fake-id-token").forEach { assertFalse(wire.contains(it)) }
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
