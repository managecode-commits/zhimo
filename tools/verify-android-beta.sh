#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${JAVA_HOME:?JAVA_HOME must point to JDK 17}"
: "${ANDROID_HOME:?ANDROID_HOME must point to an Android SDK whose licenses were accepted by its owner}"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to Android NDK 28.2.13676358}"

cd "$repo_dir"
./tools/build-android-core.sh

cd platform/android-ime
./gradlew --no-daemon --stacktrace :app:assembleDebug
apk="app/build/outputs/apk/debug/app-debug.apk"
test -s "$apk"
sha256sum "$apk"

if command -v adb >/dev/null 2>&1 && [[ "$(adb get-state 2>/dev/null || true)" == "device" ]]; then
  adb install -r "$apk"
  ./gradlew --no-daemon :app:connectedDebugAndroidTest
else
  echo "APK built; connect an authorized Android device to run instrumentation tests." >&2
fi
