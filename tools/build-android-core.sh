#!/usr/bin/env bash
set -euo pipefail

if ! cargo ndk --version >/dev/null 2>&1; then
  echo "cargo-ndk is required: cargo install cargo-ndk --locked" >&2
  exit 2
fi
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to the Android NDK}"

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output="$repo_dir/platform/android-ime/app/src/main/jniLibs"
cargo ndk \
  --target arm64-v8a \
  --target armeabi-v7a \
  --target x86_64 \
  --platform 26 \
  --output-dir "$output" \
  build -p ime-ffi --release
