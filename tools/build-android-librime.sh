#!/usr/bin/env bash
# Copyright © 2026 立方田 <managecode@gmail.com>
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=android-env.sh
source "$repo_dir/tools/android-env.sh"

librime_revision="de4700e9f6b75b109910613df907965e3cbe0567"
prebuilt_revision="3587ba3355711f0aca50136e787719f6562676b8"
librime_url="https://github.com/rime/librime.git"
prebuilt_url="https://github.com/fcitx5-android/prebuilt.git"
android_api="${ZHIMO_ANDROID_RIME_API:-26}"
jobs="${ZHIMO_ANDROID_RIME_JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}"
cache_root="${ZHIMO_ANDROID_RIME_CACHE:-${XDG_CACHE_HOME:-${HOME:?HOME is required}/.cache}/zhimo-librime}"
output_root="${1:-$repo_dir/target/android-rime}"

case "$output_root" in
  /|"$HOME"|"$repo_dir")
    echo "refusing unsafe output directory: $output_root" >&2
    exit 2
    ;;
esac

for command in git sha256sum; do
  command -v "$command" >/dev/null || {
    echo "$command is required" >&2
    exit 2
  }
done

cmake_bin="${CMAKE_BIN:-}"
if [[ -z "$cmake_bin" ]]; then
  cmake_bin="$(find "$ANDROID_HOME/cmake" -mindepth 3 -maxdepth 3 -type f -path '*/bin/cmake' -print 2>/dev/null | sort -V | tail -n 1)"
fi
[[ -x "$cmake_bin" ]] || {
  echo "Android SDK CMake is required; install it with sdkmanager 'cmake;3.31.6'" >&2
  exit 2
}
ninja_bin="${NINJA_BIN:-$(dirname "$cmake_bin")/ninja}"
[[ -x "$ninja_bin" ]] || {
  echo "Ninja was not found next to $cmake_bin" >&2
  exit 2
}

prebuilt_dir="$cache_root/prebuilt-$prebuilt_revision"
librime_dir="$cache_root/librime-$librime_revision"
mkdir -p "$cache_root"

fetch_revision() {
  local url="$1" revision="$2" directory="$3"
  if [[ -d "$directory/.git" ]] && [[ "$(git -C "$directory" rev-parse HEAD 2>/dev/null || true)" == "$revision" ]]; then
    return
  fi
  if [[ -e "$directory" ]]; then
    echo "cache path exists but is not the requested checkout: $directory" >&2
    exit 2
  fi
  git init -q "$directory"
  git -C "$directory" remote add origin "$url"
  git -C "$directory" fetch --depth 1 origin "$revision"
  git -C "$directory" checkout -q --detach FETCH_HEAD
}

fetch_revision "$prebuilt_url" "$prebuilt_revision" "$prebuilt_dir"
fetch_revision "$librime_url" "$librime_revision" "$librime_dir"

readelf_bin="$(find "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" -name llvm-readelf -print -quit)"
strip_bin="$(find "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" -name llvm-strip -print -quit)"
[[ -x "$readelf_bin" && -x "$strip_bin" ]] || {
  echo "llvm-readelf and llvm-strip were not found in $ANDROID_NDK_HOME" >&2
  exit 2
}

