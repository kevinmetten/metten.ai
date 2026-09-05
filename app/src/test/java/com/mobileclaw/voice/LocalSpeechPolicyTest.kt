package com.mobileclaw.voice

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LocalSpeechPolicyTest {
    @Test fun `input uses only explicit API 31 on-device recognizer`() {
        val source = projectFile("src/main/java/com/mobileclaw/voice/AndroidOnDeviceSpeechInput.kt").readText()
        assertTrue(source.contains("isOnDeviceRecognitionAvailable"))
        assertTrue(source.contains("createOnDeviceSpeechRecognizer"))
        assertFalse(Regex("SpeechRecognizer\\.createSpeechRecognizer\\s*\\(").containsMatchIn(source))
        assertTrue(source.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.S"))
    }

    @Test fun `output excludes network-required voices and owns shutdown`() {
        val source = projectFile("src/main/java/com/mobileclaw/voice/AndroidOfflineTextToSpeechOutput.kt").readText()
        assertTrue(source.contains("filterNot { it.isNetworkConnectionRequired }"))
        assertTrue(source.contains("tts?.shutdown()"))
    }

    @Test fun `manifest exposes recognition and TTS service queries`() {
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()
        val queries = manifest.substringAfter("<queries>").substringBefore("</queries>")
        assertTrue(queries.contains("android.speech.RecognitionService"))
        assertTrue(queries.contains("android.intent.action.TTS_SERVICE"))
    }

    private fun projectFile(relative: String): File = sequenceOf(File(relative), File("app/$relative"), File(System.getProperty("user.dir"), relative), File(System.getProperty("user.dir"), "app/$relative")).first { it.isFile }
}
