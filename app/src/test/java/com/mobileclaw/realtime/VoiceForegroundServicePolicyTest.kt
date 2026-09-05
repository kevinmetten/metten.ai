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

    @Test fun `service is lifecycle anchor and does not construct ownership graph`() {
        val source = projectFile("src/main/java/com/mobileclaw/realtime/VoiceSessionForegroundService.kt").readText()
        listOf(
            "ChatGptRealtimeSessionController(", "AndroidWebRtcVoiceTransport(", "VoiceAgentCoordinator(",
            "AgentRuntime(", "AgentTaskController(",
        ).forEach { forbidden -> assertFalse("service must not construct $forbidden", source.contains(forbidden)) }
        assertTrue(source.contains("RealtimeVoicePhase.IDLE") && source.contains("RealtimeVoicePhase.FAILED"))
        assertFalse(source.contains("VoicePhoneTaskState"))
    }

    @Test fun `application enters connecting before foreground service starts and End stops it`() {
        val source = projectFile("src/main/java/com/mobileclaw/ClawApplication.kt").readText()
        val startBody = source.substringAfter("fun startLiveVoice()").substringBefore("fun endLiveVoice()")
        assertTrue(startBody.indexOf("realtimeVoiceController.start()") < startBody.indexOf("VoiceSessionForegroundService.start(this)"))
        val endBody = source.substringAfter("fun endLiveVoice()").substringBefore("fun providerReadiness")
        assertTrue(endBody.contains("VoiceSessionForegroundService.stop(this)"))
    }

    @Test fun `voice and agent foreground services use independent notifications and stop ownership`() {
        val voice = projectFile("src/main/java/com/mobileclaw/realtime/VoiceSessionForegroundService.kt").readText()
        val agent = projectFile("src/main/java/com/mobileclaw/agent/AgentExecutionForegroundService.kt").readText()
        assertTrue(voice.contains("ID = 7302"))
        assertTrue(agent.contains("NOTIFICATION_ID = 7301"))
        assertFalse(voice.contains("AgentExecutionForegroundService"))
        assertFalse(agent.contains("VoiceSessionForegroundService"))
    }

    private fun projectFile(relative: String): File = sequenceOf(
        File(relative), File("app/$relative"), File(System.getProperty("user.dir"), relative), File(System.getProperty("user.dir"), "app/$relative"),
    ).first { it.isFile }
}
