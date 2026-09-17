package com.mobileclaw.voice

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class VoiceDecisionStreamTest {
    @Test fun `every split preserves escaped text and exact speech order before completion`() {
        val text = "Volcanoes form. Pressure rises! A quoted \"answer\", a slash \\, and 🌋 remain intact. Final tail"
        val raw = "{\"action\":\"conversation\",\"goal\":null,\"spoken_text\":" + Gson().toJson(text) + "}"
        for (size in 1..raw.length) {
            val chunks = mutableListOf<String>()
            val decoder = VoiceDecisionStream(chunks::add)
            raw.chunked(size).forEach(decoder::accept)
            assertTrue(chunks.isNotEmpty())
            assertFalse(chunks.joinToString("").endsWith("Final tail"))
            decoder.finish(VoiceTurnDecision(text))
            assertEquals(text, chunks.joinToString(""))
        }
    }
    @Test fun `phone envelope and differently ordered JSON never provisionally speak`() {
        listOf(
            """{"action":"start","goal":"Open Settings","spoken_text":"Done. "}""",
            """{"spoken_text":"Answer. ","action":"conversation","goal":null}""",
        ).forEach { raw ->
            val chunks = mutableListOf<String>()
            VoiceDecisionStream(chunks::add).accept(raw)
            assertTrue(chunks.isEmpty())
        }
    }
    @Test fun `revised envelope cannot release tail or become phone command`() {
        val chunks = mutableListOf<String>()
        val stream = VoiceDecisionStream(chunks::add)
        stream.accept("""{"action":"conversation","goal":null,"spoken_text":"First. Tail","action":"start"}""")
        assertThrows(VoiceTurnProcessingException.InvalidDecision::class.java) { stream.finish(VoiceTurnDecision("First. Tail")) }
        assertEquals(listOf("First. "), chunks)
    }
    @Test fun `long speech uses bounded phrases with no duplicates`() {
        val text = "A long explanatory phrase with natural spaces ".repeat(100)
        val chunks = mutableListOf<String>()
        val accumulator = VoicePhraseAccumulator(chunks::add)
        text.chunked(7).forEach(accumulator::accept); accumulator.finish()
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 240 })
    }
    @Test fun `queue cancels unsynthesized chunks and rejects stale producer`() {
        val q = PendingSpeechText()
        assertTrue(q.append("one")); assertFalse(q.append("two"))
        assertEquals(PendingSpeechText.Item.Text("one"), q.next())
        q.cancel(); assertFalse(q.append("old tail")); assertFalse(q.finish())
        assertEquals(PendingSpeechText.Item.Wait, q.next())
    }
    @Test fun `queue wakes after gaps and finishes exactly once`() {
        val q = PendingSpeechText()
        repeat(3) {
            assertTrue(q.append("chunk $it"))
            assertEquals(PendingSpeechText.Item.Text("chunk $it"), q.next())
            assertEquals(PendingSpeechText.Item.Wait, q.next())
        }
        assertTrue(q.finish()); assertEquals(PendingSpeechText.Item.End, q.next())
        assertEquals(PendingSpeechText.Item.Wait, q.next())
        assertFalse(q.finish())
    }
    @Test fun `queue bound fails explicitly rather than silently losing text`() {
        val q = PendingSpeechText(4)
        q.append("four")
        assertThrows(VoiceTurnProcessingException.InvalidDecision::class.java) { q.append("five") }
    }
}
