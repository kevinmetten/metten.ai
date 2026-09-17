package com.mobileclaw.voice

internal fun installVoiceDiagnostics() { VoiceDiagnostics.sink = NoOpVoiceTrace }
internal fun copyableVoiceDiagnostics(): String = ""
