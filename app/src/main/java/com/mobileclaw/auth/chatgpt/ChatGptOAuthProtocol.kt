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
        val state = parameters["state"]?.takeIf(String::isNotBlank) ?: throw ChatGptAuthException("The sign-in response was missing state.")
        if (state != expectedState) throw ChatGptAuthException("The sign-in response did not match this request. Please try again.")
        parameters["error"]?.let { throw ChatGptAuthException(parameters["error_description"] ?: "Sign-in was cancelled.") }
        return parameters["code"]?.takeIf(String::isNotBlank) ?: throw ChatGptAuthException("The sign-in response was missing a code.")
    }
}

object ChatGptJwtMetadata {
    fun extract(idToken: String?, accessToken: String?): ChatGptAccountInfo {
        val id = payload(idToken)
        val access = payload(accessToken)
        val idAuth = id?.obj("https://api.openai.com/auth")
        val accessAuth = access?.obj("https://api.openai.com/auth")
        val idProfile = id?.obj("https://api.openai.com/profile")
        val accessProfile = access?.obj("https://api.openai.com/profile")
        fun account(root: JsonObject?): String? = root?.string("chatgpt_account_id")
            ?: root?.obj("https://api.openai.com/auth")?.string("chatgpt_account_id")
            ?: root?.getAsJsonArray("organizations")?.firstOrNull()?.asJsonObject?.string("id")
        val residency = idAuth?.string("chatgpt_compute_residency") ?: id?.string("chatgpt_compute_residency")
            ?: accessAuth?.string("chatgpt_compute_residency") ?: access?.string("chatgpt_compute_residency")
        return ChatGptAccountInfo(
            email = id?.string("email") ?: idProfile?.string("email") ?: access?.string("email") ?: accessProfile?.string("email"),
            planType = idAuth?.string("chatgpt_plan_type") ?: id?.string("chatgpt_plan_type")
                ?: accessAuth?.string("chatgpt_plan_type") ?: access?.string("chatgpt_plan_type"),
            chatGptUserId = idAuth?.string("chatgpt_user_id") ?: idAuth?.string("user_id") ?: id?.string("chatgpt_user_id")
                ?: accessAuth?.string("chatgpt_user_id") ?: accessAuth?.string("user_id") ?: access?.string("chatgpt_user_id"),
            chatGptAccountId = account(id) ?: account(access),
            computeResidency = residency?.takeUnless { it == "no_constraint" },
            isFedRamp = boolean(idAuth, "chatgpt_account_is_fedramp") ?: boolean(id, "chatgpt_account_is_fedramp")
                ?: boolean(accessAuth, "chatgpt_account_is_fedramp") ?: boolean(access, "chatgpt_account_is_fedramp"),
        )
    }

    fun jwtExpirationMillis(token: String?): Long? = payload(token)?.get("exp")?.runCatching { asLong * 1000L }?.getOrNull()
    private fun payload(token: String?): JsonObject? = runCatching {
        val part = token?.split('.')?.getOrNull(1) ?: return null
        JsonParser.parseString(String(Base64.getUrlDecoder().decode(part))).asJsonObject
    }.getOrNull()
    private fun JsonObject.string(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
    private fun JsonObject.obj(name: String) = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun boolean(value: JsonObject?, name: String) = value?.get(name)?.takeIf { it.isJsonPrimitive }?.runCatching { asBoolean }?.getOrNull()
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
