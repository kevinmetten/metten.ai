package com.mobileclaw.realtime

import com.mobileclaw.auth.chatgpt.ChatGptAuthManager
import com.mobileclaw.auth.chatgpt.ChatGptBackendCredentials
import com.mobileclaw.auth.chatgpt.ChatGptRefreshException
import com.mobileclaw.llm.CHATGPT_BACKEND_ROOT
import com.mobileclaw.llm.chatGptHeaders
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface RealtimeCredentialProvider {
    suspend fun credentials(): ChatGptBackendCredentials
}

/** Isolated HTTP mapper/client for the ChatGPT subscription-backed WebRTC call endpoint. */
class ChatGptRealtimeCallClient internal constructor(
    private val credentials: RealtimeCredentialProvider,
    private val client: OkHttpClient,
    private val root: String,
    private val sessionConfig: CodexRealtimeSessionConfig,
) {
    constructor(auth: ChatGptAuthManager) : this(
        RealtimeCredentialProvider { auth.getValidBackendCredentials() },
        OkHttpClient(),
        CHATGPT_BACKEND_ROOT,
        CodexRealtimeSessionConfig(),
    )

    suspend fun createCall(offerSdp: String, requestContext: RealtimeRequestContext): RealtimeCallAnswer {
        val credential = try {
            credentials.credentials()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ChatGptRefreshException.Permanent) {
            throw RealtimeVoiceException(RealtimeVoiceDiagnostic.NOT_SIGNED_IN, "Your ChatGPT session expired. Sign in again.")
        } catch (_: ChatGptRefreshException.Transient) {
            throw RealtimeVoiceException(RealtimeVoiceDiagnostic.AUTH_REFRESH_FAILED, "Could not refresh the ChatGPT session. Try again.")
        } catch (_: Throwable) {
            throw RealtimeVoiceException(RealtimeVoiceDiagnostic.AUTH_REFRESH_FAILED, "Could not prepare the ChatGPT session. Try again.")
        }
        val request = buildRequest(offerSdp, credential, requestContext)
        return client.newCall(request).awaitRealtimeResponse(credential)
    }

    internal fun buildRequest(offerSdp: String, credential: ChatGptBackendCredentials, requestContext: RealtimeRequestContext): Request {
        val url = root.toHttpUrl().newBuilder()
            .addPathSegments("realtime/calls")
            .apply {
                sessionConfig.protocol.intent?.let { addQueryParameter("intent", it) }
                sessionConfig.protocol.architecture?.let { addQueryParameter("architecture", it) }
            }.build()
        val body = CodexRealtimeProtocolMapper.callBody(offerSdp, sessionConfig).toString()
        return Request.Builder().url(url)
            .chatGptHeaders(credential)
            .header("openai-alpha", "quicksilver=v2")
            .header("session-id", requestContext.sessionId)
            .header("thread-id", requestContext.threadId)
            .header("x-session-id", requestContext.realtimeSessionId)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private suspend fun Call.awaitRealtimeResponse(credential: ChatGptBackendCredentials): RealtimeCallAnswer =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        RealtimeVoiceException(RealtimeVoiceDiagnostic.NETWORK_FAILED, "Live Voice could not reach ChatGPT."),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!continuation.isActive) return
                        if (!it.isSuccessful) {
                            val detail = sanitizedErrorDetail(it, credential)
                            continuation.resumeWithException(classifyStatus(it.code, detail))
                            return
                        }
                        val callId = parseCallId(it.header("Location"))
                        if (callId == null) {
                            continuation.resumeWithException(
                                RealtimeVoiceException(RealtimeVoiceDiagnostic.PROTOCOL_REJECTED, "ChatGPT did not return a realtime call identifier."),
                            )
                            return
                        }
                        val answer = it.body?.string().orEmpty()
                        if (!looksLikeSdp(answer)) {
                            continuation.resumeWithException(
                                RealtimeVoiceException(RealtimeVoiceDiagnostic.PROTOCOL_REJECTED, "ChatGPT returned an invalid realtime answer."),
                            )
                            return
                        }
                        continuation.resume(RealtimeCallAnswer(answer, callId))
                    }
                }
            })
        }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        internal fun looksLikeSdp(value: String) = value.lineSequence().firstOrNull()?.trim() == "v=0"

        internal fun parseCallId(location: String?): String? {
            val clean = location?.trim()?.takeIf { it.isNotEmpty() && !it.any(Char::isWhitespace) } ?: return null
            val path = clean.substringBefore('#').substringBefore('?')
            return path.split('/').firstOrNull(::isValidCallId)
        }

        private fun isValidCallId(value: String): Boolean =
            (value.startsWith("rtc_") && value.length > "rtc_".length) || isUuidShaped(value)

        private fun isUuidShaped(value: String): Boolean = value.length == 36 && value.indices.all { index ->
            if (index in UUID_HYPHEN_POSITIONS) value[index] == '-' else value[index].isAsciiHexDigit()
        }

        private fun Char.isAsciiHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        private val UUID_HYPHEN_POSITIONS = setOf(8, 13, 18, 23)

        internal fun classifyStatus(status: Int, detail: String? = null): RealtimeVoiceException = when (status) {
            401 -> RealtimeVoiceException(RealtimeVoiceDiagnostic.NOT_SIGNED_IN, "Your ChatGPT session has expired.")
            403 -> RealtimeVoiceException(RealtimeVoiceDiagnostic.ACCESS_DENIED, "Live Voice is not available for this ChatGPT account.")
            404 -> RealtimeVoiceException(RealtimeVoiceDiagnostic.ROUTE_UNAVAILABLE, "The ChatGPT realtime route is unavailable.")
            429 -> RealtimeVoiceException(RealtimeVoiceDiagnostic.USAGE_LIMIT, "The ChatGPT Live Voice usage limit was reached.")
            else -> RealtimeVoiceException(
                RealtimeVoiceDiagnostic.PROTOCOL_REJECTED,
                "Realtime request rejected (HTTP $status)${detail?.let { ": $it" }.orEmpty()}.",
            )
        }

        private fun sanitizedErrorDetail(response: Response, credential: ChatGptBackendCredentials): String? {
            val reader = response.body?.charStream() ?: return null
            val buffer = CharArray(MAX_ERROR_BODY_CHARS)
            val count = reader.read(buffer).coerceAtLeast(0)
            if (count == 0) return null
            val json = runCatching { com.google.gson.JsonParser.parseString(String(buffer, 0, count)).asJsonObject }.getOrNull()
                ?: return null
            val error = json.getAsJsonObject("error")
            val candidate = sequenceOf(error?.get("message"), error?.get("code"), json["message"], json["detail"])
                .firstOrNull { it != null && it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString ?: return null
            return candidate.replace(credential.accessToken, "[REDACTED]")
                .replace(Regex("(?i)bearer\\s+\\S+"), "Bearer [REDACTED]")
                .replace(Regex("[A-Za-z0-9_.-]{24,}"), "[REDACTED]")
                .filter { it >= ' ' && it != '\u007f' }
                .trim().take(MAX_ERROR_DETAIL_CHARS).takeIf(String::isNotBlank)
        }

        private const val MAX_ERROR_BODY_CHARS = 2_048
        private const val MAX_ERROR_DETAIL_CHARS = 300
    }
}
