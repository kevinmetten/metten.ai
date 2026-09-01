package com.mobileclaw.auth.chatgpt

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.mobileclaw.vpn.AppHttpProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class TokenResponse(@SerializedName("id_token") val idToken: String?, @SerializedName("access_token") val accessToken: String?, @SerializedName("refresh_token") val refreshToken: String?, @SerializedName("expires_in") val expiresIn: Long?)
internal data class DeviceCode(@SerializedName("device_auth_id") val deviceAuthId: String, @SerializedName("user_code") val userCode: String, val interval: Long)
internal data class DeviceToken(@SerializedName("authorization_code") val authorizationCode: String, @SerializedName("code_verifier") val codeVerifier: String)
private data class OAuthError(val error: String?)

internal interface ChatGptAuthService {
    suspend fun exchange(code: String, redirect: String, verifier: String): TokenResponse
    suspend fun refresh(refreshToken: String): TokenResponse
    suspend fun deviceCode(): DeviceCode
    suspend fun pollDevice(id: String, code: String): DeviceToken?
    suspend fun revoke(tokens: ChatGptOAuthTokens)
}

internal object ChatGptAuthRequests {
    fun exchange(code: String, redirect: String, verifier: String) = Request.Builder()
        .url("${ChatGptOAuth.ISSUER}/oauth/token").post(FormBody.Builder()
            .add("grant_type", "authorization_code").add("code", code).add("redirect_uri", redirect)
            .add("client_id", ChatGptOAuth.CLIENT_ID).add("code_verifier", verifier).build()).build()
    fun refresh(refreshToken: String) = Request.Builder().url("${ChatGptOAuth.ISSUER}/oauth/token")
        .post(FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refreshToken)
            .add("client_id", ChatGptOAuth.CLIENT_ID).build()).build()
    fun revoke(tokens: ChatGptOAuthTokens): Request {
        val values = linkedMapOf("token" to (tokens.refreshToken ?: tokens.accessToken),
            "token_type_hint" to if (tokens.refreshToken != null) "refresh_token" else "access_token")
        if (tokens.refreshToken != null) values["client_id"] = ChatGptOAuth.CLIENT_ID
        return Request.Builder().url("${ChatGptOAuth.ISSUER}/oauth/revoke")
            .post(Gson().toJson(values).toRequestBody("application/json".toMediaType())).build()
    }
}

internal class ChatGptAuthApi : ChatGptAuthService {
    private val gson = Gson()
    private val client = OkHttpClient.Builder().proxySelector(AppHttpProxy.proxySelector()).callTimeout(30, TimeUnit.SECONDS).build()
    private suspend fun execute(request: Request): Pair<Int, String> = try {
        withContext(Dispatchers.IO) { client.newCall(request).execute().use { it.code to it.body?.string().orEmpty() } }
    } catch (e: CancellationException) { throw e }
      catch (_: IOException) { throw ChatGptRefreshException.Transient("Could not reach the ChatGPT authentication service.") }

    override suspend fun exchange(code: String, redirect: String, verifier: String): TokenResponse =
        token(ChatGptAuthRequests.exchange(code, redirect, verifier), refreshing = false)

    override suspend fun refresh(refreshToken: String): TokenResponse =
        token(ChatGptAuthRequests.refresh(refreshToken), refreshing = true)

    private suspend fun token(request: Request, refreshing: Boolean): TokenResponse {
        val (status, result) = execute(request)
        if (status in 200..299) return runCatching { gson.fromJson(result, TokenResponse::class.java) }
            .getOrElse { throw ChatGptRefreshException.Transient("The ChatGPT authentication service returned an invalid response.") }
        if (refreshing && status in 400..499 && runCatching { gson.fromJson(result, OAuthError::class.java).error }.getOrNull() == "invalid_grant")
            throw ChatGptRefreshException.Permanent()
        if (status >= 500 || status == 408 || status == 429) throw ChatGptRefreshException.Transient()
        if (refreshing) throw ChatGptRefreshException.Transient()
        throw ChatGptAuthException("Could not complete ChatGPT sign-in.")
    }

    override suspend fun deviceCode(): DeviceCode {
        val body = gson.toJson(mapOf("client_id" to ChatGptOAuth.CLIENT_ID)).toRequestBody("application/json".toMediaType())
        val (status, result) = execute(Request.Builder().url("${ChatGptOAuth.ISSUER}/api/accounts/deviceauth/usercode").post(body).build())
        if (status !in 200..299) throw ChatGptAuthException("Could not start device sign-in.")
        return runCatching { gson.fromJson(result, DeviceCode::class.java) }.getOrElse { throw ChatGptAuthException("Could not start device sign-in.") }
    }
    override suspend fun pollDevice(id: String, code: String): DeviceToken? {
        val body = gson.toJson(mapOf("device_auth_id" to id, "user_code" to code)).toRequestBody("application/json".toMediaType())
        val (status, result) = execute(Request.Builder().url("${ChatGptOAuth.ISSUER}/api/accounts/deviceauth/token").post(body).build())
        if (status == 403 || status == 404) return null
        if (status !in 200..299) throw ChatGptAuthException("Could not complete device sign-in.")
        return runCatching { gson.fromJson(result, DeviceToken::class.java) }.getOrElse { throw ChatGptAuthException("Could not complete device sign-in.") }
    }
    override suspend fun revoke(tokens: ChatGptOAuthTokens) { execute(ChatGptAuthRequests.revoke(tokens)) }
}
