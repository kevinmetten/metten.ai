#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
aar="${1:-$repo_root/app/libs/soniqo-speech-0.0.20-metten-onnx-only.aar}"
provenance="$repo_root/docs/metten-voice-soniqo-provenance.md"

test -f "$aar"
expected_size="$(sed -n 's/^\* AAR byte size: `\([0-9][0-9]*\)`\.$/\1/p' "$provenance")"
expected_sha="$(sed -n 's/^\* AAR SHA-256: `\([0-9a-f]\{64\}\)`\.$/\1/p' "$provenance")"
test -n "$expected_size"
test -n "$expected_sha"
test "$(stat -c %s "$aar")" = "$expected_size"
printf '%s  %s\n' "$expected_sha" "$aar" | sha256sum -c -

listing="$(mktemp)"
trap 'rm -f "$listing"' EXIT
unzip -Z1 "$aar" > "$listing"
if grep -E '(^|/)libLiteRt\.so$' "$listing"; then
  echo "Pinned Soniqo AAR must not contain libLiteRt.so." >&2
  exit 1
fi
for abi in arm64-v8a x86_64; do
  grep -qx "jni/$abi/libspeech_android.so" "$listing"
  grep -qx "jni/$abi/libonnxruntime.so" "$listing"
done
