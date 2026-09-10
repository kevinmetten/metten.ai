package com.mobileclaw.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

internal data class PcmDrainToken(
    val track: Any,
    val identity: PlaybackIdentity,
    val playerGeneration: Long,
    val finalTotalFrames: Long,
    val drainSequence: Long,
)

/** Pure exact-frame state used by the Android adapter and deterministic JVM tests. */
internal class ExactPcmDrainState {
    var token: PcmDrainToken? = null
        private set
    var allFinalFramesSubmitted = false
        private set
    private var drained = false

    fun arm(value: PcmDrainToken) {
        require(value.finalTotalFrames in 1..Int.MAX_VALUE.toLong())
        token = value
        allFinalFramesSubmitted = false
        drained = false
    }
    fun submitted(value: PcmDrainToken): Boolean = matches(value).also { if (it) allFinalFramesSubmitted = true }
    fun check(value: PcmDrainToken, signedPlaybackHead: Int): Boolean {
        if (!matches(value) || !allFinalFramesSubmitted || drained) return false
        val unsignedHead = signedPlaybackHead.toLong() and 0xFFFF_FFFFL
        return (unsignedHead >= value.finalTotalFrames).also { if (it) drained = true }
    }
    fun invalidate() { token = null; allFinalFramesSubmitted = false; drained = false }
    fun matches(value: PcmDrainToken) = token == value && token?.track === value.track
}

/** Pure no-progress deadline state used by the Android adapter and deterministic JVM tests. */
internal class PcmWriteProgressState(private val stallTimeoutMillis: Long) {
    private var token: PcmDrainToken? = null
    private var deadlineMillis = 0L
    private var complete = false

    fun arm(value: PcmDrainToken, nowMillis: Long) {
        token = value
        deadlineMillis = nowMillis + stallTimeoutMillis
        complete = false
    }

    fun wrote(value: PcmDrainToken, written: Int, nowMillis: Long): Boolean {
        if (!matches(value) || complete || written <= 0) return false
        deadlineMillis = nowMillis + stallTimeoutMillis
        return true
    }

    fun stalled(value: PcmDrainToken, nowMillis: Long) = matches(value) && !complete && nowMillis >= deadlineMillis
    fun submitted(value: PcmDrainToken): Boolean = matches(value).also { if (it) complete = true }
    fun invalidate() { token = null; complete = false }
    fun matches(value: PcmDrainToken) = token == value && token?.track === value.track
}

/** Per-utterance AudioTrack player with authoritative playback-head drain accounting. */
class AndroidAudioTrackPcmPlayer : PcmSpeechPlayer {
    private val thread = HandlerThread("MettenPcmPlayback").apply { start() }
    private val handler = Handler(thread.looper)
    private val generation = AtomicLong()
    private var current: Session? = null
    private var released = false

    private data class Session(
        val identity: PlaybackIdentity,
        val generation: Long,
        val track: AudioTrack,
        val samples: FloatArray,
        val listener: (PcmPlaybackEvent) -> Unit,
        val drain: ExactPcmDrainState,
        val writeProgress: PcmWriteProgressState,
        val token: PcmDrainToken,
        var offset: Int = 0,
        var started: Boolean = false,
        var terminal: Boolean = false,
        val deadlineMillis: Long,
    )

    override fun play(identity: PlaybackIdentity, speech: SynthesizedSpeech, listener: (PcmPlaybackEvent) -> Unit) {
        handler.post {
            if (released) { listener(PcmPlaybackEvent.Failed("PCM playback was released.")); return@post }
            cancelCurrent()
            val format = speech.format
            val minBuffer = try {
                AudioTrack.getMinBufferSize(format.sampleRateHz, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            } catch (failure: RuntimeException) {
                listener(PcmPlaybackEvent.Failed(failure.message ?: "PCM playback format could not initialize.")); return@post
            }
            if (minBuffer <= 0) { listener(PcmPlaybackEvent.Failed("PCM playback format is unavailable.")); return@post }
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(format.sampleRateHz).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                    .setBufferSizeInBytes(max(minBuffer, WRITE_FRAMES * 4 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (failure: RuntimeException) {
                listener(PcmPlaybackEvent.Failed(failure.message ?: "PCM playback could not initialize.")); return@post
            }
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release(); listener(PcmPlaybackEvent.Failed("PCM playback could not initialize.")); return@post
            }
            val playerGeneration = generation.incrementAndGet()
            val total = speech.samples.size.toLong()
            if (total !in 1..Int.MAX_VALUE.toLong()) {
                discard(track); listener(PcmPlaybackEvent.Failed("PCM playback has no playable audio.")); return@post
            }
            val drain = ExactPcmDrainState()
            val token = PcmDrainToken(track, identity, playerGeneration, total, playerGeneration)
            drain.arm(token) // Complete-waveform total is immutable before any frame is playable.
            val writeProgress = PcmWriteProgressState(WRITE_STALL_TIMEOUT_MILLIS)
            writeProgress.arm(token, android.os.SystemClock.uptimeMillis())
            val duration = total * 1_000L / format.sampleRateHz
            val session = Session(identity, playerGeneration, track, speech.samples, listener, drain, writeProgress, token,
                deadlineMillis = android.os.SystemClock.uptimeMillis() + duration + DRAIN_MARGIN_MILLIS)
            current = session
            val markerResult = try {
                track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(audioTrack: AudioTrack) { handler.post { checkDrain(session, audioTrack) } }
                    override fun onPeriodicNotification(audioTrack: AudioTrack) = Unit
                }, handler)
                track.setNotificationMarkerPosition(total.toInt())
            } catch (failure: RuntimeException) {
                fail(session, failure.message ?: "PCM drain tracking could not initialize."); return@post
            }
            if (markerResult != AudioTrack.SUCCESS) {
                fail(session, "PCM drain tracking could not initialize."); return@post
            }
            writeMore(session)
        }
    }

