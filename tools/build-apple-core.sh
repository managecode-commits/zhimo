#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "Apple XCFramework builds require macOS and Xcode" >&2
  exit 2
fi

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build_dir="$repo_dir/target/apple-xcframework"
mkdir -p "$build_dir"
mkdir -p "$build_dir/Resources"
cp -R "$repo_dir/models/handwriting" "$build_dir/Resources/"

rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios
cargo build -p ime-ffi --release --target aarch64-apple-ios
cargo build -p ime-ffi --release --target aarch64-apple-ios-sim
cargo build -p ime-ffi --release --target x86_64-apple-ios

lipo -create \
  "$repo_dir/target/aarch64-apple-ios-sim/release/libime_ffi.a" \
  "$repo_dir/target/x86_64-apple-ios/release/libime_ffi.a" \
  -output "$build_dir/libime_ffi-simulator.a"

xcodebuild -create-xcframework \
  -library "$repo_dir/target/aarch64-apple-ios/release/libime_ffi.a" -headers "$repo_dir/include" \
  -library "$build_dir/libime_ffi-simulator.a" -headers "$repo_dir/include" \
  -output "$build_dir/ShurufaCore.xcframework"
