package com.mobileclaw.auth.chatgpt

import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ChatGptOAuthProtocolTest {
    @Test fun `RFC PKCE vector and generated structure`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ChatGptPkce.challenge(verifier))
        val generated = ChatGptPkce.generate()
        assertTrue(generated.verifier.matches(Regex("[A-Za-z0-9_-]{43,128}")))
        assertFalse(generated.challenge.contains('='))
        assertFalse(generated.toString().contains(generated.verifier))
    }

    @Test fun `authorization URL is least privilege and complete`() {
        val url = ChatGptOAuthProtocol.authorizationUrl("http://localhost:1455/auth/callback", ChatGptPkce("verifier", "challenge"), "state").toHttpUrl()
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals(ChatGptOAuth.CLIENT_ID, url.queryParameter("client_id"))
        assertEquals("http://localhost:1455/auth/callback", url.queryParameter("redirect_uri"))
        assertEquals(ChatGptOAuth.SCOPES, url.queryParameter("scope"))
        assertEquals("challenge", url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("true", url.queryParameter("id_token_add_organizations"))
        assertEquals("true", url.queryParameter("codex_cli_simplified_flow"))
        assertEquals("state", url.queryParameter("state"))
        assertEquals(ChatGptOAuth.ORIGINATOR, url.queryParameter("originator"))
        assertFalse(url.toString().contains("api.connectors"))
    }

    @Test fun `callback validates route state errors and replay`() {
        assertEquals("ok", CallbackValidator("state").validate("/auth/callback", mapOf("state" to "state", "code" to "ok")))
        assertFails { CallbackValidator("state").validate("/auth/callback", mapOf("state" to "wrong", "code" to "x")) }
        assertFails { CallbackValidator("state").validate("/auth/callback", mapOf("code" to "x")) }
        assertFails { CallbackValidator("state").validate("/auth/callback", mapOf("state" to "state")) }
        assertFails { CallbackValidator("state").validate("/else", emptyMap()) }
        val error = runCatching { CallbackValidator("state").validate("/auth/callback", mapOf("error" to "denied", "error_description" to "cancelled")) }.exceptionOrNull()
        assertTrue(error?.message?.contains("cancelled") == true)
        val once = CallbackValidator("state")
        once.validate("/auth/callback", mapOf("state" to "state", "code" to "one"))
        assertFails { once.validate("/auth/callback", mapOf("state" to "state", "code" to "two")) }
    }

    private fun assertFails(block: () -> Unit) = assertTrue(runCatching(block).isFailure)
}
