#!/usr/bin/env bash
# Copyright © 2026 立方田 <managecode@gmail.com>
set -euo pipefail

if ! command -v cargo >/dev/null 2>&1; then
  cargo_bin_dir="${CARGO_HOME:-${HOME:?HOME is required}/.cargo}/bin"
  if [[ -x "$cargo_bin_dir/cargo" ]]; then
    export PATH="$cargo_bin_dir:$PATH"
  else
    echo "Rust Cargo is required; install Rust with rustup and reopen the shell" >&2
    exit 2
  fi
fi
if ! command -v cargo-ndk >/dev/null 2>&1; then
  echo "cargo-ndk 4.1.2 is required: cargo install cargo-ndk --locked --version 4.1.2" >&2
  exit 2
fi
[[ "$(cargo ndk --version)" == "cargo-ndk 4.1.2" ]] || {
  echo "cargo-ndk 4.1.2 is required for the reproducible Android build" >&2
  exit 2
}
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to the Android NDK}"

for target in aarch64-linux-android armv7-linux-androideabi x86_64-linux-android; do
  rustup target list --installed | grep -Fx "$target" >/dev/null || {
    echo "missing Rust target: $target" >&2
    exit 2
  }
done

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
