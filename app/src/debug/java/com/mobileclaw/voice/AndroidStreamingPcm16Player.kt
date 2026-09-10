package com.mobileclaw.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** Synchronous, naturally backpressured writes; atomic cancellation preempts all later chunks. */
internal class AndroidStreamingPcm16Player : StreamingPcm16Player {
    private val thread = HandlerThread("MettenPocketDrain").apply { start() }
    private val handler = Handler(thread.looper)
    private val ownership = ExactPlaybackOwnership<Session> { it.identity }
    private val released = AtomicBoolean()
    private data class Session(
        val identity: PlaybackIdentity, val sampleRate: Int, val listener: (StreamingPlaybackEvent) -> Unit,
        val drain: StreamingPcmDrainState = StreamingPcmDrainState(identity, sampleRate),
        @Volatile var track: AudioTrack? = null, var started: Boolean = false, var terminal: Boolean = false,
    )

    override fun start(identity: PlaybackIdentity, sampleRateHz: Int, listener: (StreamingPlaybackEvent) -> Unit) {
        if (released.get()) { listener(StreamingPlaybackEvent.Failed("PCM playback was released.")); return }
        ownership.replace(Session(identity, sampleRateHz, listener))?.let(::discard)
    }

    override fun append(identity: PlaybackIdentity, pcm16: ByteArray) {
        if (pcm16.isEmpty()) return
        val value = ownership.current(identity) ?: return
        synchronized(value) {
            if (!ownership.isCurrent(value) || value.terminal || value.drain.final) return
            if (pcm16.size % 2 != 0) return fail(value, "Pocket returned an unaligned PCM16 chunk.")
            val track = value.track ?: createTrack(value).getOrElse { return fail(value, it.message ?: "PCM playback failed.") }.also { value.track = it }
            // Blocking here backpressures Pocket; chunks cannot accumulate in a Handler queue.
            val written = try { track.write(pcm16, 0, pcm16.size, AudioTrack.WRITE_BLOCKING) }
            catch (failure: RuntimeException) { return fail(value, failure.message ?: "PCM playback write failed.") }
            if (!ownership.isCurrent(value)) return // exact cancellation won while the bounded write completed
            if (written != pcm16.size) return fail(value, "PCM playback accepted $written of ${pcm16.size} bytes.")
            value.drain.wrote(written)
            if (!value.started) {
                try { track.play() } catch (failure: RuntimeException) { return fail(value, failure.message ?: "PCM playback could not start.") }
                value.started = true; value.listener(StreamingPlaybackEvent.Started)
            }
        }
    }

    override fun finish(identity: PlaybackIdentity) {
        val value = ownership.current(identity) ?: return
        synchronized(value) {
            if (!ownership.isCurrent(value) || value.terminal) return
            if (!value.started || value.drain.framesWritten == 0L) return fail(value, "Pocket TTS produced no playable PCM.")
            value.drain.finish(SystemClock.uptimeMillis())
        }
        handler.post { checkDrain(value) }
    }

    private fun checkDrain(value: Session) {
        if (!ownership.isCurrent(value) || value.terminal) return
        val head = try { value.track!!.playbackHeadPosition.toLong() and 0xffff_ffffL }
        catch (failure: RuntimeException) { return fail(value, failure.message ?: "PCM playback position failed.") }
        when (value.drain.check(value.identity, head, SystemClock.uptimeMillis())) {
            StreamingDrainOutcome.DRAINED -> terminal(value, StreamingPlaybackEvent.Drained)
            StreamingDrainOutcome.TIMED_OUT -> terminal(value, StreamingPlaybackEvent.Failed("PCM playback did not drain before its deadline."))
            StreamingDrainOutcome.WAITING -> handler.postDelayed({ checkDrain(value) }, DRAIN_POLL_MS)
            StreamingDrainOutcome.STALE -> Unit
        }
    }

    override fun cancel(identity: PlaybackIdentity?) {
        ownership.cancel(identity)?.let(::discard)
    }
    override fun release() { if (released.compareAndSet(false, true)) { cancel(); thread.quitSafely() } }
    private fun fail(value: Session, reason: String) = terminal(value, StreamingPlaybackEvent.Failed(reason))
    private fun terminal(value: Session, event: StreamingPlaybackEvent) {
        if (!ownership.complete(value)) return
        synchronized(value) { if (value.terminal) return; value.terminal = true }
        discard(value); value.listener(event)
    }
    private fun discard(value: Session) {
        synchronized(value) { value.terminal = true; value.track?.let { runCatching { it.pause() }; runCatching { it.flush() }; runCatching { it.release() } }; value.track = null }
    }
    private fun createTrack(value: Session) = runCatching {
        val minimum = AudioTrack.getMinBufferSize(value.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "PCM16 output is unavailable." }
        AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(value.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(max(minimum, PREFILL_BYTES)).setTransferMode(AudioTrack.MODE_STREAM).build()
            .also { check(it.state == AudioTrack.STATE_INITIALIZED) { "PCM16 output could not initialize." } }
    }
    private companion object { const val PREFILL_BYTES = 8_192; const val DRAIN_POLL_MS = 20L }
}
