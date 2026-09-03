package com.mobileclaw.realtime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Audio-only WebRTC V3 transport. No transcript, audio, SDP, ICE secret, or call id is logged/persisted. */
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
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphone = false

    override suspend fun connect(onDisconnected: (RealtimeVoiceException?) -> Unit) {
        check(!closed.get())
        disconnectCallback = onDisconnected
        acquireAudioRoute()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions(),
        )
        val currentFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        factory = currentFactory
        val currentSource = currentFactory.createAudioSource(MediaConstraints())
        source = currentSource
        val currentAudio = currentFactory.createAudioTrack("metten_voice_audio", currentSource).apply { setEnabled(true) }
        localAudio = currentAudio
        val configuration = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()),
        ).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val currentPeer = currentFactory.createPeerConnection(configuration, observer())
            ?: throw RealtimeVoiceException(RealtimeVoiceDiagnostic.UNKNOWN_FAILURE, "This device could not create a WebRTC audio connection.")
        peer = currentPeer
        currentPeer.addTrack(currentAudio, listOf("metten_voice_stream"))
        val offer = currentPeer.createOfferAwait().also { currentPeer.setDescriptionAwait(it, local = true) }
        val completeOffer = currentPeer.awaitIceGathering(offer)
        val answer = calls.createCall(completeOffer.description)
        if (closed.get()) return
        currentPeer.setDescriptionAwait(SessionDescription(SessionDescription.Type.ANSWER, answer.sdp), local = false)
        connectionReady.await()
        // Basic speech is carried by the negotiated media transceivers. V1 deliberately omits
        // the experimental orchestration sideband; its absence cannot expose tools or fail audio.
    }

    override fun setMuted(muted: Boolean) {
        localAudio?.setEnabled(!muted)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        disconnectCallback = null
        localAudio?.setEnabled(false)
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
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidate(candidate: IceCandidate?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
            Log.i(TAG, "WebRTC connection state: $state")
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> connectionReady.complete(Unit)
                PeerConnection.PeerConnectionState.FAILED -> disconnectCallback?.invoke(
                    RealtimeVoiceException(RealtimeVoiceDiagnostic.NETWORK_FAILED, "Live Voice lost its WebRTC connection."),
                ).also { connectionReady.completeExceptionally(protocolFailure()) }
                PeerConnection.PeerConnectionState.CLOSED -> disconnectCallback?.invoke(null)
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
        suspendCancellableCoroutine { continuation ->
            if (iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
                continuation.resume(localDescription ?: fallback)
                return@suspendCancellableCoroutine
            }
            val timer = Thread {
                repeat(100) {
                    if (!continuation.isActive) return@Thread
                    if (iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
                        continuation.resume(localDescription ?: fallback)
                        return@Thread
                    }
                    Thread.sleep(50)
                }
                if (continuation.isActive) continuation.resume(localDescription ?: fallback)
            }
            timer.name = "voice-ice-gathering"
            timer.start()
        }

    private fun acquireAudioRoute() {
        previousMode = audioManager.mode
        previousSpeakerphone = audioManager.isSpeakerphoneOn
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).build().also(audioManager::requestAudioFocus)
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

    private companion object { const val TAG = "LiveVoice" }
}
