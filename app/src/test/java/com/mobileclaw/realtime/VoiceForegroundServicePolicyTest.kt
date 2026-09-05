package com.mobileclaw.realtime

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class VoiceForegroundServicePolicyTest {
    @Test fun `manifest declares microphone foreground service contract`() {
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE"))
        assertTrue(manifest.contains("android:name=\".realtime.VoiceSessionForegroundService\""))
        assertTrue(manifest.contains("android:foregroundServiceType=\"microphone\""))
    }

    @Test fun `service follows application-owned Metten lifecycle without constructing ownership graph`() {
        val source = projectFile("src/main/java/com/mobileclaw/realtime/VoiceSessionForegroundService.kt").readText()
        listOf("ChatGptRealtimeSessionController(", "AndroidWebRtcVoiceTransport(", "VoiceAgentCoordinator(", "AgentRuntime(", "AgentTaskController(")
            .forEach { assertFalse(source.contains(it)) }
        assertTrue(source.contains("mettenVoiceController.state"))
        assertTrue(source.contains("MettenVoicePhase.IDLE") && source.contains("MettenVoicePhase.FAILED"))
    }

    @Test fun `product Voice wiring cannot reach ChatGPT Realtime or WebRTC`() {
        val application = projectFile("src/main/java/com/mobileclaw/ClawApplication.kt").readText()
        val settings = projectFile("src/main/java/com/mobileclaw/ui/settings/SettingsPage.kt").readText()
        listOf("ChatGptRealtimeSessionController", "AndroidWebRtcVoiceTransport", "ChatGptRealtimeCallClient", "ChatGptRealtimeSidebandClient", "/v1/live")
            .forEach { forbidden -> assertFalse("product Voice wiring referenced $forbidden", forbidden in application || forbidden in settings) }
        assertTrue(application.contains("MettenVoiceSessionController("))
        assertTrue(application.contains("AndroidOnDeviceSpeechInput(this)"))
        assertTrue(application.contains("AndroidOfflineTextToSpeechOutput(this)"))
    }

    private fun projectFile(relative: String): File = sequenceOf(File(relative), File("app/$relative"), File(System.getProperty("user.dir"), relative), File(System.getProperty("user.dir"), "app/$relative")).first { it.isFile }
}
