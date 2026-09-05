package com.mobileclaw.realtime

import org.junit.Assert.*
import org.junit.Test

class VoiceControlEnvelopeTest {
    @Test fun `supported envelopes parse strictly`() {
        assertEquals(VoiceControlEnvelope.Start("Open Spotify"), VoiceControlEnvelopeParser.parse("""{"op":"start_phone_task","goal":"Open Spotify"}"""))
        assertEquals(VoiceControlEnvelope.Status, VoiceControlEnvelopeParser.parse("""{"op":"get_phone_task_status"}"""))
        assertEquals(VoiceControlEnvelope.Cancel, VoiceControlEnvelopeParser.parse("""{"op":"cancel_phone_task"}"""))
        assertEquals(VoiceControlEnvelope.Replace("Open YouTube"), VoiceControlEnvelopeParser.parse("""{"op":"replace_phone_task","goal":"Open YouTube"}"""))
    }

    @Test fun `blank unknown extra and malformed controls are rejected`() {
        assertNull(VoiceControlEnvelopeParser.parse("""{"op":"start_phone_task","goal":" "}"""))
        assertNull(VoiceControlEnvelopeParser.parse("""{"op":"replace_phone_task","goal":" "}"""))
        assertNull(VoiceControlEnvelopeParser.parse("""{"op":"start_phone_task","goal":"Open Spotify","extra":true}"""))
        assertNull(VoiceControlEnvelopeParser.parse("""{"op":"replace_phone_task","goal":"Open YouTube","unexpected":123}"""))
        assertNull(VoiceControlEnvelopeParser.parse("""{"op":"get_phone_task_status","extra":true}"""))
        assertNull(VoiceControlEnvelopeParser.parse("""{"op":"unknown"}"""))
        assertNull(VoiceControlEnvelopeParser.parse("""{"op":"cancel_phone_task","goal":"do something"}"""))
        assertNull(VoiceControlEnvelopeParser.parse("""{"op":"start_phone_task","goal":123}"""))
        assertNull(VoiceControlEnvelopeParser.parse("""{"goal":"Open Spotify"}"""))
        assertNull(VoiceControlEnvelopeParser.parse("""{"op":"start_phone_task","op":"cancel_phone_task","goal":"Open Spotify"}"""))
        assertNull(VoiceControlEnvelopeParser.parse("[]"))
        assertNull(VoiceControlEnvelopeParser.parse("42"))
        assertNull(VoiceControlEnvelopeParser.parse("{"))
    }
}