abis=(arm64-v8a armeabi-v7a x86_64)
for abi in "${abis[@]}"; do
  for dependency in boost yaml-cpp leveldb marisa opencc; do
    [[ -d "$prebuilt_dir/$dependency/$abi" ]] || {
      echo "missing $dependency prebuilt prefix for $abi" >&2
      exit 2
    }
  done

  build_dir="$cache_root/build-librime-$librime_revision-$abi"
  stage_dir="$cache_root/stage-librime-$librime_revision-$abi"
  mkdir -p "$build_dir" "$stage_dir/include" "$stage_dir/lib"

  find_root="$prebuilt_dir/boost/$abi;$prebuilt_dir/yaml-cpp/$abi;$prebuilt_dir/leveldb/$abi;$prebuilt_dir/marisa/$abi;$prebuilt_dir/opencc/$abi;."
  "$cmake_bin" -S "$librime_dir" -B "$build_dir" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DCMAKE_MAKE_PROGRAM="$ninja_bin" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_FIND_ROOT_PATH="$find_root" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="$android_api" \
    -DANDROID_STL=c++_static \
    -DBUILD_SHARED_LIBS=ON \
    -DBUILD_STATIC=ON \
    -DBUILD_TEST=OFF \
    -DBUILD_DATA=OFF \
    -DBUILD_SAMPLE=OFF \
    -DBUILD_SEPARATE_LIBS=OFF \
    -DENABLE_LOGGING=OFF
  "$cmake_bin" --build "$build_dir" --target rime --parallel "$jobs"

  cp "$build_dir/lib/librime.so" "$stage_dir/lib/librime.so"
  cp \
    "$librime_dir/src/rime_api.h" \
    "$librime_dir/src/rime_api_deprecated.h" \
    "$librime_dir/src/rime_api_stdbool.h" \
    "$librime_dir/src/rime_levers_api.h" \
    "$stage_dir/include/"
  "$strip_bin" --strip-unneeded "$stage_dir/lib/librime.so"

  case "$abi" in
    arm64-v8a) expected_machine="AArch64" ;;
    armeabi-v7a) expected_machine="ARM" ;;
    x86_64) expected_machine="Advanced Micro Devices X86-64" ;;
  esac
  header="$($readelf_bin -h "$stage_dir/lib/librime.so")"
  grep -F "Machine:" <<<"$header" | grep -F "$expected_machine" >/dev/null || {
    echo "$abi librime has the wrong ELF machine" >&2
    exit 1
  }
  dynamic="$($readelf_bin -d "$stage_dir/lib/librime.so")"
  grep -F 'Library soname: [librime.so]' <<<"$dynamic" >/dev/null || {
    echo "$abi librime has an unexpected SONAME" >&2
    grep -F 'SONAME' <<<"$dynamic" >&2 || true
    exit 1
  }
  if grep -E 'Shared library: \[/|Shared library: \[[A-Za-z]:[/\\]' <<<"$dynamic"; then
    echo "$abi librime contains an absolute DT_NEEDED path" >&2
    exit 1
  fi
  symbols="$($readelf_bin -Ws "$stage_dir/lib/librime.so")"
  grep -Eq '[[:space:]]rime_get_api$' <<<"$symbols" || {
    echo "$abi librime does not export rime_get_api" >&2
    exit 1
  }

  mkdir -p "$output_root/$abi/include" "$output_root/$abi/lib"
  cp \
    "$stage_dir/include/rime_api.h" \
    "$stage_dir/include/rime_api_deprecated.h" \
    "$stage_dir/include/rime_api_stdbool.h" \
    "$stage_dir/include/rime_levers_api.h" \
    "$output_root/$abi/include/"
  cp "$stage_dir/lib/librime.so" "$output_root/$abi/lib/librime.so"
  printf '%s: ' "$abi"
  grep -E 'SONAME|NEEDED' <<<"$dynamic" | sed 's/^[[:space:]]*//'
done

{
  echo "librime_revision=$librime_revision"
  echo "prebuilt_revision=$prebuilt_revision"
  echo "android_ndk=$(basename "$ANDROID_NDK_HOME")"
  echo "android_api=$android_api"
  echo "cmake=$($cmake_bin --version | head -n 1)"
  for abi in "${abis[@]}"; do
    sha256sum "$output_root/$abi/lib/librime.so"
  done
} >"$output_root/BUILD-METADATA.txt"
cp "$prebuilt_dir/toolchain-versions.json" "$output_root/UPSTREAM-TOOLCHAIN-VERSIONS.json"
cp "$librime_dir/LICENSE" "$output_root/LICENSE.librime.txt"

echo "Android librime prefixes are ready: $output_root"
