package com.mobileclaw.voice
import android.content.Context

internal fun installVoiceDiagnostics(context: Context) { VoiceDiagnostics.sink = NoOpVoiceTrace }
internal fun copyableVoiceDiagnostics(): String = ""
