package com.mobileclaw.realtime

import com.google.gson.JsonParser
import com.mobileclaw.auth.chatgpt.ChatGptBackendCredentials
import com.mobileclaw.llm.RESIDENCY_HEADER
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class ChatGptRealtimeCallClientTest {
    private val credential = ChatGptBackendCredentials("secret-access-token", "account-123", "eu")
    private val requestContext = RealtimeRequestContext(
        "11111111-1111-4111-8111-111111111111",
        "22222222-2222-4222-8222-222222222222",
        "33333333-3333-4333-8333-333333333333",
    )

    @Test fun `request maps backend contract and credentials only to headers`() {
        val client = client("https://example.test/backend-api/codex")
        val request = client.buildRequest("v=0\r\no=offer", credential, requestContext)
        assertEquals("/backend-api/codex/realtime/calls", request.url.encodedPath)
        assertEquals("quicksilver", request.url.queryParameter("intent"))
        assertEquals("avas", request.url.queryParameter("architecture"))
        assertEquals("Bearer secret-access-token", request.header("Authorization"))
        assertEquals("account-123", request.header("ChatGPT-Account-Id"))
        assertEquals("metten_ai_android", request.header("originator"))
        assertEquals("eu", request.header(RESIDENCY_HEADER))
        assertEquals("quicksilver=v2", request.header("openai-alpha"))
        assertEquals(requestContext.sessionId, request.header("session-id"))
        assertEquals(requestContext.threadId, request.header("thread-id"))
        assertEquals(requestContext.realtimeSessionId, request.header("x-session-id"))
        val json = JsonParser.parseString(request.bodyString()).asJsonObject
        assertEquals("v=0\r\no=offer", json["sdp"].asString)
        val session = json["session"].asJsonObject
        assertEquals(setOf("model", "instructions", "audio", "delegation"), session.keySet())
        assertEquals("gpt-live-1-codex", session["model"].asString)
        assertTrue(session["instructions"].asString.isNotBlank())
        val audio = session["audio"].asJsonObject
        assertEquals(setOf("output"), audio.keySet())
        assertEquals(setOf("voice"), audio["output"].asJsonObject.keySet())
        assertEquals("cove", audio["output"].asJsonObject["voice"].asString)
        assertEquals("client", session["delegation"].asJsonObject["type"].asString)
        assertFalse(session.has("type"))
        assertFalse(session.has("output_modalities"))
        assertFalse(audio.has("input"))
        assertFalse(audio["output"].asJsonObject.has("format"))
        assertFalse(session.has("tools"))
        assertFalse(session.has("tool_choice"))
        assertFalse(request.bodyString().contains("gpt-realtime-1.5"))
        assertFalse(request.bodyString().contains("secret-access-token"))
        assertFalse(request.bodyString().contains("account-123"))
    }

    @Test fun `residency no constraint and absent account headers are omitted`() {
        val request = client("https://example.test").buildRequest("v=0", ChatGptBackendCredentials("token", null, "no_constraint"), requestContext)
        assertNull(request.header("ChatGPT-Account-Id"))
        assertNull(request.header(RESIDENCY_HEADER))
    }

    @Test fun `call id parser searches segments and accepts only rtc or UUID identifiers`() {
        assertEquals("rtc_test", ChatGptRealtimeCallClient.parseCallId("https://chatgpt.com/realtime/calls/rtc_test"))
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        assertEquals(uuid, ChatGptRealtimeCallClient.parseCallId("/calls/$uuid?x=1"))
        assertEquals("rtc_nested", ChatGptRealtimeCallClient.parseCallId("/calls/rtc_nested/status"))
        assertNull(ChatGptRealtimeCallClient.parseCallId("/calls/not-a-call"))
        assertNull(ChatGptRealtimeCallClient.parseCallId("/calls/rtc_"))
        assertNull(ChatGptRealtimeCallClient.parseCallId("/calls/123e4567-e89b-12d3-a456-42661417400z"))
        assertNull(ChatGptRealtimeCallClient.parseCallId("/calls/"))
        assertNull(ChatGptRealtimeCallClient.parseCallId("not a location"))
        assertNull(ChatGptRealtimeCallClient.parseCallId(null))
    }

    @Test fun `status failures are safe and classified`() {
        assertEquals(RealtimeVoiceDiagnostic.NOT_SIGNED_IN, ChatGptRealtimeCallClient.classifyStatus(401).diagnostic)
        assertEquals(RealtimeVoiceDiagnostic.ACCESS_DENIED, ChatGptRealtimeCallClient.classifyStatus(403).diagnostic)
        assertEquals(RealtimeVoiceDiagnostic.ROUTE_UNAVAILABLE, ChatGptRealtimeCallClient.classifyStatus(404).diagnostic)
        assertEquals(RealtimeVoiceDiagnostic.USAGE_LIMIT, ChatGptRealtimeCallClient.classifyStatus(429).diagnostic)
        listOf(401, 403, 404, 429, 500).forEach { status ->
            assertFalse(ChatGptRealtimeCallClient.classifyStatus(status).message.orEmpty().contains("secret-access-token"))
        }
    }

    @Test fun `successful response parses answer and location`() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Location", "/realtime/calls/rtc_123").setBody("v=0\r\no=answer"))
        val answer = runBlocking { client(server.url("/backend-api/codex").toString()).createCall("v=0\r\no=offer", requestContext) }
        assertEquals("rtc_123", answer.callId)
        assertSame(requestContext, answer.sidebandAttachment.requestContext)
        assertTrue(answer.sdp.startsWith("v=0"))
    }

    @Test fun `missing location and malformed SDP fail as protocol errors`() = withServer { server ->
        server.enqueue(MockResponse().setBody("v=0\r\no=answer"))
        assertProtocolFailure(server)
        server.enqueue(MockResponse().addHeader("Location", "/calls/rtc_bad").setBody("not sdp"))
        assertProtocolFailure(server)
    }

    @Test fun `cancellation cancels in flight request`() = withServer { server ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        runBlocking {
            val job = async { client(server.url("/backend-api/codex").toString()).createCall("v=0", requestContext) }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            job.cancel()
            runCatching { job.await() }
            assertTrue(job.isCancelled)
        }
    }

    private fun assertProtocolFailure(server: MockWebServer) {
        val thrown = runCatching { runBlocking { client(server.url("/backend-api/codex").toString()).createCall("v=0", requestContext) } }.exceptionOrNull()
        assertEquals(RealtimeVoiceDiagnostic.PROTOCOL_REJECTED, (thrown as RealtimeVoiceException).diagnostic)
    }

    @Test fun `refresh failures remain distinct from signed out`() {
        val permanent = ChatGptRealtimeCallClient(
            RealtimeCredentialProvider { throw com.mobileclaw.auth.chatgpt.ChatGptRefreshException.Permanent() },
            OkHttpClient(), "https://example.test", CodexRealtimeSessionConfig(),
        )
        val transient = ChatGptRealtimeCallClient(
            RealtimeCredentialProvider { throw com.mobileclaw.auth.chatgpt.ChatGptRefreshException.Transient() },
            OkHttpClient(), "https://example.test", CodexRealtimeSessionConfig(),
        )
        assertEquals(RealtimeVoiceDiagnostic.NOT_SIGNED_IN, voiceFailure(permanent).diagnostic)
        assertEquals(RealtimeVoiceDiagnostic.AUTH_REFRESH_FAILED, voiceFailure(transient).diagnostic)
    }

    private fun voiceFailure(client: ChatGptRealtimeCallClient) =
        runCatching { runBlocking { client.createCall("v=0", requestContext) } }.exceptionOrNull() as RealtimeVoiceException

    @Test fun `request contexts are fresh UUIDs per transport generation`() {
        val first = RealtimeRequestContext.create()
        val second = RealtimeRequestContext.create()
        listOf(first.sessionId, first.threadId, first.realtimeSessionId, second.sessionId, second.threadId, second.realtimeSessionId)
            .forEach { assertNotNull(java.util.UUID.fromString(it)) }
        assertNotEquals(first, second)
    }

    @Test fun `400 diagnostic is bounded sanitized and credential safe`() = withServer { server ->
        val unsafe = "Bearer ${credential.accessToken} " + "x".repeat(500)
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"$unsafe"}}"""))
        val failure = runCatching {
            runBlocking { client(server.url("/backend-api/codex").toString()).createCall("v=0", requestContext) }
        }.exceptionOrNull() as RealtimeVoiceException
        assertEquals(RealtimeVoiceDiagnostic.PROTOCOL_REJECTED, failure.diagnostic)
        assertTrue(failure.message.orEmpty().startsWith("Realtime request rejected (HTTP 400):"))
        assertFalse(failure.message.orEmpty().contains(credential.accessToken))
        assertTrue(failure.message.orEmpty().length < 380)
    }

    private fun client(root: String) = ChatGptRealtimeCallClient(
        RealtimeCredentialProvider { credential }, OkHttpClient(), root, CodexRealtimeSessionConfig(),
    )
    private fun okhttp3.Request.bodyString(): String {
        val buffer = okio.Buffer(); body!!.writeTo(buffer); return buffer.readUtf8()
    }
    private inline fun withServer(block: (MockWebServer) -> Unit) {
        MockWebServer().use { server -> server.start(); block(server) }
    }
}
