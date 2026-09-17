package com.mobileclaw.voice

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.mobileclaw.BuildConfig

private val debugTrace = BoundedVoiceTrace(300)
private var previousProcess = "previousProcessExit=unavailable"

internal fun installVoiceDiagnostics(context: Context) {
    val app = context.applicationContext
    val prefs = app.getSharedPreferences("voice_crash_diagnostics_v1", Context.MODE_PRIVATE)
    val priorVoice = prefs.getString("current_voice", null)
    previousProcess = buildPreviousExit(app, priorVoice, prefs.getString("java_crash", null))
    val record = PersistentVoiceRecord()
    val priorHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
        val frames = failure.stackTrace.asSequence().filter { it.className.startsWith("com.mobileclaw.") }
            .take(8).joinToString(",") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }.take(900)
        prefs.edit().putString("java_crash", "type=${failure.javaClass.name.take(160)} thread=${thread.name.take(80)} appFrames=$frames").commit()
        priorHandler?.uncaughtException(thread, failure)
    }
    VoiceDiagnostics.sink = VoiceTraceSink { event, metadata ->
        debugTrace.record(event, metadata)
        if (event == "VOICE_SESSION_STARTED") {
            record.begin(VoiceDiagnostics.generation, VoiceDiagnostics.aecEnabled, VoiceDiagnostics.thresholdMillis)
            prefs.edit().remove("java_crash").putString("current_voice", record.encode()).commit()
        } else if (event in PersistentVoiceRecord.durableEvents) {
            record.add(event, metadata)
            val encoded = record.encode()
            prefs.edit().putString("current_voice", encoded).commit()
            if (Build.VERSION.SDK_INT >= 30) (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .setProcessStateSummary("voice:${record.phase}:g${record.generation}".toByteArray().take(120).toByteArray())
        }
    }
}

private fun buildPreviousExit(context: Context, voice: String?, javaCrash: String?): String {
    val exit = if (Build.VERSION.SDK_INT >= 30) {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
    } else null
    val exitText = if (exit == null) "previousProcessExit=reason_unavailable api=${Build.VERSION.SDK_INT}" else {
        val kind = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
            ApplicationExitInfo.REASON_CRASH -> "java_or_runtime_crash"
            ApplicationExitInfo.REASON_ANR -> "anr"
            else -> "reason_${exit.reason}"
        }
        listOf("previousProcessExit=$kind", "signalOrStatus=${exit.status}", "timestamp=${exit.timestamp}",
            "pssKb=${exit.pss}", "rssKb=${exit.rss}", "importance=${exit.importance}",
            "description=${sanitizeSystem(exit.description)}", "processStateSummary=${sanitizeSystem(exit.processStateSummary?.toString(Charsets.UTF_8))}").joinToString("\n")
    }
    return listOf("--- Previous process / last Voice crash ---", exitText,
        javaCrash?.let { "javaCrash=$it" }, voice?.let { "lastVoiceSession:\n$it" } ?: "lastVoiceSession=unavailable")
        .filterNotNull().joinToString("\n").take(8_000)
}

private fun sanitizeSystem(value: String?): String = value.orEmpty().replace(Regex("[^A-Za-z0-9_.=:/, -]"), "?").take(500)

fun copyableVoiceDiagnostics(): String = debugTrace.snapshot(listOf(
    "Metten Voice diagnostics app/debug build=${BuildConfig.GIT_VERSION}",
    "generation=${VoiceDiagnostics.generation}",
    "engine=SONIQO-0.0.20 mode=TRANSCRIBE_ONLY precision=INT8",
    "stt=PARAKEET_EOU", "tts=POCKET",
    "aec=${if (VoiceDiagnostics.aecEnabled) "enabled" else "disabled"}",
    "interruptionThresholdMs=${VoiceDiagnostics.thresholdMillis}", previousProcess,
))
