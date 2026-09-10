# Metten Voice Soniqo candidate provenance

The debug conversational candidate uses `audio.soniqo:speech:0.0.21`, inspected at
speech-android commit `ca9c58916cc63224fc74f81f95310b7546868b71` with speech-core
commit `c2cdcf2f1b90f15acac640cf8cf5233ed1f388a9`.

## Licenses and runtime

* speech-android and speech-core: Apache-2.0.
* `soniqo/Parakeet-EOU-120M-ONNX-INT8`: CC-BY-4.0.
* `soniqo/Pocket-TTS-100M-ONNX-INT8`: CC-BY-4.0.
* `soniqo/Silero-VAD-v5-ONNX`: MIT.
* `soniqo/DeepFilterNet3-ONNX`: MIT (not enabled initially).

The two CC-BY-4.0 model names and Soniqo authorship must remain in product notices.
The selected pipeline performs inference locally after public model provisioning and
requires no account, key, subscription, metering, activation, license server, or cloud
speech processing. Smart Turn and FunctionGemma are not used.

## Physical-test limitation

Soniqo v0.0.21 speech-core confirms interruption after a fixed 1.0 second minimum and
uses a 0.4 second recovery timeout. Android `SpeechConfig` does not expose the minimum.
Consequently a short one-word “Stop” is **not claimed solved** until a later integration
addresses the upstream surface and the behavior is tested on Galaxy Z Fold 7 hardware.
DeepFilterNet is enhancement rather than acoustic echo cancellation, and speech-android
does not integrate Android `AcousticEchoCanceler`; true AEC also remains a physical-test
follow-up.
