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

    @Test fun `request maps backend contract and credentials only to headers`() {
        val client = client("https://example.test/backend-api/codex")
        val request = client.buildRequest("v=0\r\no=offer", credential)
        assertEquals("/backend-api/codex/realtime/calls", request.url.encodedPath)
        assertEquals("quicksilver", request.url.queryParameter("intent"))
        assertEquals("avas", request.url.queryParameter("architecture"))
        assertEquals("Bearer secret-access-token", request.header("Authorization"))
        assertEquals("account-123", request.header("ChatGPT-Account-Id"))
        assertEquals("metten_ai_android", request.header("originator"))
        assertEquals("eu", request.header(RESIDENCY_HEADER))
        val json = JsonParser.parseString(request.bodyString()).asJsonObject
        assertEquals("v=0\r\no=offer", json["sdp"].asString)
        assertEquals(ChatGptRealtimeCallClient.REALTIME_MODEL, json["session"].asJsonObject["model"].asString)
        assertFalse(request.bodyString().contains("secret-access-token"))
        assertFalse(request.bodyString().contains("account-123"))
    }

    @Test fun `residency no constraint and absent account headers are omitted`() {
        val request = client("https://example.test").buildRequest("v=0", ChatGptBackendCredentials("token", null, "no_constraint"))
        assertNull(request.header("ChatGPT-Account-Id"))
        assertNull(request.header(RESIDENCY_HEADER))
    }

    @Test fun `call id accepts rtc and uuid but not arbitrary or missing location`() {
        assertEquals("rtc_test", ChatGptRealtimeCallClient.parseCallId("https://chatgpt.com/realtime/calls/rtc_test"))
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        assertEquals(uuid, ChatGptRealtimeCallClient.parseCallId("/calls/$uuid?x=1"))
        assertNull(ChatGptRealtimeCallClient.parseCallId("/calls/not-a-call"))
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
        val answer = runBlocking { client(server.url("/backend-api/codex").toString()).createCall("v=0\r\no=offer") }
        assertEquals("rtc_123", answer.callId)
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
            val job = async { client(server.url("/backend-api/codex").toString()).createCall("v=0") }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            job.cancel()
            runCatching { job.await() }
            assertTrue(job.isCancelled)
        }
    }

    private fun assertProtocolFailure(server: MockWebServer) {
        val thrown = runCatching { runBlocking { client(server.url("/backend-api/codex").toString()).createCall("v=0") } }.exceptionOrNull()
        assertEquals(RealtimeVoiceDiagnostic.PROTOCOL_REJECTED, (thrown as RealtimeVoiceException).diagnostic)
    }

    private fun client(root: String) = ChatGptRealtimeCallClient(RealtimeCredentialProvider { credential }, OkHttpClient(), root)
    private fun okhttp3.Request.bodyString(): String {
        val buffer = okio.Buffer(); body!!.writeTo(buffer); return buffer.readUtf8()
    }
    private inline fun withServer(block: (MockWebServer) -> Unit) {
        MockWebServer().use { server -> server.start(); block(server) }
    }
}
