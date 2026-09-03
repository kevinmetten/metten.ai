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

    suspend fun createCall(offerSdp: String): RealtimeCallAnswer {
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
        val request = buildRequest(offerSdp, credential)
        return client.newCall(request).awaitRealtimeResponse()
    }

    internal fun buildRequest(offerSdp: String, credential: ChatGptBackendCredentials): Request {
        val url = root.toHttpUrl().newBuilder()
            .addPathSegments("realtime/calls")
            .apply {
                sessionConfig.protocol.intent?.let { addQueryParameter("intent", it) }
                sessionConfig.protocol.architecture?.let { addQueryParameter("architecture", it) }
            }.build()
        val body = CodexRealtimeProtocolMapper.callBody(offerSdp, sessionConfig).toString()
        return Request.Builder().url(url)
            .chatGptHeaders(credential)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private suspend fun Call.awaitRealtimeResponse(): RealtimeCallAnswer =
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
                            continuation.resumeWithException(classifyStatus(it.code))
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

        internal fun classifyStatus(status: Int): RealtimeVoiceException = when (status) {
            401 -> RealtimeVoiceException(RealtimeVoiceDiagnostic.NOT_SIGNED_IN, "Your ChatGPT session has expired.")
            403 -> RealtimeVoiceException(RealtimeVoiceDiagnostic.ACCESS_DENIED, "Live Voice is not available for this ChatGPT account.")
            404 -> RealtimeVoiceException(RealtimeVoiceDiagnostic.ROUTE_UNAVAILABLE, "The ChatGPT realtime route is unavailable.")
            429 -> RealtimeVoiceException(RealtimeVoiceDiagnostic.USAGE_LIMIT, "The ChatGPT Live Voice usage limit was reached.")
            else -> RealtimeVoiceException(RealtimeVoiceDiagnostic.PROTOCOL_REJECTED, "ChatGPT rejected the realtime call (HTTP $status).")
        }
    }
}
