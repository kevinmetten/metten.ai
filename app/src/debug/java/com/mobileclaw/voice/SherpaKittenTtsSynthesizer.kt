package com.mobileclaw.voice

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig

/** Debug-only sherpa adapter. Phase 1 intentionally uses complete-waveform generation. */
class SherpaKittenTtsSynthesizer(private val assets: AssetManager) : LocalTtsSynthesizer {
    @Volatile private var cancelled = false
    private var tts: OfflineTts? = null

    override fun initialize(): Result<Unit> {
        var candidate: OfflineTts? = null
        val result = runCatching {
            check(tts == null) { "Neural speech is already initialized." }
            val kitten = OfflineTtsKittenModelConfig(
                model = KittenTtsModelSpec.MODEL,
                voices = KittenTtsModelSpec.VOICES,
                tokens = KittenTtsModelSpec.TOKENS,
                dataDir = KittenTtsModelSpec.DATA_DIR,
            )
            candidate = OfflineTts(
                assetManager = assets,
                config = OfflineTtsConfig(model = OfflineTtsModelConfig(kitten = kitten, numThreads = 2)),
            )
            val engine = checkNotNull(candidate)
            check(KittenTtsModelSpec.SPEAKER_ID >= 0 && KittenTtsModelSpec.SPEAKER_ID < engine.numSpeakers()) {
                "The configured Kitten voice is unavailable."
            }
            tts = engine
            candidate = null
        }
        if (result.isFailure) runCatching { candidate?.release() }
        return result
    }

    override fun synthesize(text: String): Result<SynthesizedSpeech> = runCatching {
        cancelled = false
        val engine = checkNotNull(tts) { "Neural speech is not initialized." }
        val audio = engine.generate(text, sid = KittenTtsModelSpec.SPEAKER_ID, speed = 1.0f)
        check(!cancelled) { "Neural speech synthesis was cancelled." }
        SynthesizedSpeech(PcmFormat(audio.sampleRate, 1), audio.samples.copyOf())
    }

    override fun cancel() { cancelled = true }
    override fun release() { tts?.release(); tts = null }
}
