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

apksigner_bin="$(command -v apksigner || true)"
if [[ -z "$apksigner_bin" ]]; then
  apksigner_bin="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner -print 2>/dev/null | sort -V | tail -n 1)"
fi
test -x "$apksigner_bin"
"$apksigner_bin" verify --verbose --print-certs "$apk"

for abi in arm64-v8a armeabi-v7a x86_64; do
  unzip -Z1 "$apk" | grep -Fx "lib/$abi/libime_ffi.so" >/dev/null
  unzip -Z1 "$apk" | grep -Fx "lib/$abi/libshurufa_android.so" >/dev/null
done
if [[ -n "${ANDROID_RIME_ROOT:-}" ]]; then
  for abi in arm64-v8a armeabi-v7a x86_64; do
    unzip -Z1 "$apk" | grep -Fx "lib/$abi/librime.so" >/dev/null
  done
fi

sha256sum "$apk" | tee "$apk.sha256"
echo "Signed Android release verified: $apk"
