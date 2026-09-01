package com.mobileclaw.auth.chatgpt

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object ChatGptOAuth {
    const val ISSUER = "https://auth.openai.com"
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val SCOPES = "openid profile email offline_access"
    const val ORIGINATOR = "metten_ai_android"
    const val DEVICE_VERIFICATION_URL = "$ISSUER/codex/device"
    const val DEVICE_REDIRECT_URI = "$ISSUER/deviceauth/callback"
    val CALLBACK_PORTS = intArrayOf(1455, 1457)
    const val LOOPBACK_ADDRESS = "127.0.0.1"
}

data class ChatGptPkce(val verifier: String, val challenge: String) {
    companion object {
        fun generate(random: SecureRandom = SecureRandom()): ChatGptPkce {
            val bytes = ByteArray(64).also(random::nextBytes)
            val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            return ChatGptPkce(verifier, challenge(verifier))
        }

        fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8)),
        )
    }

    override fun toString() = "ChatGptPkce(verifier=[REDACTED], challenge=[REDACTED])"
}

class ChatGptOAuthTokens(
    val idToken: String?,
    val accessToken: String,
    val refreshToken: String?,
    val accessTokenExpiresAt: Long,
) {
    override fun toString() = "ChatGptOAuthTokens([REDACTED], expiresAt=$accessTokenExpiresAt)"
}

data class ChatGptAccountInfo(
    val email: String? = null,
    val planType: String? = null,
    val chatGptUserId: String? = null,
    val chatGptAccountId: String? = null,
    val computeResidency: String? = null,
    val isFedRamp: Boolean? = null,
)

data class ChatGptBackendCredentials(
    val accessToken: String,
    val accountId: String?,
    val computeResidency: String?,
) {
    override fun toString() = "ChatGptBackendCredentials(accessToken=[REDACTED], accountId=[REDACTED], residency=$computeResidency)"
}

sealed interface ChatGptAuthState {
    data object SignedOut : ChatGptAuthState
    data object SigningIn : ChatGptAuthState
    data class AwaitingBrowser(val message: String = "Complete sign-in in your browser.") : ChatGptAuthState
    data class AwaitingDeviceCode(val userCode: String) : ChatGptAuthState
    data class SignedIn(val account: ChatGptAccountInfo) : ChatGptAuthState
    data class Refreshing(val account: ChatGptAccountInfo) : ChatGptAuthState
    data class Error(val message: String) : ChatGptAuthState
}

class ChatGptAuthException(message: String) : Exception(message)
