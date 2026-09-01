package com.mobileclaw.auth.chatgpt

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mobileclaw.vpn.AppHttpProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class TokenResponse(@SerializedName("id_token") val idToken: String?, @SerializedName("access_token") val accessToken: String?, @SerializedName("refresh_token") val refreshToken: String?, @SerializedName("expires_in") val expiresIn: Long?)
internal data class DeviceCode(val deviceAuthId: String, val userCode: String, val interval: Long)
internal data class DeviceToken(val authorizationCode: String, val codeVerifier: String, val codeChallenge: String?)
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

internal object ChatGptDeviceProtocol {
    fun initialResponse(status: Int, json: String): DeviceCode {
        if (status == 404) throw ChatGptAuthException("Device-code sign-in is not available. Use Sign in with ChatGPT instead.")
        if (status !in 200..299) throw ChatGptAuthException("Could not start device sign-in.")
        return runCatching { parseCode(json) }.getOrElse { throw ChatGptAuthException("Could not start device sign-in.") }
    }

    fun pollResponse(status: Int, json: String): DeviceToken? {
        if (status == 403 || status == 404) return null
        if (status !in 200..299) throw ChatGptAuthException("Could not complete device sign-in.")
        return runCatching { parseToken(json) }.getOrElse { throw ChatGptAuthException("Could not complete device sign-in.") }
    }

    fun parseCode(json: String): DeviceCode {
        val root = JsonParser.parseString(json).asJsonObject
        val id = root.requiredString("device_auth_id")
        val code = root.string("user_code") ?: root.requiredString("usercode")
        val interval = root.get("interval")?.runCatching { asString.toLong() }?.getOrNull()
            ?.takeIf { it > 0 } ?: throw ChatGptAuthException("The device sign-in service returned an invalid response.")
        return DeviceCode(id, code, interval)
    }

    fun parseToken(json: String): DeviceToken {
        val root = JsonParser.parseString(json).asJsonObject
        return DeviceToken(root.requiredString("authorization_code"), root.requiredString("code_verifier"), root.string("code_challenge"))
    }

    private fun JsonObject.string(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
    private fun JsonObject.requiredString(name: String) = string(name)
        ?: throw ChatGptAuthException("The device sign-in service returned an invalid response.")
}

internal object OkHttpCallAwaiter {
    suspend fun await(call: Call): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        val terminal = AtomicBoolean(false)
        continuation.invokeOnCancellation {
            terminal.compareAndSet(false, true)
            call.cancel()
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (terminal.compareAndSet(false, true)) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val result = try {
                        it.code to it.body?.string().orEmpty()
                    } catch (failure: IOException) {
                        if (terminal.compareAndSet(false, true)) continuation.resumeWithException(failure)
                        return
                    }
                    if (terminal.compareAndSet(false, true)) continuation.resume(result)
                }
            }
        })
    }
}

internal class ChatGptAuthApi : ChatGptAuthService {
    private val gson = Gson()
    private val client = OkHttpClient.Builder().proxySelector(AppHttpProxy.proxySelector()).callTimeout(30, TimeUnit.SECONDS).build()
    private suspend fun execute(request: Request): Pair<Int, String> = try {
        OkHttpCallAwaiter.await(client.newCall(request))
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
        return ChatGptDeviceProtocol.initialResponse(status, result)
    }
    override suspend fun pollDevice(id: String, code: String): DeviceToken? {
        val body = gson.toJson(mapOf("device_auth_id" to id, "user_code" to code)).toRequestBody("application/json".toMediaType())
        val (status, result) = execute(Request.Builder().url("${ChatGptOAuth.ISSUER}/api/accounts/deviceauth/token").post(body).build())
        return ChatGptDeviceProtocol.pollResponse(status, result)
    }
    override suspend fun revoke(tokens: ChatGptOAuthTokens) { execute(ChatGptAuthRequests.revoke(tokens)) }
}
