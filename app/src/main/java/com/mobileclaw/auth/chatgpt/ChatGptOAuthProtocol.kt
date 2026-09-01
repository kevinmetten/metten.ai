package com.mobileclaw.auth.chatgpt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.SecureRandom
import java.util.Base64

object ChatGptOAuthProtocol {
    fun generateState(random: SecureRandom = SecureRandom()): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    fun authorizationUrl(redirectUri: String, pkce: ChatGptPkce, state: String): String =
        "${ChatGptOAuth.ISSUER}/oauth/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", ChatGptOAuth.CLIENT_ID)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", ChatGptOAuth.SCOPES)
            .addQueryParameter("code_challenge", pkce.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("id_token_add_organizations", "true")
            .addQueryParameter("codex_cli_simplified_flow", "true")
            .addQueryParameter("state", state)
            .addQueryParameter("originator", ChatGptOAuth.ORIGINATOR).build().toString()
}

internal class CallbackValidator(private val expectedState: String) {
    private var completed = false
    @Synchronized fun validate(path: String, parameters: Map<String, String>): String {
        if (path != "/auth/callback") throw ChatGptAuthException("Unrelated callback route.")
        if (completed) throw ChatGptAuthException("This sign-in response was already handled.")
        completed = true
        parameters["error"]?.let { throw ChatGptAuthException(parameters["error_description"] ?: "Sign-in was cancelled.") }
        val state = parameters["state"] ?: throw ChatGptAuthException("The sign-in response was missing state.")
        if (state != expectedState) throw ChatGptAuthException("The sign-in response did not match this request. Please try again.")
        return parameters["code"] ?: throw ChatGptAuthException("The sign-in response was missing a code.")
    }
}

object ChatGptJwtMetadata {
    fun extract(idToken: String?, accessToken: String?): ChatGptAccountInfo {
        val id = payload(idToken)
        val access = payload(accessToken)
        val source = id ?: access ?: JsonObject()
        val auth = source.obj("https://api.openai.com/auth")
        val profile = source.obj("https://api.openai.com/profile")
        fun account(root: JsonObject?): String? = root?.string("chatgpt_account_id")
            ?: root?.obj("https://api.openai.com/auth")?.string("chatgpt_account_id")
            ?: root?.getAsJsonArray("organizations")?.firstOrNull()?.asJsonObject?.string("id")
        val residency = auth?.string("chatgpt_compute_residency") ?: source.string("chatgpt_compute_residency")
        return ChatGptAccountInfo(
            email = source.string("email") ?: profile?.string("email"),
            planType = auth?.string("chatgpt_plan_type") ?: source.string("chatgpt_plan_type"),
            chatGptUserId = auth?.string("chatgpt_user_id") ?: auth?.string("user_id") ?: source.string("chatgpt_user_id"),
            chatGptAccountId = account(id) ?: account(access),
            computeResidency = residency?.takeUnless { it == "no_constraint" },
            isFedRamp = auth?.get("chatgpt_account_is_fedramp")?.takeIf { it.isJsonPrimitive }?.asBoolean,
        )
    }

    fun jwtExpirationMillis(token: String?): Long? = payload(token)?.get("exp")?.runCatching { asLong * 1000L }?.getOrNull()
    private fun payload(token: String?): JsonObject? = runCatching {
        val part = token?.split('.')?.getOrNull(1) ?: return null
        JsonParser.parseString(String(Base64.getUrlDecoder().decode(part))).asJsonObject
    }.getOrNull()
    private fun JsonObject.string(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
    private fun JsonObject.obj(name: String) = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
}

object ChatGptExpiration {
    const val FALLBACK_MILLIS = 60 * 60 * 1000L
    const val SKEW_MILLIS = 60_000L
    fun resolve(now: Long, expiresIn: Long?, accessToken: String?): Long = when {
        expiresIn != null && expiresIn in 1..86_400 -> now + expiresIn * 1000L
        ChatGptJwtMetadata.jwtExpirationMillis(accessToken) != null -> ChatGptJwtMetadata.jwtExpirationMillis(accessToken)!!
        else -> now + FALLBACK_MILLIS
    }
    fun requiresRefresh(expiresAt: Long, now: Long) = expiresAt <= now + SKEW_MILLIS
}
