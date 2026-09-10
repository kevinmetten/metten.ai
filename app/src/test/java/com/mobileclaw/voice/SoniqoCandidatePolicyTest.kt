package com.mobileclaw.voice

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SoniqoCandidatePolicyTest {
    @Test fun `debug candidate is pinned and cannot silently select legacy speech`() {
        val build = projectFile("build.gradle.kts").readText()
        val factory = projectFile("src/debug/java/com/mobileclaw/voice/MettenSpeechEngineFactory.kt").readText()
        assertTrue(build.contains("debugImplementation(\"audio.soniqo:speech:0.0.21\")"))
        assertTrue(factory.contains("SoniqoConversationalSpeechSession"))
        assertFalse(factory.contains("AndroidOnDeviceSpeechInput"))
        assertFalse(factory.contains("MettenSpeechOutputFactory.create"))
    }

    @Test fun `candidate configuration is conservative local transcribe only`() {
        val source = projectFile("src/debug/java/com/mobileclaw/voice/SoniqoConversationalSpeechSession.kt").readText()
        listOf(
            "SttModel.PARAKEET_EOU", "TtsModel.POCKET", "PipelineMode.TRANSCRIBE_ONLY",
            "ModelPrecision.INT8", "partialTranscriptionInterval = 0.5f",
            "endOfSpeechSilenceSec = 0.8f", "beamSize = 4", "enableSmartTurn = false",
            "useNnapi = false", "Speech engine: SONIQO; STT: PARAKEET_EOU; TTS: POCKET",
        ).forEach { assertTrue("Missing $it", source.contains(it)) }
        assertFalse(source.contains("FunctionGemma"))
    }

    @Test fun `model licenses and fixed interruption limitation are recorded`() {
        val notice = projectFile("../docs/metten-voice-soniqo-provenance.md").readText()
        assertTrue(notice.contains("Parakeet-EOU-120M-ONNX-INT8`: CC-BY-4.0"))
        assertTrue(notice.contains("Pocket-TTS-100M-ONNX-INT8`: CC-BY-4.0"))
        assertTrue(notice.contains("fixed 1.0 second minimum"))
        assertTrue(notice.contains("not claimed solved", ignoreCase = true))
    }

    @Test fun `models are provisioned before the returned directory configures pipeline`() {
        val source = projectFile("src/debug/java/com/mobileclaw/voice/SoniqoConversationalSpeechSession.kt").readText()
        val provision = source.indexOf("ModelManager.ensureModels(")
        val construct = source.indexOf("SpeechPipeline(SpeechConfig(")
        val configure = source.indexOf("modelDir = modelDir", construct)
        assertTrue(provision >= 0 && provision < construct && construct < configure)
        assertTrue(source.contains("modelDir = modelDir"))
        assertFalse(source.contains("File(app.filesDir"))
        assertFalse(source.contains("SpeechEvent.ResponseInterrupted"))
        assertFalse(source.contains("SpeechEvent.ResponseAudioDelta"))
        assertFalse(source.contains("SpeechEvent.ResponseDone"))
    }

    private fun projectFile(relative: String): File = sequenceOf(
        File(relative), File("app/$relative"), File(System.getProperty("user.dir"), relative),
        File(System.getProperty("user.dir"), "app/$relative"),
    ).first { it.isFile }
}
