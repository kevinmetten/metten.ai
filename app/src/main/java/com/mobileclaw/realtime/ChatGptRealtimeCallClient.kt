package com.mobileclaw.realtime

import com.google.gson.JsonObject
import com.mobileclaw.auth.chatgpt.ChatGptAuthManager
import com.mobileclaw.auth.chatgpt.ChatGptBackendCredentials
import com.mobileclaw.llm.CHATGPT_BACKEND_ROOT
import com.mobileclaw.llm.chatGptHeaders
import java.io.IOException
import java.util.UUID
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
) {
    constructor(auth: ChatGptAuthManager) : this(
        RealtimeCredentialProvider { auth.getValidBackendCredentials() },
        OkHttpClient(),
        CHATGPT_BACKEND_ROOT,
    )

    suspend fun createCall(offerSdp: String): RealtimeCallAnswer {
        val credential = try {
            credentials.credentials()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw RealtimeVoiceException(RealtimeVoiceDiagnostic.NOT_SIGNED_IN, "Sign in to ChatGPT to use Live Voice.")
        }
        val request = buildRequest(offerSdp, credential)
        return client.newCall(request).awaitRealtimeResponse()
    }

    internal fun buildRequest(offerSdp: String, credential: ChatGptBackendCredentials): Request {
        val url = root.toHttpUrl().newBuilder()
            .addPathSegments("realtime/calls")
            .addQueryParameter("intent", "quicksilver")
            .addQueryParameter("architecture", "avas")
            .build()
        val session = JsonObject().apply {
            addProperty("type", "realtime")
            addProperty("model", REALTIME_MODEL)
            add("output_modalities", com.google.gson.JsonArray().apply { add("audio") })
            add("audio", JsonObject().apply {
                add("input", JsonObject().apply {
                    add("turn_detection", JsonObject().apply {
                        addProperty("type", "server_vad")
                        addProperty("create_response", true)
                        addProperty("interrupt_response", true)
                    })
                })
            })
        }
        val body = JsonObject().apply {
            addProperty("sdp", offerSdp)
            add("session", session)
        }.toString()
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
        const val REALTIME_MODEL = "gpt-live-1-codex"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        internal fun looksLikeSdp(value: String) = value.lineSequence().firstOrNull()?.trim() == "v=0"

        internal fun parseCallId(location: String?): String? {
            val segment = location?.substringBefore('?')?.trimEnd('/')?.substringAfterLast('/')?.takeIf(String::isNotBlank)
                ?: return null
            return segment.takeIf { it.startsWith("rtc_") || runCatching { UUID.fromString(it) }.isSuccess }
        }

        internal fun classifyStatus(status: Int): RealtimeVoiceException = when (status) {
            401 -> RealtimeVoiceException(RealtimeVoiceDiagnostic.NOT_SIGNED_IN, "Your ChatGPT session has expired.")
            403 -> RealtimeVoiceException(RealtimeVoiceDiagnostic.ACCESS_DENIED, "Live Voice is not available for this ChatGPT account.")
            404 -> RealtimeVoiceException(RealtimeVoiceDiagnostic.ROUTE_UNAVAILABLE, "The ChatGPT realtime route is unavailable.")
            429 -> RealtimeVoiceException(RealtimeVoiceDiagnostic.USAGE_LIMIT, "The ChatGPT Live Voice usage limit was reached.")
            else -> RealtimeVoiceException(RealtimeVoiceDiagnostic.PROTOCOL_REJECTED, "ChatGPT rejected the realtime call (HTTP $status).")
        }
    }
}
