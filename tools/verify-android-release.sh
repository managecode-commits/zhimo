#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${JAVA_HOME:?JAVA_HOME must point to JDK 17}"
: "${ANDROID_HOME:?ANDROID_HOME must point to the licensed Android SDK}"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to Android NDK 28.2.13676358}"
: "${SHURUFA_ANDROID_KEYSTORE:?SHURUFA_ANDROID_KEYSTORE must point to the release keystore}"
: "${SHURUFA_ANDROID_KEY_ALIAS:?SHURUFA_ANDROID_KEY_ALIAS is required}"
: "${SHURUFA_ANDROID_STORE_PASSWORD:?SHURUFA_ANDROID_STORE_PASSWORD is required}"
: "${SHURUFA_ANDROID_KEY_PASSWORD:?SHURUFA_ANDROID_KEY_PASSWORD is required}"
: "${SHURUFA_ANDROID_CERT_SHA256:?SHURUFA_ANDROID_CERT_SHA256 is required}"

[[ "$SHURUFA_ANDROID_KEYSTORE" = /* ]] || {
  echo "SHURUFA_ANDROID_KEYSTORE must be an absolute path" >&2
  exit 2
}
test -f "$SHURUFA_ANDROID_KEYSTORE"
command -v unzip >/dev/null
command -v sha256sum >/dev/null
cd "$repo_dir"
./tools/build-android-core.sh

cd platform/android-ime
./gradlew --no-daemon --stacktrace :app:assembleRelease
apk="app/build/outputs/apk/release/app-release.apk"
test -s "$apk"
verify_args=("$apk")
if [[ -n "${ANDROID_RIME_ROOT:-}" ]]; then verify_args+=(--require-rime); fi
ANDROID_HOME="$ANDROID_HOME" ANDROID_NDK_HOME="$ANDROID_NDK_HOME" JAVA_HOME="$JAVA_HOME" \
  "$repo_dir/tools/verify-android-apk.sh" "${verify_args[@]}"
sha256sum "$apk" > "$apk.sha256"
echo "Signed Android release verified: $apk"
