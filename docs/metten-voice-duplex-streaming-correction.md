# Fold7 duplex and streaming correction

Base: `501c363ce308967e4cf199e4effad23f13f5044b` on
`codex/implement-full-duplex-conversational-speech-path`.

## Evidence and limits

The supplied device trace measures 57,498 ms in the brain request and about 303 ms
from response completion to AudioTrack startup. VAD starts 798 ms after playback,
well before the human interruption; later transcripts classify as echo, with no
OutputInterrupted. This establishes far-end contamination upstream of the lexical
gate and a whole-response TTFA bottleneck. It does not identify Samsung's private
DSP implementation or prove that a correctly configured platform AEC will succeed.

Before: VOICE_RECOGNITION, 16 kHz mono float AudioRecord, attached/enabled AEC on
that record's session, no session-owned communication mode/route, and
USAGE_ASSISTANT / CONTENT_TYPE_SPEECH AudioTrack. AEC enabled reports effect state,
not measured echo attenuation or a correctly routed far-end reference.

After: session-owned MODE_IN_COMMUNICATION and communication device (connected
headset when available, otherwise speaker), VOICE_COMMUNICATION capture, AEC on
the capture session, and USAGE_VOICE_COMMUNICATION / CONTENT_TYPE_SPEECH playback.
Capture and playback keep their own sessions; artificially sharing session IDs is
not the Android AEC reference mechanism. No separate NS or AGC is forced; vendor
communication preprocessing remains in charge. Route ownership survives mute
and is restored on release without overwriting an intervening call's mode.

The 320-sample Android reads are accumulated into exact 512-sample Silero blocks.
The pinned native TurnDetector processes only complete blocks and discards each
push's remainder. The former 1600-sample pushes lost 64 samples per full read;
short reads also needed framing. The framer preserves samples across reads.

No software AEC dependency added: the previous trace tests a mismatched Android
path, not the corrected communication path. WebRTC AEC3 would require synchronized
render-reference and capture frames, resampling, delay handling and native builds;
its reverse-stream API cannot be replaced with lexical filtering or microphone
muting. Evaluate it if repeated physical evidence shows the communication path
still fails. DeepFilterNet alone is not AEC.

References inspected:
- https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler
- https://developer.android.com/reference/android/media/MediaRecorder.AudioSource
- https://developer.android.com/reference/android/media/AudioManager
- https://source.android.com/docs/core/audio/implement-pre-processing
- https://webrtc.googlesource.com/src/+/refs/heads/main/modules/audio_processing/include/audio_processing.h

## Exact pinned upstream inspected

speech-android `a019eaf2896443c5e889a3f26463b81c42e9db1f`:
- `sdk/src/main/kotlin/audio/soniqo/speech/SpeechPipeline.kt`: pushAudio,
  resumeListening, synthesizeStreaming, cancelSynthesis, close.
- `sdk/src/main/cpp/jni_bridge.cpp`: nativePushAudio, PARAKEET_EOU construction,
  synthesize_streaming_pcm16, nativePipelineSynthesizeStreaming,
  nativePipelineCancelSynthesis. PARAKEET_EOU uses OnnxNemotronStreamingStt with
  Parakeet EOU model files; this class name does not imply a model substitution.

speech-core `c2cdcf2f1b90f15acac640cf8cf5233ed1f388a9`:
- `src/pipeline/voice_pipeline.cpp`: push_audio, worker_loop, TranscribeOnly
  response path, optional echo_canceller processing and playback-reference feed.
- `src/pipeline/turn_detector.cpp`: push_audio, end_turn, VAD block consumption.
- `src/vad/streaming_vad.cpp`: process, process_prob, onset/silence state handling.
- `src/models/nemotron/onnx_nemotron_streaming_stt.cpp`: begin_stream, push_chunk,
  flush_stream, end_stream, cancel_stream. Beam mode emits replacement partials;
  finalization flushes remaining encoder audio.

Direct JNI synthesis calls the TTS object under its synthesis mutex. It does not
arm VoicePipeline's agent-speaking state or feed its optional echo canceller.
The Android wrapper exposes no configured reference AEC in this app. Therefore
Metten's sequence-aware gate remains necessary; thresholds stay 500/1000 ms,
with the same 1500 ms output-tail quarantine.

## Streaming and ownership

LlmVoiceTurnBrain previously called `llm.chat(stream=false)` and parsed JSON only
after completion. ChatGptOAuthGateway already uses SSE and
ChatGptResponsesStreamParser already forwards output_text.delta through onToken
when stream=true. Its awaitAndConsume retains OkHttp cancellation during body
consumption. OpenAiGateway likewise exposes ChatRequest's streaming callback;
no additional networking stack or credentials are introduced.

Voice now enables that existing callback. A strict conversation prefix commits
action=conversation and goal=null before exposing spoken_text. JSON escapes are
decoded incrementally, sentences/whitespace phrases are accumulated, and the
final tail waits for complete decision validation. Phone actions still wait for
canonical full-decision validation and coordinator acceptance. Differently
ordered valid JSON retains the complete-response compatibility path. A revised
or malformed streamed envelope fails; its pending tail is not spoken.

