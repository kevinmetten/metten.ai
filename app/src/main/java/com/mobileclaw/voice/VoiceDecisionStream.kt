package com.mobileclaw.voice

import com.google.gson.JsonParser

/** Streams only a committed conversation envelope. Phone decisions always wait for full validation. */
internal class VoiceDecisionStream(private val emit: (String) -> Unit) {
    private val raw = StringBuilder()
    private var offset = -1
    private val decoded = StringBuilder()
    private val phrase = VoicePhraseAccumulator(emit)
    private var closed = false

    fun accept(delta: String) {
        raw.append(delta)
        if (offset < 0) {
            val prefix = PREFIX.find(raw) ?: return
            offset = prefix.range.last + 1
        }
        while (!closed && offset < raw.length) {
            val c = raw[offset]
            if (c == '"') { closed = true; offset++; break }
            val size = if (c != '\\') 1 else {
                if (offset + 1 >= raw.length) return
                if (raw[offset + 1] == 'u') 6 else 2
            }
            if (offset + size > raw.length) return
            val piece = if (c == '\\') {
                try { JsonParser.parseString("\"${raw.substring(offset, offset + size)}\"").asString }
                catch (failure: Exception) { throw VoiceTurnProcessingException.InvalidDecision("Invalid streamed Voice escape.", failure) }
            } else {
                if (c < ' ') throw VoiceTurnProcessingException.InvalidDecision("Invalid streamed Voice character.")
                c.toString()
            }
            decoded.append(piece)
            phrase.accept(piece)
            offset += size
        }
        // Do not flush an unfinished phrase until the complete decision passes validation.
    }

    fun finish(decision: VoiceTurnDecision) {
        if (offset < 0) return // Valid differently ordered JSON uses the whole-result compatibility path.
        if (!closed || raw.substring(offset).trim() != "}" || decision.phoneCommand != null ||
            decision.spokenText != decoded.toString().trim())
            throw VoiceTurnProcessingException.InvalidDecision("The streamed Voice decision changed after speech began.")
        phrase.finish()
    }

    private companion object {
        val PREFIX = Regex("""^\s*\{\s*"action"\s*:\s*"conversation"\s*,\s*"goal"\s*:\s*null\s*,\s*"spoken_text"\s*:\s*"""" )
    }
}

/** Sentence boundaries, then bounded whitespace phrase boundaries; never arbitrary token fragments. */
internal class VoicePhraseAccumulator(private val emit: (String) -> Unit) {
    private val pending = StringBuilder()
    fun accept(text: String) {
        pending.append(text)
        while (true) {
            val sentence = Regex("[.!?][\\\"'’”)]*\\s+").find(pending)
            val boundary = sentence?.range?.last?.plus(1)?.takeIf { it <= MAX_CHARS }
                ?: if (pending.length >= MAX_CHARS) (MAX_CHARS downTo 1).firstOrNull { pending[it - 1].isWhitespace() } else null
            if (boundary == null) {
                // Refuse unbounded tokens rather than synthesize an arbitrary fragment.
                if (pending.length > MAX_UNBROKEN) throw VoiceTurnProcessingException.InvalidDecision("Voice text has no safe phrase boundary.")
                return
            }
            emitPrefix(boundary)
        }
    }
    fun finish() { if (pending.isNotEmpty()) emitPrefix(pending.length) }
    private fun emitPrefix(end: Int) {
        val text = pending.substring(0, end)
        pending.delete(0, end)
        if (text.isNotBlank()) emit(text)
    }
    private companion object { const val MAX_CHARS = 240; const val MAX_UNBROKEN = 1000 }
}
