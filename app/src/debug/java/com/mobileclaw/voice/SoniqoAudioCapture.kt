package com.mobileclaw.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal interface FloatPcmCapture {
    fun start(consumer: (FloatArray) -> Unit): Result<CaptureStart>
    fun stop()
    fun release()
}
internal data class CaptureStart(val acousticEchoCancelerEnabled: Boolean)

/** Metten-owned 16 kHz mono float capture seam; Soniqo never owns Android lifecycle state. */
internal class SoniqoAudioRecordCapture(
    context: Context,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
) : FloatPcmCapture {
    private val duplex = AndroidDuplexAudio(context)
    private val running = AtomicBoolean()
    @Volatile private var record: AudioRecord? = null
    @Volatile private var echoCanceler: AcousticEchoCanceler? = null

    override fun start(consumer: (FloatArray) -> Unit): Result<CaptureStart> = runCatching {
        if (!running.compareAndSet(false, true)) return@runCatching CaptureStart(echoCanceler?.enabled == true)
        duplex.acquire()
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        check(minimum > 0) { "16 kHz float microphone capture is unavailable." }
        val next = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
            .setBufferSizeInBytes(maxOf(minimum, FRAME_SAMPLES * Float.SIZE_BYTES * 4))
            .build()
        check(next.state == AudioRecord.STATE_INITIALIZED) { "Microphone capture could not initialize." }
        record = next
        val aec = if (AcousticEchoCanceler.isAvailable()) runCatching {
            AcousticEchoCanceler.create(next.audioSessionId)?.also { it.enabled = true }
        }.getOrNull() else null
        echoCanceler = aec
        next.startRecording()
        worker.execute {
            val framer = SoniqoPcmFramer(consumer)
            val frame = FloatArray(FRAME_SAMPLES)
            while (running.get() && record === next) {
                val count = try { next.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING) }
                catch (_: IllegalStateException) { if (running.get()) running.set(false); break }
                if (count > 0) framer.accept(frame.copyOf(count))
                else if (count < 0 && running.get()) { running.set(false); break }
            }
        }
        VoiceDiagnostics.event("CAPTURE_CONFIGURED", "source=${next.audioSource} session=${next.audioSessionId} rate=${next.sampleRate} frameSamples=$FRAME_SAMPLES aec=${aec?.enabled == true} control=${aec?.hasControl() == true}")
        CaptureStart(aec?.enabled == true)
    }.onFailure { stop() }

    override fun stop() {
        running.set(false)
        echoCanceler?.let { runCatching { it.release() } }
        echoCanceler = null
        record?.let { value ->
            record = null
            runCatching { value.stop() }
            value.release()
        }
    }

    override fun release() { stop(); duplex.release(); worker.shutdownNow() }

    private companion object { const val SAMPLE_RATE = 16_000; const val FRAME_SAMPLES = 320 }
}
