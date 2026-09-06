#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="${METTEN_TTS_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/metten-tts}"
AAR_NAME="sherpa-onnx-1.13.7.aar"
AAR_SIZE=49113869
AAR_SHA="c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8"
AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.7/$AAR_NAME"
MODEL_NAME="kitten-nano-en-v0_8-fp32"
ARCHIVE="$MODEL_NAME.tar.bz2"
ARCHIVE_SIZE=63815222
ARCHIVE_SHA="16092117bfe591ddcd58d078e1454603b8e1caea46f85653b2c2efae76bd883e"
ARCHIVE_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$ARCHIVE"
DEST="$ROOT/app/src/debug/assets/tts/$MODEL_NAME"

mkdir -p "$CACHE" "$ROOT/app/libs" "$(dirname "$DEST")"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fetch() {
  local url="$1" path="$2" size="$3" sha="$4"
  if [[ ! -f "$path" ]] || [[ "$(stat -c %s "$path")" != "$size" ]] || ! printf '%s  %s\n' "$sha" "$path" | sha256sum -c - >/dev/null 2>&1; then
    rm -f "$path"
    curl --fail --location --retry 3 --output "$path.part" "$url"
    [[ "$(stat -c %s "$path.part")" == "$size" ]] || { echo "Unexpected byte size for $(basename "$path")" >&2; exit 1; }
    printf '%s  %s\n' "$sha" "$path.part" | sha256sum -c - >/dev/null
    mv "$path.part" "$path"
  fi
  printf '%s  %s\n' "$sha" "$path" | sha256sum -c - >/dev/null
}

fetch "$AAR_URL" "$CACHE/$AAR_NAME" "$AAR_SIZE" "$AAR_SHA"
fetch "$ARCHIVE_URL" "$CACHE/$ARCHIVE" "$ARCHIVE_SIZE" "$ARCHIVE_SHA"

unzip -l "$CACHE/$AAR_NAME" > "$tmp/aar-entries"
grep -q 'jni/arm64-v8a/libsherpa-onnx-jni.so' "$tmp/aar-entries" || { echo "Pinned AAR lacks arm64 sherpa JNI" >&2; exit 1; }
grep -q 'jni/x86_64/libsherpa-onnx-jni.so' "$tmp/aar-entries" || { echo "Pinned AAR lacks x86_64 sherpa JNI" >&2; exit 1; }
unzip -p "$CACHE/$AAR_NAME" classes.jar > "$tmp/classes.jar"
for class in OfflineTts OfflineTtsConfig OfflineTtsKittenModelConfig OfflineTtsModelConfig; do
  jar tf "$tmp/classes.jar" | grep -q "com/k2fsa/sherpa/onnx/$class.class" || {
    echo "Pinned AAR lacks $class" >&2; exit 1;
  }
done

tar -tjf "$CACHE/$ARCHIVE" > "$tmp/entries"
while IFS= read -r entry; do
  [[ "$entry" != /* && "$entry" != ../* && "$entry" != *"/../"* && "$entry" != *"/.." ]] || {
    echo "Unsafe model archive entry: $entry" >&2; exit 1;
  }
  [[ "$entry" == "$MODEL_NAME" || "$entry" == "$MODEL_NAME/"* ]] || {
    echo "Unexpected model archive layout: $entry" >&2; exit 1;
  }
done < "$tmp/entries"
tar -xjf "$CACHE/$ARCHIVE" -C "$tmp"
source_dir="$tmp/$MODEL_NAME"
for required in model.fp32.onnx voices.bin tokens.txt; do
  [[ -f "$source_dir/$required" ]] || { echo "Missing required model payload: $required" >&2; exit 1; }
done

stage="$tmp/stage"
mkdir -p "$stage"
cp "$source_dir/model.fp32.onnx" "$source_dir/voices.bin" "$source_dir/tokens.txt" "$stage/"
if [[ -d "$source_dir/espeak-ng-data" ]]; then cp -R "$source_dir/espeak-ng-data" "$stage/"; fi

payload_json="$tmp/payload.json"
(
  cd "$stage"
  find . -type f ! -name metten-model-manifest.json -print0 | sort -z | xargs -0 sha256sum |
    python3 -c 'import json,sys; print(json.dumps([{"path": l.split("  ",1)[1].removeprefix("./"), "sha256": l.split("  ",1)[0]} for l in sys.stdin.read().splitlines()], sort_keys=True))'
) > "$payload_json"
python3 - "$payload_json" "$stage/metten-model-manifest.json" <<'PY'
import json, sys
payload = json.load(open(sys.argv[1], encoding="utf-8"))
manifest = {
  "sherpa": {"version":"1.13.7", "size":49113869, "sha256":"c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8"},
  "model": {"package":"kitten-nano-en-v0_8-fp32", "archiveSize":63815222, "sha256":"16092117bfe591ddcd58d078e1454603b8e1caea46f85653b2c2efae76bd883e"},
  "conversionCommit":"df3a2636074ae9bbf84c03f66b9d8a66290af077",
  "voice":{"name":"expr-voice-2-f", "sid":1},
  "payload":payload,
  "espeakProvenance":"included in the checksummed sherpa model archive" if any(x["path"].startswith("espeak-ng-data/") for x in payload) else "not present in the model archive",
}
open(sys.argv[2], "w", encoding="utf-8").write(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
PY

cp "$CACHE/$AAR_NAME" "$tmp/$AAR_NAME"
find "$ROOT/app/src/debug/assets/tts" -mindepth 1 -maxdepth 1 -type d -name 'kitten-nano-en-*' ! -name "$MODEL_NAME" -exec rm -rf {} +
rm -rf "$DEST"
mv "$stage" "$DEST"
mv "$tmp/$AAR_NAME" "$ROOT/app/libs/$AAR_NAME"
echo "Provisioned sherpa-onnx 1.13.7 and $MODEL_NAME"
