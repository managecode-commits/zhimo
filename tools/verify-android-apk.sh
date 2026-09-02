#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "usage: $0 APK [--require-rime]" >&2
  exit 2
fi
apk="$1"
require_rime="${2:-}"
[[ -z "$require_rime" || "$require_rime" == "--require-rime" ]] || {
  echo "unknown option: $require_rime" >&2
  exit 2
}
: "${JAVA_HOME:?JAVA_HOME must point to JDK 17}"
: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to the Android NDK}"
test -x "$JAVA_HOME/bin/java"
export PATH="$JAVA_HOME/bin:$PATH"
test -s "$apk"
command -v unzip >/dev/null
command -v sha256sum >/dev/null

apksigner_bin="$(command -v apksigner || true)"
if [[ -z "$apksigner_bin" ]]; then
  apksigner_bin="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner -print 2>/dev/null | sort -V | tail -n 1)"
fi
readelf_bin="$(find "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" -name llvm-readelf -print | head -n 1)"
test -x "$apksigner_bin"
test -x "$readelf_bin"
signer_output="$("$apksigner_bin" verify --verbose --print-certs "$apk")"
printf '%s\n' "$signer_output"
if [[ -n "${SHURUFA_ANDROID_CERT_SHA256:-}" ]]; then
  actual_cert="$(sed -n 's/.*certificate SHA-256 digest: //p' <<<"$signer_output" | head -n 1 | tr '[:upper:]' '[:lower:]')"
  expected_cert="$(tr -d ':' <<<"$SHURUFA_ANDROID_CERT_SHA256" | tr '[:upper:]' '[:lower:]')"
  [[ "$actual_cert" == "$expected_cert" ]] || {
    echo "APK signer certificate SHA-256 does not match SHURUFA_ANDROID_CERT_SHA256" >&2
    exit 1
  }
fi

for abi in arm64-v8a armeabi-v7a x86_64; do
  unzip -Z1 "$apk" | grep -Fx "lib/$abi/libime_ffi.so" >/dev/null
  unzip -Z1 "$apk" | grep -Fx "lib/$abi/libshurufa_android.so" >/dev/null
  case "$abi" in
    arm64-v8a) expected_machine="AArch64" ;;
    armeabi-v7a) expected_machine="ARM" ;;
    x86_64) expected_machine="Advanced Micro Devices X86-64" ;;
  esac
  while IFS= read -r library; do
    elf="$(unzip -p "$apk" "$library" | "$readelf_bin" -h -d -)"
    grep -F "Machine:" <<<"$elf" | grep -F "$expected_machine" >/dev/null
    if grep -E 'Shared library: \[/|Shared library: \[[A-Za-z]:[/\\]' <<<"$elf"; then
      echo "absolute DT_NEEDED path found in $library" >&2
      exit 1
    fi
  done < <(unzip -Z1 "$apk" | grep -E "^lib/$abi/.+\\.so$")
  bridge_dynamic="$(unzip -p "$apk" "lib/$abi/libshurufa_android.so" | "$readelf_bin" -d -)"
  grep -F 'Shared library: [libime_ffi.so]' <<<"$bridge_dynamic" >/dev/null
  if [[ "$require_rime" == "--require-rime" ]]; then
    unzip -Z1 "$apk" | grep -Fx "lib/$abi/librime.so" >/dev/null
    ime_dynamic="$(unzip -p "$apk" "lib/$abi/libime_ffi.so" | "$readelf_bin" -d -)"
    grep -F 'Shared library: [librime.so]' <<<"$ime_dynamic" >/dev/null
  fi
done

sha256sum "$apk"
