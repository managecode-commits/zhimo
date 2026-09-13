#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo_dir/tools/android-env.sh"

cd "$repo_dir"
./tools/build-android-core.sh

cd platform/android-ime
./gradlew --no-daemon --stacktrace :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
apk="app/build/outputs/apk/debug/app-debug.apk"
test -s "$apk"
verify_args=("$apk")
if [[ -n "${ANDROID_RIME_ROOT:-}" ]]; then
  verify_args+=(--require-rime)
fi
ANDROID_HOME="$ANDROID_HOME" ANDROID_NDK_HOME="$ANDROID_NDK_HOME" JAVA_HOME="$JAVA_HOME" \
  "$repo_dir/tools/verify-android-apk.sh" "${verify_args[@]}"

if command -v adb >/dev/null 2>&1 && [[ "$(adb get-state 2>/dev/null || true)" == "device" ]]; then
  test_args=()
  if [[ -n "${ANDROID_RIME_ROOT:-}" ]]; then
    test_args+=("-Pandroid.testInstrumentationRunnerArguments.requireNativeRime=true")
  fi
  ./gradlew --no-daemon :app:connectedDebugAndroidTest "${test_args[@]}"
  adb install -r "$apk"
else
  echo "APK built; connect an authorized Android device to run instrumentation tests." >&2
fi
