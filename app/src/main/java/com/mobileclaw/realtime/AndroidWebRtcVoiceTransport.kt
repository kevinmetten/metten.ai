package com.mobileclaw.realtime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.MediaStreamTrack
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Audio-only WebRTC transport for Codex's AVAS realtime V1 adapter. */
class AndroidWebRtcVoiceTransport(
    context: Context,
    private val calls: ChatGptRealtimeCallClient,
) : RealtimeVoiceTransport {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val closed = AtomicBoolean(false)
    private var disconnectCallback: ((RealtimeVoiceException?) -> Unit)? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var source: AudioSource? = null
    private var localAudio: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    private val connectionReady = CompletableDeferred<Unit>()
    private val iceGatheringComplete = CompletableDeferred<Unit>()
    private val remoteAudioTracks = mutableListOf<AudioTrack>()
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphone = false

    override suspend fun connect(onDisconnected: (RealtimeVoiceException?) -> Unit) {
        try {
            check(!closed.get())
            disconnectCallback = onDisconnected
            acquireAudioRoute()
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions(),
            )
            val currentFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
            factory = currentFactory
            val currentSource = try {
                currentFactory.createAudioSource(MediaConstraints())
            } catch (_: SecurityException) {
                throw RealtimeVoiceException(RealtimeVoiceDiagnostic.MIC_FAILURE, "The microphone could not be opened.")
            }
            source = currentSource
            val currentAudio = currentFactory.createAudioTrack("metten_voice_audio", currentSource).apply { setEnabled(true) }
            localAudio = currentAudio
            val configuration = PeerConnection.RTCConfiguration(
                listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()),
            ).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
            val currentPeer = currentFactory.createPeerConnection(configuration, observer())
                ?: throw RealtimeVoiceException(RealtimeVoiceDiagnostic.NETWORK_FAILED, "This device could not create a WebRTC audio connection.")
            peer = currentPeer
            currentPeer.addTrack(currentAudio, listOf("metten_voice_stream"))
            val offer = currentPeer.createOfferAwait().also { currentPeer.setDescriptionAwait(it, local = true) }
            val completeOffer = currentPeer.awaitIceGathering(offer)
            val answer = calls.createCall(completeOffer.description)
            if (closed.get()) throw CancellationException("Voice session stopped.")
            currentPeer.setDescriptionAwait(SessionDescription(SessionDescription.Type.ANSWER, answer.sdp), local = false)
            withTimeout(CONNECTION_TIMEOUT_MS) { connectionReady.await() }
            // WebRTC's native AudioDeviceModule renders enabled remote AudioTracks. No PCM bridge
            // is needed. The V1 media conversation does not require Codex's agent sideband.
        } catch (_: TimeoutCancellationException) {
            close()
            throw RealtimeVoiceException(RealtimeVoiceDiagnostic.NETWORK_FAILED, "The Live Voice connection timed out.")
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    override fun setMuted(muted: Boolean) {
        localAudio?.setEnabled(!muted)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        disconnectCallback = null
        localAudio?.setEnabled(false)
        synchronized(remoteAudioTracks) {
            remoteAudioTracks.forEach { it.setEnabled(false) }
            remoteAudioTracks.clear()
        }
        iceGatheringComplete.cancel()
        connectionReady.cancel()
        peer?.close()
        peer?.dispose()
        localAudio?.dispose()
        source?.dispose()
        factory?.dispose()
        peer = null
        localAudio = null
        source = null
        factory = null
        releaseAudioRoute()
    }

    private fun observer() = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) iceGatheringComplete.complete(Unit)
        }
        override fun onIceCandidate(candidate: IceCandidate?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val track = receiver?.track()
            if (track?.kind() == MediaStreamTrack.AUDIO_TRACK_KIND && track is AudioTrack) {
                track.setEnabled(true)
                synchronized(remoteAudioTracks) { remoteAudioTracks += track }
            }
        }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
            Log.i(TAG, "WebRTC connection state: $state")
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> connectionReady.complete(Unit)
                PeerConnection.PeerConnectionState.FAILED -> {
                    val failure = RealtimeVoiceException(RealtimeVoiceDiagnostic.NETWORK_FAILED, "Live Voice lost its WebRTC connection.")
                    connectionReady.completeExceptionally(failure)
                    disconnectCallback?.invoke(failure)
                }
                PeerConnection.PeerConnectionState.CLOSED -> disconnectCallback?.invoke(
                    RealtimeVoiceException(RealtimeVoiceDiagnostic.REMOTE_CLOSED, "The remote Live Voice session ended."),
                )
                else -> Unit
            }
        }
    }

    private suspend fun PeerConnection.createOfferAwait(): SessionDescription = suspendCancellableCoroutine { continuation ->
        createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (continuation.isActive) {
                    sdp?.let(continuation::resume) ?: continuation.resumeWithException(protocolFailure())
                }
            }
            override fun onCreateFailure(error: String?) { if (continuation.isActive) continuation.resumeWithException(protocolFailure()) }
        }, MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false")
        })
    }

    private suspend fun PeerConnection.setDescriptionAwait(description: SessionDescription, local: Boolean) =
        suspendCancellableCoroutine<Unit> { continuation ->
            val observer = object : SimpleSdpObserver() {
                override fun onSetSuccess() { if (continuation.isActive) continuation.resume(Unit) }
                override fun onSetFailure(error: String?) { if (continuation.isActive) continuation.resumeWithException(protocolFailure()) }
            }
            if (local) setLocalDescription(observer, description) else setRemoteDescription(observer, description)
        }

    private suspend fun PeerConnection.awaitIceGathering(fallback: SessionDescription): SessionDescription =
        if (iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
            localDescription ?: fallback
        } else {
            withTimeout(ICE_GATHERING_TIMEOUT_MS) { iceGatheringComplete.await() }
            localDescription ?: fallback
        }

    private fun acquireAudioRoute() {
        previousMode = audioManager.mode
        previousSpeakerphone = audioManager.isSpeakerphoneOn
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).build()
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw RealtimeVoiceException(RealtimeVoiceDiagnostic.AUDIO_PLAYBACK_FAILURE, "Audio focus is unavailable for Live Voice.")
        }
        focusRequest = request
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
    }

    private fun releaseAudioRoute() {
        focusRequest?.let(audioManager::abandonAudioFocusRequest)
        focusRequest = null
        audioManager.isSpeakerphoneOn = previousSpeakerphone
        audioManager.mode = previousMode
    }

    private fun protocolFailure() = RealtimeVoiceException(
        RealtimeVoiceDiagnostic.PROTOCOL_REJECTED,
        "WebRTC could not negotiate the Live Voice audio session.",
    )

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    private companion object {
        const val TAG = "LiveVoice"
        const val ICE_GATHERING_TIMEOUT_MS = 10_000L
        const val CONNECTION_TIMEOUT_MS = 20_000L
    }
}
