package com.mobileclaw.llm

import com.mobileclaw.auth.chatgpt.ChatGptBackendCredentials
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ChatGptModelServiceTest {
    private lateinit var server: MockWebServer
    @Before fun start() { server = MockWebServer().also { it.start() } }
    @After fun stop() { server.shutdown() }

    private fun service(residency: String? = "eu") = ChatGptModelService(
        credentials = ChatGptBackendCredentialProvider { ChatGptBackendCredentials("fake-access", "acct-1", residency) },
        client = okhttp3.OkHttpClient(), root = server.url("/backend-api/codex").toString().trimEnd('/'), clientVersion = "1.2.3",
    )

    @Test fun `catalog request headers and structured metadata are correct`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"models":[
          {"slug":"hidden","display_name":"Hidden","visibility":"hidden","priority":0,"unknown":true},
          {"slug":"visible","display_name":"Visible","visibility":"list","priority":1,"supported_in_api":false,
           "supported_reasoning_levels":[{"effort":"high","description":"More effort"}],"input_modalities":["text","image"],"context_window":200000}
        ]}"""))
        val service = service()
        val models = service.fetchModels()
        val request = server.takeRequest()
        assertEquals("/backend-api/codex/models", request.requestUrl!!.encodedPath)
        assertEquals("1.2.3", request.requestUrl!!.queryParameter("client_version"))
        assertEquals("Bearer fake-access", request.getHeader("Authorization"))
        assertEquals("acct-1", request.getHeader("ChatGPT-Account-Id"))
        assertEquals("eu", request.getHeader(RESIDENCY_HEADER))
        assertEquals("metten_ai_android", request.getHeader("originator"))
        assertEquals(ChatGptReasoningLevel("high", "More effort"), models[1].supportedReasoningLevels.single())
        assertEquals(200000L, models[1].contextWindow)
        assertEquals(listOf("visible"), service.pickerModels(models).map { it.slug })
        assertFalse(service.pickerModels(models).single().supportedInApi!!)
    }

    @Test fun `no constraint residency is omitted`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"models":[]}"""))
        service("no_constraint").fetchModels()
        assertNull(server.takeRequest().getHeader(RESIDENCY_HEADER))
    }

    @Test fun `input modalities distinguish legacy missing text-only and image-capable models`() {
        val parsed = service().parseModels("""{"models":[
          {"slug":"legacy","visibility":"list"},
          {"slug":"text-only","visibility":"list","input_modalities":["text"]},
          {"slug":"vision","visibility":"list","input_modalities":["text","image"]}
        ]}""").associateBy { it.slug }
        assertEquals(listOf("text", "image"), parsed.getValue("legacy").inputModalities)
        assertEquals(listOf("text"), parsed.getValue("text-only").inputModalities)
        assertEquals(listOf("text", "image"), parsed.getValue("vision").inputModalities)
    }

    @Test fun `cancellation aborts stalled model discovery`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch { service().fetchModels() }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }
}
