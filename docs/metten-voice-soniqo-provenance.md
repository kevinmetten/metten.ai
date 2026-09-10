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

Soniqo v0.0.21's native response path confirms interruption after a fixed 1.0 second
minimum and uses a 0.4 second recovery timeout, but direct TRANSCRIBE_ONLY synthesis does
not arm that native response state. Metten instead gates Soniqo VAD events for 500 ms when
Android AEC is attached and enabled, or a conservative 1,000 ms without it. Consequently
a short one-word “Stop” is **not claimed solved** until tested on Galaxy Z Fold 7 hardware.
DeepFilterNet is enhancement rather than acoustic echo cancellation. The current Android
JNI explicitly disables it because DeepFilterNet expects 48 kHz while the Voice pipeline
supplies 16 kHz and has not wired the required resampling. Metten attempts Android
`AcousticEchoCanceler` on its exact `AudioRecord` session; hardware support and effectiveness
remain physical-test questions.
