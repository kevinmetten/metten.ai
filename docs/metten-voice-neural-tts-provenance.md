# Metten Voice neural TTS provenance

Phase 1 is an internal, debug-only Fold 7 evaluation.

* Runtime: `sherpa-onnx` v1.13.7, `sherpa-onnx-1.13.7.aar`, 49,113,869 bytes, SHA-256 `c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8`.
* Model: `KittenML/kitten-tts-nano-0.8-fp32`, sherpa package `kitten-nano-en-v0_8-fp32.tar.bz2`, 63,815,222 bytes, SHA-256 `16092117bfe591ddcd58d078e1454603b8e1caea46f85653b2c2efae76bd883e`.
* Conversion reference: sherpa-onnx commit `df3a2636074ae9bbf84c03f66b9d8a66290af077`, `scripts/kitten-tts/v0_8/run.sh`.
* Voice: `expr-voice-2-f`, SID 1. Runtime initialization also validates the SID against `numSpeakers()`.
* Provisioning uses immutable GitHub release URLs and writes an extracted-file manifest after checksum verification.

The debug adapter deliberately uses complete-waveform `generate()` for Phase 1. The unavailable build environment prevented source-level confirmation that v1.13.7 Kitten callbacks are incremental, non-cumulative PCM with the necessary lifetime and cancellation guarantees. Complete generation makes the total frame count immutable before AudioTrack playback begins and removes the late-marker race.
