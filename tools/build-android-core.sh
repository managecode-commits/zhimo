#!/usr/bin/env bash
set -euo pipefail

if ! cargo ndk --version >/dev/null 2>&1; then
  echo "cargo-ndk is required: cargo install cargo-ndk --locked" >&2
  exit 2
fi
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to the Android NDK}"

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output="$repo_dir/platform/android-ime/app/src/main/jniLibs"

abis=(arm64-v8a armeabi-v7a x86_64)
if [[ -z "${ANDROID_RIME_ROOT:-}" ]]; then
  cargo ndk \
    --target arm64-v8a \
    --target armeabi-v7a \
    --target x86_64 \
    --platform 26 \
    --output-dir "$output" \
    build -p ime-ffi --release
  exit 0
fi

for abi in "${abis[@]}"; do
  rime_prefix="$ANDROID_RIME_ROOT/$abi"
  test -f "$rime_prefix/include/rime_api.h"
  test -d "$rime_prefix/lib"
  RIME_ROOT="$rime_prefix" cargo ndk \
    --target "$abi" \
    --platform 26 \
    --output-dir "$output" \
    build -p ime-ffi --release --features native-librime
  find "$rime_prefix/lib" -maxdepth 1 \( -type f -o -type l \) -name '*.so' \
    -exec cp -Lf '{}' "$output/$abi/" ';'
done

for abi in "${abis[@]}"; do
  test -s "$output/$abi/libime_ffi.so"
done
