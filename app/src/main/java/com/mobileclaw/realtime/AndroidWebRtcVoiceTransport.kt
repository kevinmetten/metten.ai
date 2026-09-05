package com.mobileclaw.realtime

import android.content.Context
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
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.audio.AudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Audio-only WebRTC transport for ChatGPT's Frameless Bidi realtime V3 adapter. */
class AndroidWebRtcVoiceTransport(
    context: Context,
    private val calls: ChatGptRealtimeCallClient,
    sideband: RealtimeSideband? = null,
) : RealtimeControlTransport {
    private val appContext = context.applicationContext
    private val requestContext = RealtimeRequestContext.create()
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val closed = AtomicBoolean(false)
    private val nativeEvents = RealtimeVoiceNativeEventGate()
    private val sidebandAttachment = TransportSidebandAttachment(sideband)
    private var disconnectCallback: ((RealtimeVoiceException?) -> Unit)? = null
    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: AudioDeviceModule? = null
    private var peer: PeerConnection? = null
    private var eventsChannel: DataChannel? = null
    private var source: AudioSource? = null
    private var localAudio: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    private val connectionReady = CompletableDeferred<Unit>()
    private val iceGatheringComplete = CompletableDeferred<Unit>()
    private val remoteAudioTracks = mutableListOf<AudioTrack>()
    private var previousMode = AudioManager.MODE_NORMAL
    private var controlGeneration = -1L
    private var delegationListener: ((RealtimeDelegationRequest) -> Unit)? = null

    override fun bindControl(generation: Long, listener: (RealtimeDelegationRequest) -> Unit) {
        controlGeneration = generation
        delegationListener = listener
    }

    override fun send(update: RealtimeDelegationUpdate): Boolean = sidebandAttachment.send(update)

    override suspend fun connect(onDisconnected: (RealtimeVoiceException?) -> Unit) {
        try {
            check(!closed.get())
            disconnectCallback = onDisconnected
            acquireAudioRoute()
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions(),
            )
            val currentAudioDeviceModule = JavaAudioDeviceModule.builder(appContext)
                .setAudioAttributes(VoiceAudioPolicy.playbackAttributes)
                .createAudioDeviceModule()
            audioDeviceModule = currentAudioDeviceModule
            val currentFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(currentAudioDeviceModule)
                .createPeerConnectionFactory()
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
            // Current Codex WebRTC startup requires the SDP offer to advertise the standard
            // realtime events channel. Voice V1 does not send tools or persist events through it.
            eventsChannel = currentPeer.createDataChannel(REALTIME_EVENTS_CHANNEL, DataChannel.Init()).also {
                it.registerObserver(eventsObserver(it))
            }
            val offer = currentPeer.createOfferAwait().also { currentPeer.setDescriptionAwait(it, local = true) }
            val completeOffer = currentPeer.awaitIceGathering(offer)
            val answer = calls.createCall(completeOffer.description, requestContext)
            if (!sidebandAttachment.attach(answer.sidebandAttachment, controlGeneration) { request ->
                if (!closed.get() && request.voiceSessionGeneration == controlGeneration) delegationListener?.invoke(request)
            }) throw CancellationException("Voice session stopped.")
            currentPeer.setDescriptionAwait(SessionDescription(SessionDescription.Type.ANSWER, answer.sdp), local = false)
            withTimeout(CONNECTION_TIMEOUT_MS) { connectionReady.await() }
            // WebRTC's native AudioDeviceModule renders enabled remote AudioTracks. No PCM bridge
            // is needed. This V1 product milestone does not expose Codex agent-side delegation.
        } catch (_: TimeoutCancellationException) {
            RealtimeVoiceRuntimeDiagnostics.event(RealtimeVoiceRuntimeReason.CONNECTION_TIMEOUT, terminal = true)
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
        // Mark local teardown before invoking native close methods, whose callbacks may be synchronous.
        if (!nativeEvents.beginLocalClose() || !closed.compareAndSet(false, true)) return
        disconnectCallback = null
        delegationListener = null
        sidebandAttachment.close()
        localAudio?.setEnabled(false)
        synchronized(remoteAudioTracks) {
            remoteAudioTracks.forEach { it.setEnabled(false) }
            remoteAudioTracks.clear()
        }
        iceGatheringComplete.cancel()
        connectionReady.cancel()
        eventsChannel?.close()
        eventsChannel?.unregisterObserver()
        eventsChannel?.dispose()
        peer?.close()
        peer?.dispose()
        localAudio?.dispose()
        source?.dispose()
        factory?.dispose()
        audioDeviceModule?.release()
        peer = null
        eventsChannel = null
        localAudio = null
        source = null
        factory = null
        audioDeviceModule = null
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
            RealtimeVoiceRuntimeDiagnostics.peerState(state?.name ?: "UNKNOWN")
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> connectionReady.complete(Unit)
                PeerConnection.PeerConnectionState.FAILED -> {
                    val failure = RealtimeVoiceException(RealtimeVoiceDiagnostic.NETWORK_FAILED, "Live Voice lost its WebRTC connection.")
                    connectionReady.completeExceptionally(failure)
                    fatalDisconnect(RealtimeNativeTransportEvent.WEBRTC_PEER_FAILED, failure)
                }
                PeerConnection.PeerConnectionState.CLOSED -> fatalDisconnect(
                    RealtimeNativeTransportEvent.WEBRTC_PEER_CLOSED,
                    RealtimeVoiceException(RealtimeVoiceDiagnostic.REMOTE_CLOSED, "The remote Live Voice session ended."),
                )
                else -> Unit
            }
        }
    }

    private fun eventsObserver(channel: DataChannel) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() {
            val state = channel.state()
            RealtimeVoiceRuntimeDiagnostics.dataChannelState(state.name)
            if (state == DataChannel.State.CLOSED && !closed.get()) {
                // oai-events is advertised for SDP compatibility; V2 control uses the sideband.
                // Its closure is not authoritative evidence that the audio PeerConnection died.
                nativeEvents.accept(RealtimeNativeTransportEvent.EVENTS_DATACHANNEL_CLOSED)
                Log.w(TAG, "Auxiliary oai-events data channel closed while Voice transport remains active")
            }
        }

        // Session events can contain transcripts. V1 intentionally consumes and discards them.
        override fun onMessage(buffer: DataChannel.Buffer) = Unit
    }

    private fun fatalDisconnect(event: RealtimeNativeTransportEvent, failure: RealtimeVoiceException) {
        if (!closed.get() && nativeEvents.accept(event) != null) {
            disconnectCallback?.invoke(failure)
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
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(VoiceAudioPolicy.playbackAttributes).build()
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw RealtimeVoiceException(RealtimeVoiceDiagnostic.AUDIO_PLAYBACK_FAILURE, "Audio focus is unavailable for Live Voice.")
        }
        focusRequest = request
        val playbackPlan = VoiceAudioPolicy.builtInAssistantPlaybackPlan()
        audioManager.mode = playbackPlan.audioMode
    }

    private fun releaseAudioRoute() {
        focusRequest?.let(audioManager::abandonAudioFocusRequest)
        focusRequest = null
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
        const val REALTIME_EVENTS_CHANNEL = "oai-events"
    }
}