    private fun writeMore(session: Session) {
        if (!isCurrent(session)) return
        val count = minOf(WRITE_FRAMES, session.samples.size - session.offset)
        if (count > 0) {
            val now = android.os.SystemClock.uptimeMillis()
            val written = try {
                session.track.write(session.samples, session.offset, count, AudioTrack.WRITE_NON_BLOCKING)
            } catch (failure: RuntimeException) {
                fail(session, failure.message ?: "PCM playback write failed."); return
            }
            if (written < 0) { fail(session, "PCM playback write failed ($written)."); return }
            if (written == 0 && session.writeProgress.stalled(session.token, now)) {
                fail(session, "PCM playback stopped accepting audio."); return
            }
            session.writeProgress.wrote(session.token, written, now)
            session.offset += written
            if (written > 0 && !session.started) {
                try { session.track.play(); session.started = true; session.listener(PcmPlaybackEvent.Started) }
                catch (failure: RuntimeException) { fail(session, failure.message ?: "PCM playback could not start."); return }
            }
        }
        if (session.offset == session.samples.size) {
            session.writeProgress.submitted(session.token)
            session.drain.submitted(session.token)
            checkDrain(session, session.track)
        } else handler.postDelayed({ writeMore(session) }, WRITE_RETRY_MILLIS)
    }

    private fun checkDrain(session: Session, callbackTrack: AudioTrack) {
        if (!isCurrent(session) || callbackTrack !== session.track || !session.drain.matches(session.token)) return
        val head = try { session.track.playbackHeadPosition } catch (failure: RuntimeException) {
            fail(session, failure.message ?: "PCM playback position failed."); return
        }
        if (session.drain.check(session.token, head)) {
            session.terminal = true
            current = null
            cleanupNaturally(session.track)
            session.listener(PcmPlaybackEvent.Drained)
        } else if (android.os.SystemClock.uptimeMillis() >= session.deadlineMillis) {
            fail(session, "PCM playback did not drain before its deadline.")
        } else handler.postDelayed({ checkDrain(session, session.track) }, HEAD_CHECK_MILLIS)
    }

    override fun cancel(identity: PlaybackIdentity?) {
        handler.post {
            val value = current ?: return@post
            if (identity == null || value.identity == identity) cancelCurrent()
        }
    }

    override fun release() {
        handler.post {
            if (released) return@post
            released = true
            cancelCurrent()
            thread.quitSafely()
        }
    }

    private fun isCurrent(value: Session) = !released && current === value && !value.terminal
    private fun fail(value: Session, reason: String) {
        if (!isCurrent(value)) return
        value.terminal = true; current = null; value.drain.invalidate(); value.writeProgress.invalidate(); discard(value.track)
        value.listener(PcmPlaybackEvent.Failed(reason))
    }
    private fun cancelCurrent() {
        val value = current ?: return
        current = null; value.terminal = true; value.drain.invalidate(); value.writeProgress.invalidate(); discard(value.track)
    }
    private fun discard(track: AudioTrack) {
        try { if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause() } catch (_: IllegalStateException) {}
        try { track.flush() } catch (_: IllegalStateException) {}
        try { track.setPlaybackPositionUpdateListener(null) } catch (_: RuntimeException) {}
        try { track.release() } catch (_: RuntimeException) {}
    }
    private fun cleanupNaturally(track: AudioTrack) {
        try { track.setPlaybackPositionUpdateListener(null) } catch (_: RuntimeException) {}
        try { track.release() } catch (_: RuntimeException) {}
    }

    private companion object {
        const val WRITE_FRAMES = 1_024
        const val WRITE_RETRY_MILLIS = 5L
        const val WRITE_STALL_TIMEOUT_MILLIS = 3_000L
        const val HEAD_CHECK_MILLIS = 75L
        const val DRAIN_MARGIN_MILLIS = 5_000L
    }
}
