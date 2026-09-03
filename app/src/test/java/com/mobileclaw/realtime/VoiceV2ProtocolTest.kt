package com.mobileclaw.realtime

import com.mobileclaw.auth.chatgpt.ChatGptBackendCredentials
import kotlinx.coroutines.test.TestScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class VoiceV2ProtocolTest {
    @Test fun `valid client delegation maps input text exactly`() {
        val event = """{"type":"delegation.created","item":{"type":"delegation","target":"client","id":"d1","content":[{"type":"input_text","text":"{\"op\":"},{"type":"audio","text":"ignored"},{"type":"input_text","text":"\"get_phone_task_status\"}"}]}}"""
        assertEquals(RealtimeDelegationRequest(7, "d1", "{\"op\":\"get_phone_task_status\"}"), FramelessDelegationProtocol.parse(event, 7))
    }

    @Test fun `wrong target type and malformed events are ignored`() {
        assertNull(FramelessDelegationProtocol.parse("""{"type":"delegation.created","item":{"type":"delegation","target":"server","id":"d","content":[]}}""", 1))
        assertNull(FramelessDelegationProtocol.parse("""{"type":"delegation.created","item":{"type":"message","target":"client","id":"d","content":[]}}""", 1))
        assertNull(FramelessDelegationProtocol.parse("not json", 1))
    }

    @Test fun `context append preserves id channel and chunks unicode safely`() {
        val text = "🙂".repeat(300)
        val messages = FramelessDelegationProtocol.contextAppend(RealtimeDelegationUpdate(2, "same-id", text, RealtimeDelegationChannel.SPEAKABLE))
        assertTrue(messages.size > 1)
        val pieces = messages.map { com.google.gson.JsonParser.parseString(it).asJsonObject }.map { json ->
            assertEquals("delegation.context.append", json["type"].asString)
            assertEquals("same-id", json["delegation_item_id"].asString)
            assertEquals("speakable", json["channel"].asString)
            json.getAsJsonArray("content")[0].asJsonObject["text"].asString.also { assertTrue(it.toByteArray().size <= 500) }
        }
        assertEquals(text, pieces.joinToString(""))
    }

    @Test fun `existing call request uses exact live call path and OAuth headers`() {
        val client = ChatGptRealtimeSidebandClient(TestScope(), RealtimeCredentialProvider { error("unused") }, OkHttpClient(), "https://api.openai.com/v1".toHttpUrl())
        val request = client.request("rtc_existing", ChatGptBackendCredentials("secret", "account", null))
        assertEquals("https://api.openai.com/v1/live/rtc_existing", request.url.toString())
        assertEquals("Bearer secret", request.header("Authorization"))
        assertEquals("account", request.header("ChatGPT-Account-Id"))
        assertEquals("realtime=v1", request.header("OpenAI-Beta"))
    }
}
