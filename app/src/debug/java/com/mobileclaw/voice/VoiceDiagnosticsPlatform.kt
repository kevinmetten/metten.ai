package com.mobileclaw.voice

import com.mobileclaw.BuildConfig

private val debugTrace = BoundedVoiceTrace(300)

internal fun installVoiceDiagnostics() { VoiceDiagnostics.sink = debugTrace }

fun copyableVoiceDiagnostics(): String = debugTrace.snapshot(listOf(
    "Metten Voice diagnostics app/debug build=${BuildConfig.GIT_VERSION}",
    "generation=${VoiceDiagnostics.generation}",
    "engine=SONIQO-0.0.20 mode=TRANSCRIBE_ONLY precision=INT8",
    "stt=PARAKEET_EOU",
    "tts=POCKET",
    "aec=${if (VoiceDiagnostics.aecEnabled) "enabled" else "disabled"}",
    "interruptionThresholdMs=${VoiceDiagnostics.thresholdMillis}",
))
