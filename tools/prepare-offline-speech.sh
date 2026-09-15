#!/usr/bin/env bash
# Copyright © 2026 立方田 <managecode@gmail.com>
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
download_dir="$repo_dir/target/speech-downloads"
vendor_dir="$repo_dir/target/vendor"
model_dir="$repo_dir/models/speech"
mkdir -p "$download_dir" "$vendor_dir" "$model_dir"
fetch_checked() {
  local url="$1" destination="$2" expected="$3"
  if [[ -f "$destination" ]] && [[ "$(sha256sum "$destination" | cut -d ' ' -f 1)" == "$expected" ]]; then return; fi
  local partial
  partial="$(mktemp "$download_dir/fetch.XXXXXX")"
  if ! curl -fL --retry 2 --connect-timeout 20 --max-time 1200 "$url" -o "$partial"; then
    echo "Download failed; partial file retained: $partial" >&2; return 1
  fi
  [[ "$(sha256sum "$partial" | cut -d ' ' -f 1)" == "$expected" ]] || {
    echo "SHA-256 mismatch: $partial" >&2; return 1;
  }
  mv "$partial" "$destination"
}
fetch_checked https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/refs/tags/v1.9.1 \
  "$download_dir/whisper-v1.9.1.tar.gz" 147267177eef7b22ec3d2476dd514d1b12e160e176230b740e3d1bd600118447
if [[ ! -f "$vendor_dir/whisper.cpp-1.9.1/include/whisper.h" ]]; then
  tar -xzf "$download_dir/whisper-v1.9.1.tar.gz" -C "$vendor_dir"
fi
model_hash=422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898
model_revision=5359861c739e955e79d9a303bcbc70fb988958b1
# Optional public mirror; integrity is always checked against the same pinned LFS SHA-256.
model_host="${ZHIMO_MODEL_HOST:-https://huggingface.co}"
fetch_checked "$model_host/ggml-org/whisper-vad/resolve/9ffd54a1e1ee413ddf265af9913beaf518d1639b/ggml-silero-v5.1.2.bin" \
  "$model_dir/ggml-silero-v5.1.2.bin" 29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf
fetch_checked "$model_host/ggerganov/whisper.cpp/resolve/$model_revision/ggml-base-q5_1.bin" \
  "$download_dir/ggml-base-q5_1.bin" "$model_hash"
if [[ ! -f "$model_dir/ggml-base-q5_1.bin" ]] || [[ "$(sha256sum "$model_dir/ggml-base-q5_1.bin" | cut -d ' ' -f 1)" != "$model_hash" ]]; then
  cp "$download_dir/ggml-base-q5_1.bin" "$model_dir/ggml-base-q5_1.bin"
fi
echo "Pinned offline speech source and multilingual model ready."
