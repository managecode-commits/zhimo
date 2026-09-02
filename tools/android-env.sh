#!/usr/bin/env bash
# Sourced by Android build scripts. Discovers tools but never accepts licenses.

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in /tmp/shurufa-jdk17 "${HOME:-}/.sdkman/candidates/java/current"; do
    if [[ -x "$candidate/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi
if [[ -z "${JAVA_HOME:-}" ]] && command -v java >/dev/null 2>&1; then
  java_home="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.home = //p' | head -n 1)"
  if [[ -x "$java_home/bin/java" ]]; then export JAVA_HOME="$java_home"; fi
fi
: "${JAVA_HOME:?JAVA_HOME must point to JDK 17}"
test -x "$JAVA_HOME/bin/java" || {
  echo "JAVA_HOME does not contain bin/java: $JAVA_HOME" >&2
  exit 2
}

if [[ -z "${ANDROID_HOME:-}" && -n "${ANDROID_SDK_ROOT:-}" ]]; then
  export ANDROID_HOME="$ANDROID_SDK_ROOT"
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
  local_properties="$repo_dir/platform/android-ime/local.properties"
  if [[ -f "$local_properties" ]]; then
    sdk_dir="$(sed -n 's/^sdk\.dir=//p' "$local_properties" | head -n 1)"
    if [[ -d "$sdk_dir" ]]; then export ANDROID_HOME="$sdk_dir"; fi
  fi
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
  for candidate in "${HOME:-}/Android/Sdk" "${HOME:-}/Library/Android/sdk" /tmp/shurufa-android-sdk; do
    if [[ -f "$candidate/licenses/android-sdk-license" && -f "$candidate/platforms/android-37.0/android.jar" ]]; then
      export ANDROID_HOME="$candidate"
      break
    fi
  done
fi
: "${ANDROID_HOME:?ANDROID_HOME must point to an Android SDK whose licenses were accepted by its owner}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
test -f "$ANDROID_HOME/licenses/android-sdk-license" || {
  echo "Android SDK license acceptance is not recorded under $ANDROID_HOME/licenses" >&2
  exit 2
}
test -f "$ANDROID_HOME/platforms/android-37.0/android.jar" || {
  echo "Android SDK Platform 37.0 is not installed under $ANDROID_HOME" >&2
  exit 2
}

export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}"
test -f "$ANDROID_NDK_HOME/source.properties" || {
  echo "Android NDK 28.2.13676358 is not installed under $ANDROID_HOME/ndk" >&2
  exit 2
}
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
