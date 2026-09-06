# Metten Voice neural TTS license status

This inventory covers the internal debug-only Phase-1 evaluation, not approval for public or commercial neural-TTS distribution.

| Component | Recorded status |
| --- | --- |
| sherpa-onnx v1.13.7 runtime | Apache-2.0 upstream; exact AAR is pinned and checksummed. |
| KittenTTS Nano v0.8 FP32 | Official model reports Apache-2.0; exact converted archive is pinned and checksummed. |
| Converted model and `voices.bin` | Conversion provenance is recorded; redistribution provenance and any underlying voice-data duties remain to be closed. |
| `tokens.txt`, dictionaries, normalization data | Included files are inventoried by the generated manifest; origin and production notice requirements remain under audit. |
| eSpeak NG/data | Presence is recorded from the exact archive. Copyleft, notice, data, and linkage obligations require resolution before public distribution. |

Nothing reviewed is known to prohibit an internal developer evaluation. Public/commercial neural-TTS release remains blocked until runtime binary composition, model weights, voices, phonemizer, dictionaries/data, attribution, source, and redistribution obligations are fully resolved.
