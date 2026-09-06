package com.mobileclaw.voice

object KittenTtsModelSpec {
    const val PACKAGE = "kitten-nano-en-v0_8-fp32"
    const val ROOT = "tts/$PACKAGE"
    const val MODEL = "$ROOT/model.fp32.onnx"
    const val VOICES = "$ROOT/voices.bin"
    const val TOKENS = "$ROOT/tokens.txt"
    const val DATA_DIR = "$ROOT/espeak-ng-data"
    const val VOICE = "expr-voice-2-f"
    const val SPEAKER_ID = 1
    const val MODEL_ARCHIVE_SIZE = 63_815_222L
    const val MODEL_ARCHIVE_SHA256 = "16092117bfe591ddcd58d078e1454603b8e1caea46f85653b2c2efae76bd883e"
}
