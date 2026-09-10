package com.mobileclaw.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.max

/** Pocket-compatible single-AudioTrack streaming player with playback-head drain proof. */
internal class AndroidStreamingPcm16Player : StreamingPcm16Player {
    private val thread = HandlerThread("MettenPocketPlayback").apply { start() }
    private val handler = Handler(thread.looper)
    private var current: Session? = null
    private var released = false
    private data class Session(
        val identity: PlaybackIdentity, val sampleRate: Int, val listener: (StreamingPlaybackEvent) -> Unit,
        val drain: StreamingPcmDrainState = StreamingPcmDrainState(identity),
        var track: AudioTrack? = null, var started: Boolean = false, var terminal: Boolean = false,
    )

    override fun start(identity: PlaybackIdentity, sampleRateHz: Int, listener: (StreamingPlaybackEvent) -> Unit) = handler.post {
        if (released) listener(StreamingPlaybackEvent.Failed("PCM playback was released."))
        else { cancelCurrent(); current = Session(identity, sampleRateHz, listener) }
    }.let { Unit }

    override fun append(identity: PlaybackIdentity, pcm16: ByteArray) {
        if (pcm16.isEmpty()) return
        handler.post {
            val value = current?.takeIf { it.identity == identity && !it.drain.final && !it.terminal } ?: return@post
            if (pcm16.size % 2 != 0) return@post fail(value, "Pocket returned an unaligned PCM16 chunk.")
            val track = value.track ?: createTrack(value).getOrElse { return@post fail(value, it.message ?: "PCM playback failed.") }.also { value.track = it }
            val written = try { track.write(pcm16, 0, pcm16.size, AudioTrack.WRITE_BLOCKING) } catch (failure: RuntimeException) {
                return@post fail(value, failure.message ?: "PCM playback write failed.")
            }
            if (written != pcm16.size) return@post fail(value, "PCM playback accepted $written of ${pcm16.size} bytes.")
            value.drain.wrote(written)
            if (!value.started) {
                try { track.play() } catch (failure: RuntimeException) { return@post fail(value, failure.message ?: "PCM playback could not start.") }
                value.started = true; value.listener(StreamingPlaybackEvent.Started)
            }
        }
    }

    override fun finish(identity: PlaybackIdentity) = handler.post {
        val value = current?.takeIf { it.identity == identity && !it.terminal } ?: return@post
        if (!value.started || value.drain.framesWritten == 0L) fail(value, "Pocket TTS produced no playable PCM.")
        else { value.drain.finish(); checkDrain(value) }
    }.let { Unit }

    private fun checkDrain(value: Session) {
        if (current !== value || value.terminal || !value.drain.final) return
        val head = try { value.track!!.playbackHeadPosition.toLong() and 0xffff_ffffL } catch (failure: RuntimeException) {
            return fail(value, failure.message ?: "PCM playback position failed.")
        }
        if (value.drain.check(head)) {
            value.terminal = true; current = null; discard(value.track); value.listener(StreamingPlaybackEvent.Drained)
        } else handler.postDelayed({ checkDrain(value) }, DRAIN_POLL_MS)
    }

    private fun createTrack(value: Session) = runCatching {
        val minimum = AudioTrack.getMinBufferSize(value.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "PCM16 output is unavailable." }
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(value.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(max(minimum, PREFILL_BYTES)).setTransferMode(AudioTrack.MODE_STREAM).build()
            .also { check(it.state == AudioTrack.STATE_INITIALIZED) { "PCM16 output could not initialize." } }
    }

    override fun cancel(identity: PlaybackIdentity?) = handler.post {
        current?.takeIf { identity == null || it.identity == identity }?.let { cancelCurrent() }
    }.let { Unit }
    override fun release() = handler.post { if (!released) { released = true; cancelCurrent(); thread.quitSafely() } }.let { Unit }
    private fun cancelCurrent() { current?.also { it.terminal = true; discard(it.track) }; current = null }
    private fun fail(value: Session, reason: String) { if (current !== value || value.terminal) return; value.terminal = true; current = null; discard(value.track); value.listener(StreamingPlaybackEvent.Failed(reason)) }
    private fun discard(track: AudioTrack?) { if (track == null) return; runCatching { track.pause() }; runCatching { track.flush() }; runCatching { track.release() } }
    private companion object { const val PREFILL_BYTES = 8_192; const val DRAIN_POLL_MS = 20L }
}