One StreamTurn binds the conversational TurnToken, OutputToken and exact speech
handle. The handle feeds a bounded single-consumer text queue. One Soniqo Output,
PlaybackIdentity and PocketStreamingPlayback cover all chunks and gaps. Each
chunk still passes through PocketTextSegmentation and cache-overflow split/retry;
only logical EOF finishes playback. The gate's reference grows with text admitted
to synthesis so later echo is not classified against just the first sentence.

Interruption clears that turn, cancels its coroutine/OkHttp request and exact
speech handle, discards queued text, requests native synthesis cancellation, and
pauses/flushes/releases the exact track. Stale generation callbacks cannot append
PCM or complete a new turn. Native close is queued behind in-flight synthesis.
PHONE_CONTROL submission, execution, cancellation and coordinator ownership are
unchanged. Phone announcements arriving during streamed speech are deferred.

## Diagnostics and verification

Numerical diagnostics add communication route/mode, capture source/session,
AEC state/control, output usage/session/device, first useful text, chunk lengths,
network cancellation and actual track stop. TURN_LATENCY now indicates whether
audio started before BRAIN_RESPONSE_COMPLETE and clears previous turn timings.
No transcript or answer bodies are added to diagnostics; existing bounded sink
and release no-op behavior remain.

Added tests exercise every token split of escaped JSON, chunk ordering/tails,
malformed-envelope rejection, bounded queue cancellation/wakeup, three repeated
controller interruptions, stale completion, new-turn progress, phone-worker
isolation, teardown isolation, growing echo references, exact sample framing,
and Pocket cache-overflow protection across progressive input. Existing tests
are retained. These are deterministic state/data tests, not acoustic evidence.

Local Gradle execution is blocked before configuration: the Gradle distribution
download fails with Network is unreachable; no Android SDK is configured.
`voice-correction.yml` supplies PR-triggered compileDebugKotlin, debug Voice/brain/
ChatGPT/ownership tests, compileReleaseKotlin and corresponding release tests.
It contains no APK assembly or workflow_dispatch. Validation results must be read
from that check; this document does not claim a successful compile or test run.

Unchanged: PARAKEET_EOU, POCKET, INT8, Soniqo 0.0.20 custom ONNX-only AAR,
TRANSCRIBE_ONLY, application/package identity, AgentRuntime/PHONE_CONTROL, no
paid/cloud speech service. No APK built or manual workflow dispatched.

Physical gate remains 3/3 volcano-to-earthquake interruptions, no self-interrupts,
one replacement answer each time, no resumed old speech, and first audio before
brain completion for a long streamed answer. Do not merge PR #37 on unit tests.

## Publication status

Automatic approval review rejected the push to
`https://github.com/kevinmetten/metten.ai.git`, citing insufficient explicit
authorization for the exact destination and source payload. The remote branch
lookup returned an empty list. No PR exists and no CI was triggered. Publishing
this reviewed local diff requires explicit approval; no workaround was used.
Local debug/release compilation and test attempts stopped in the Gradle wrapper
with `java.net.SocketException: Network is unreachable`, before any task ran.
`git diff 501c363 --check` passed. No native/dependency change requires additional
native-library merging or dependency-resolution checks in this correction.

## Changed files

- `.github/workflows/voice-correction.yml`
- `app/src/debug/AndroidManifest.xml`
- `app/src/debug/java/com/mobileclaw/voice/AndroidDuplexAudio.kt`
- `app/src/debug/java/com/mobileclaw/voice/AndroidStreamingPcm16Player.kt`
- `app/src/debug/java/com/mobileclaw/voice/PocketSegmentedSynthesis.kt`
- `app/src/debug/java/com/mobileclaw/voice/SoniqoAudioCapture.kt`
- `app/src/debug/java/com/mobileclaw/voice/SoniqoConversationalSpeechSession.kt`
- `app/src/debug/java/com/mobileclaw/voice/SoniqoInterruptionGate.kt`
- `app/src/debug/java/com/mobileclaw/voice/SoniqoPcmFramer.kt`
- `app/src/main/java/com/mobileclaw/voice/MettenVoiceSessionController.kt`
- `app/src/main/java/com/mobileclaw/voice/PendingSpeechText.kt`
- `app/src/main/java/com/mobileclaw/voice/SpeechEngines.kt`
- `app/src/main/java/com/mobileclaw/voice/VoiceDecisionStream.kt`
- `app/src/main/java/com/mobileclaw/voice/VoiceDiagnosticTrace.kt`
- `app/src/main/java/com/mobileclaw/voice/VoiceTurnBrain.kt`
- `app/src/test/java/com/mobileclaw/voice/LlmVoiceTurnBrainTest.kt`
- `app/src/test/java/com/mobileclaw/voice/StreamingVoiceOwnershipTest.kt`
- `app/src/test/java/com/mobileclaw/voice/VoiceDecisionStreamTest.kt`
- `app/src/testDebug/java/com/mobileclaw/voice/PocketSegmentedSynthesisTest.kt`
- `app/src/testDebug/java/com/mobileclaw/voice/SoniqoInterruptionGateTest.kt`
- `app/src/testDebug/java/com/mobileclaw/voice/SoniqoPcmFramerTest.kt`
- `docs/metten-voice-duplex-streaming-correction.md`
