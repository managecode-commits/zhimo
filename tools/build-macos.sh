#!/usr/bin/env bash
# Copyright © 2026 立方田 <managecode@gmail.com>
set -euo pipefail
[[ "$(uname -s)" == Darwin ]] || { echo 'Requires macOS, Xcode command-line tools, Rust and CMake; no macOS package was generated.' >&2; exit 2; }
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"
output="${1:-$repo_dir/target/packages/zhimo-macos-$(date +%Y%m%d-%H%M%S)}"
[[ "$output" = /* ]] || output="$repo_dir/$output"
test ! -e "$output" && test ! -e "$output.zip"
test ! -e "$output-notarized.zip"
command -v cmake >/dev/null
test -f models/speech/ggml-base-q5_1.bin
test -f target/vendor/whisper.cpp-1.9.1/include/whisper.h
work="$(mktemp -d "$repo_dir/target/macos-build.XXXXXX")"
app="$output/Zhimo.app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources/models" "$app/Contents/Frameworks"
sdk="$(xcrun --sdk macosx --show-sdk-path)"
export MACOSX_DEPLOYMENT_TARGET=12.0
export CARGO_BUILD_JOBS=2
for arch in arm64 x86_64; do
  if [[ "$arch" == arm64 ]]; then rust_target=aarch64-apple-darwin; else rust_target=x86_64-apple-darwin; fi
  rustup target add "$rust_target"
  cargo build --locked --release -p ime-ffi --target "$rust_target"
  cmake -S platform/shared-speech -B "$work/speech-$arch" -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_OSX_ARCHITECTURES="$arch" -DCMAKE_OSX_DEPLOYMENT_TARGET=12.0 -DBUILD_TESTING=OFF
  cmake --build "$work/speech-$arch" -j2
  xcrun swiftc -swift-version 5 -target "$arch-apple-macosx12.0" -sdk "$sdk" \
    -I platform/apple -L "$work/speech-$arch" -lzhimo_speech \
    platform/apple/ZhimoSession.swift platform/macos-inputmethod/InputController.swift \
    platform/macos-inputmethod/MacInputPanel.swift platform/macos-inputmethod/main.swift \
    "target/$rust_target/release/libime_ffi.a" \
    -framework Cocoa -framework InputMethodKit -framework Carbon -framework AVFoundation \
    -framework Security -framework SystemConfiguration -lc++ -liconv \
    -Xlinker -rpath -Xlinker @executable_path/../Frameworks -o "$work/Zhimo-$arch"
  if [[ "$arch" == "$(uname -m)" ]]; then
    xcrun swiftc -swift-version 5 -parse-as-library -I platform/apple \
      platform/apple/ZhimoSession.swift platform/macos-inputmethod/CoreProbe.swift \
      "target/$rust_target/release/libime_ffi.a" -framework Security -framework SystemConfiguration -lc++ -liconv -o "$work/core-probe"
    "$work/core-probe"
  fi
done
lipo -create "$work/Zhimo-arm64" "$work/Zhimo-x86_64" -output "$app/Contents/MacOS/Zhimo"
lipo -create "$work/speech-arm64/libzhimo_speech.dylib" "$work/speech-x86_64/libzhimo_speech.dylib" -output "$app/Contents/Frameworks/libzhimo_speech.dylib"
install_name_tool -id @rpath/libzhimo_speech.dylib "$app/Contents/Frameworks/libzhimo_speech.dylib"
for arch in arm64 x86_64; do
  install_name_tool -change "$work/speech-$arch/libzhimo_speech.dylib" @rpath/libzhimo_speech.dylib "$app/Contents/MacOS/Zhimo"
done
cp platform/macos-inputmethod/Info.plist "$app/Contents/Info.plist"
cp -R models/handwriting "$app/Contents/Resources/models/"
cp -R models/speech "$app/Contents/Resources/models/"
cp LICENSE THIRD_PARTY_NOTICES.md "$app/Contents/Resources/"
icons=platform/macos-inputmethod/Assets.xcassets/AppIcon.appiconset
mkdir -p "$work/Zhimo.iconset"
for size in 16 32 128 256 512; do
  cp "$icons/icon-$size.png" "$work/Zhimo.iconset/icon_${size}x${size}.png"
  double=$((size * 2))
  cp "$icons/icon-$double.png" "$work/Zhimo.iconset/icon_${size}x${size}@2x.png"
done
iconutil -c icns "$work/Zhimo.iconset" -o "$app/Contents/Resources/Zhimo.icns"
cp "$icons/icon-32.png" "$app/Contents/Resources/menu-icon.png"
identity="${ZHIMO_MAC_SIGN_IDENTITY:--}"
sign_options=(--force --options runtime --sign "$identity")
if [[ "$identity" != - ]]; then sign_options+=(--timestamp); fi
codesign "${sign_options[@]}" "$app/Contents/Frameworks/libzhimo_speech.dylib"
codesign "${sign_options[@]}" --entitlements platform/macos-inputmethod/Entitlements.plist "$app"
codesign --verify --deep --strict "$app"
if otool -L "$app/Contents/MacOS/Zhimo" "$app/Contents/Frameworks/libzhimo_speech.dylib" | grep -F "$work/"; then
  echo 'Unexpected build-directory dependency in release payload' >&2; exit 2
fi
cp platform/macos-inputmethod/install.command platform/macos-inputmethod/README.md "$output/"
chmod +x "$output/install.command"
ditto -c -k --keepParent "$output" "$output.zip"
if test -n "${ZHIMO_MAC_NOTARY_PROFILE:-}"; then
  [[ "$identity" != - ]] || { echo 'Notarization requires Developer ID signing'; exit 2; }
  xcrun notarytool submit "$output.zip" --keychain-profile "$ZHIMO_MAC_NOTARY_PROFILE" --wait
  xcrun stapler staple "$app"
  ditto -c -k --keepParent "$output" "$output-notarized.zip"
  shasum -a 256 "$output-notarized.zip"
fi
shasum -a 256 "$output.zip"
echo 'macOS build complete. Ad-hoc signing is local testing only; IMK application compatibility still requires manual validation.'
