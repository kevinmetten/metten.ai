package com.mobileclaw.voice

import org.junit.Assert.*
import org.junit.Test

class KittenTtsModelSpecTest {
    @Test fun `phase one model and voice are pinned`() {
        assertEquals("kitten-nano-en-v0_8-fp32", KittenTtsModelSpec.PACKAGE)
        assertEquals("tts/kitten-nano-en-v0_8-fp32/model.fp32.onnx", KittenTtsModelSpec.MODEL)
        assertEquals("expr-voice-2-f", KittenTtsModelSpec.VOICE)
        assertEquals(1, KittenTtsModelSpec.SPEAKER_ID)
        assertEquals("16092117bfe591ddcd58d078e1454603b8e1caea46f85653b2c2efae76bd883e", KittenTtsModelSpec.MODEL_ARCHIVE_SHA256)
    }
}
