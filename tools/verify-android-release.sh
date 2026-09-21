#!/usr/bin/env bash
# Copyright © 2026 立方田 <managecode@gmail.com>
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo_dir/tools/android-env.sh"
: "${ZHIMO_ANDROID_KEYSTORE:?ZHIMO_ANDROID_KEYSTORE must point to the release keystore}"
: "${ZHIMO_ANDROID_KEY_ALIAS:?ZHIMO_ANDROID_KEY_ALIAS is required}"
: "${ZHIMO_ANDROID_STORE_PASSWORD:?ZHIMO_ANDROID_STORE_PASSWORD is required}"
: "${ZHIMO_ANDROID_KEY_PASSWORD:?ZHIMO_ANDROID_KEY_PASSWORD is required}"
: "${ZHIMO_ANDROID_CERT_SHA256:?ZHIMO_ANDROID_CERT_SHA256 is required}"

[[ "$ZHIMO_ANDROID_KEYSTORE" = /* ]] || {
  echo "ZHIMO_ANDROID_KEYSTORE must be an absolute path" >&2
  exit 2
}
test -f "$ZHIMO_ANDROID_KEYSTORE"
command -v unzip >/dev/null
command -v sha256sum >/dev/null
cd "$repo_dir"
python3 tools/prepare-streaming-speech-model.py --model zipformer
bash tools/build-streaming-speech-android.sh
python3 tools/package-streaming-speech.py
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
