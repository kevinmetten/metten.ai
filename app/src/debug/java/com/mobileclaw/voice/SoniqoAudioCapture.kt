package com.mobileclaw.voice

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
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
) : FloatPcmCapture {
    private val running = AtomicBoolean()
    @Volatile private var record: AudioRecord? = null
    @Volatile private var echoCanceler: AcousticEchoCanceler? = null

    override fun start(consumer: (FloatArray) -> Unit): Result<CaptureStart> = runCatching {
        if (!running.compareAndSet(false, true)) return@runCatching CaptureStart(echoCanceler?.enabled == true)
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        check(minimum > 0) { "16 kHz float microphone capture is unavailable." }
        val next = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
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
            val frame = FloatArray(FRAME_SAMPLES)
            while (running.get() && record === next) {
                val count = try { next.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING) }
                catch (_: IllegalStateException) { if (running.get()) running.set(false); break }
                if (count > 0) consumer(frame.copyOf(count))
                else if (count < 0 && running.get()) { running.set(false); break }
            }
        }
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

    override fun release() { stop(); worker.shutdownNow() }

    private companion object { const val SAMPLE_RATE = 16_000; const val FRAME_SAMPLES = 1_600 }
}
