package com.mobileclaw.auth.chatgpt

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.mobileclaw.vpn.AppHttpProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

internal data class TokenResponse(@SerializedName("id_token") val idToken: String?, @SerializedName("access_token") val accessToken: String?, @SerializedName("refresh_token") val refreshToken: String?, @SerializedName("expires_in") val expiresIn: Long?)
internal data class DeviceCode(@SerializedName("device_auth_id") val deviceAuthId: String, @SerializedName("user_code") val userCode: String, val interval: Long)
internal data class DeviceToken(@SerializedName("authorization_code") val authorizationCode: String, @SerializedName("code_verifier") val codeVerifier: String)

internal open class ChatGptAuthApi {
    private val gson = Gson()
    private val client = OkHttpClient.Builder().proxySelector(AppHttpProxy.proxySelector()).callTimeout(30, TimeUnit.SECONDS).build()
    protected open suspend fun execute(request: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
    }
    suspend fun exchange(code: String, redirect: String, verifier: String): TokenResponse = token(FormBody.Builder()
        .add("grant_type", "authorization_code").add("code", code).add("redirect_uri", redirect)
        .add("client_id", ChatGptOAuth.CLIENT_ID).add("code_verifier", verifier).build())
    suspend fun refresh(refresh: String): TokenResponse = token(FormBody.Builder().add("grant_type", "refresh_token")
        .add("refresh_token", refresh).add("client_id", ChatGptOAuth.CLIENT_ID).build())
    private suspend fun token(body: FormBody): TokenResponse {
        val (status, result) = execute(Request.Builder().url("${ChatGptOAuth.ISSUER}/oauth/token").post(body).build())
        if (status !in 200..299) throw ChatGptAuthException("Could not complete ChatGPT sign-in.")
        return gson.fromJson(result, TokenResponse::class.java)
    }
    suspend fun deviceCode(): DeviceCode {
        val body = gson.toJson(mapOf("client_id" to ChatGptOAuth.CLIENT_ID)).toRequestBody("application/json".toMediaType())
        val (status, result) = execute(Request.Builder().url("${ChatGptOAuth.ISSUER}/api/accounts/deviceauth/usercode").post(body).build())
        if (status !in 200..299) throw ChatGptAuthException("Could not start device sign-in.")
        return gson.fromJson(result, DeviceCode::class.java)
    }
    suspend fun pollDevice(id: String, code: String): DeviceToken? {
        val body = gson.toJson(mapOf("device_auth_id" to id, "user_code" to code)).toRequestBody("application/json".toMediaType())
        val (status, result) = execute(Request.Builder().url("${ChatGptOAuth.ISSUER}/api/accounts/deviceauth/token").post(body).build())
        if (status == 403 || status == 404) return null
        if (status !in 200..299) throw ChatGptAuthException("Could not complete device sign-in.")
        return gson.fromJson(result, DeviceToken::class.java)
    }
    suspend fun revoke(tokens: ChatGptOAuthTokens) {
        val token = tokens.refreshToken ?: tokens.accessToken
        val hint = if (tokens.refreshToken != null) "refresh_token" else "access_token"
        val body = gson.toJson(mapOf("token" to token, "token_type_hint" to hint, "client_id" to ChatGptOAuth.CLIENT_ID)).toRequestBody("application/json".toMediaType())
        execute(Request.Builder().url("${ChatGptOAuth.ISSUER}/oauth/revoke").post(body).build())
    }
}
