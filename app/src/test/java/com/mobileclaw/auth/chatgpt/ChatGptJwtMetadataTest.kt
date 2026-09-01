package com.mobileclaw.auth.chatgpt

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ChatGptJwtMetadataTest {
    private fun jwt(payload: Any): String = "x.${Base64.getUrlEncoder().withoutPadding().encodeToString(Gson().toJson(payload).toByteArray())}.x"

    @Test fun `extracts custom account metadata`() {
        val token = jwt(mapOf("email" to "person@example.test", "https://api.openai.com/auth" to mapOf(
            "chatgpt_plan_type" to "future_plan", "chatgpt_user_id" to "user", "chatgpt_account_id" to "account",
            "chatgpt_compute_residency" to "eu", "chatgpt_account_is_fedramp" to true)))
        val info = ChatGptJwtMetadata.extract(token, null)
        assertEquals("person@example.test", info.email); assertEquals("future_plan", info.planType)
        assertEquals("user", info.chatGptUserId); assertEquals("account", info.chatGptAccountId)
        assertEquals("eu", info.computeResidency); assertEquals(true, info.isFedRamp)
    }

    @Test fun `account fallbacks residency normalization and malformed safety`() {
        val organizations = jwt(mapOf("organizations" to listOf(mapOf("id" to "org")), "chatgpt_compute_residency" to "no_constraint"))
        assertEquals("org", ChatGptJwtMetadata.extract(organizations, null).chatGptAccountId)
        assertNull(ChatGptJwtMetadata.extract(organizations, null).computeResidency)
        val access = jwt(mapOf("chatgpt_account_id" to "access-account"))
        assertEquals("access-account", ChatGptJwtMetadata.extract("bad", access).chatGptAccountId)
        assertEquals(ChatGptAccountInfo(), ChatGptJwtMetadata.extract("bad", "also.bad"))
    }

    @Test fun `access token fills metadata omitted by id token`() {
        val id = jwt(mapOf("email" to "id@example.test"))
        val access = jwt(mapOf("https://api.openai.com/auth" to mapOf(
            "chatgpt_plan_type" to "plus", "chatgpt_user_id" to "access-user",
            "chatgpt_compute_residency" to "eu", "chatgpt_account_is_fedramp" to true,
        ), "chatgpt_account_id" to "access-account"))
        val info = ChatGptJwtMetadata.extract(id, access)
        assertEquals("id@example.test", info.email); assertEquals("plus", info.planType)
        assertEquals("access-user", info.chatGptUserId); assertEquals("access-account", info.chatGptAccountId)
        assertEquals("eu", info.computeResidency); assertEquals(true, info.isFedRamp)
    }

    @Test fun `account id supports top level custom auth and organization paths`() {
        assertEquals("top", ChatGptJwtMetadata.extract(jwt(mapOf("chatgpt_account_id" to "top")), null).chatGptAccountId)
        assertEquals("custom", ChatGptJwtMetadata.extract(jwt(mapOf("https://api.openai.com/auth" to mapOf("chatgpt_account_id" to "custom"))), null).chatGptAccountId)
        assertEquals("org", ChatGptJwtMetadata.extract(jwt(mapOf("organizations" to listOf(mapOf("id" to "org")))), null).chatGptAccountId)
    }

    @Test fun `expiration uses response JWT and skew`() {
        assertEquals(1_061_000L, ChatGptExpiration.resolve(1_000L, 1060, null))
        val token = jwt(mapOf("exp" to 1234))
        assertEquals(1_234_000L, ChatGptExpiration.resolve(0, null, token))
        assertTrue(ChatGptExpiration.requiresRefresh(60_000, 1))
        assertFalse(ChatGptExpiration.requiresRefresh(70_002, 1))
        assertEquals(ChatGptExpiration.FALLBACK_MILLIS, ChatGptExpiration.resolve(0, null, "bad"))
    }

    @Test fun `secret diagnostics are redacted`() {
        val access = "TEST_ACCESS_SECRET_DO_NOT_LEAK"; val refresh = "TEST_REFRESH_SECRET_DO_NOT_LEAK"
        val tokens = ChatGptOAuthTokens("id-secret", access, refresh, 1)
        assertFalse(tokens.toString().contains(access)); assertFalse(tokens.toString().contains(refresh))
        assertFalse(ChatGptBackendCredentials(access, "account", null).toString().contains(access))
        assertFalse(ChatGptAuthState.SignedIn(ChatGptAccountInfo(email = "a@b.test")).toString().contains(access))
    }
}
