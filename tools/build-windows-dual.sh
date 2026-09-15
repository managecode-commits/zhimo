#!/usr/bin/env bash
# Copyright © 2026 立方田 <managecode@gmail.com>
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"
: "${MINGW_X64_ROOT:?Set MINGW_X64_ROOT to an extracted MinGW POSIX toolchain prefix}"
: "${MINGW_X86_ROOT:?Set MINGW_X86_ROOT to an extracted MinGW POSIX toolchain prefix}"
cmake_bin="${CMAKE:-cmake}"
output="${1:-$repo_dir/target/packages/zhimo-windows-dual-$(date +%Y%m%d-%H%M%S)}"
test ! -e "$output"
test ! -e "$output.zip"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$repo_dir/target}"
export TMPDIR="${TMPDIR:-$repo_dir/target/windows-build-tmp}"
mkdir -p "$TMPDIR"
export CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-2}"
for tuple in x86_64 i686; do
  if [[ "$tuple" == x86_64 ]]; then prefix="$MINGW_X64_ROOT"; else prefix="$MINGW_X86_ROOT"; fi
  compiler="$prefix/usr/bin/$tuple-w64-mingw32"
  test -x "$compiler-gcc-posix"
  rustup target list --installed | grep -Fx "$tuple-pc-windows-gnu" >/dev/null
  env "PATH=$prefix/usr/bin:$PATH" \
    "CARGO_TARGET_$(tr '[:lower:]' '[:upper:]' <<<"$tuple")_PC_WINDOWS_GNU_LINKER=$compiler-gcc-posix" \
    "CC_${tuple}_pc_windows_gnu=$compiler-gcc-posix" \
    "AR_${tuple}_pc_windows_gnu=$compiler-ar" \
    cargo build --locked --release -p ime-ffi --target "$tuple-pc-windows-gnu"
done
build="$repo_dir/target/windows-dual"
export PATH="$MINGW_X64_ROOT/usr/bin:$PATH"
"$cmake_bin" -S platform/shared-speech -B "$build/speech" -DCMAKE_SYSTEM_NAME=Windows \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_COMPILER="$MINGW_X64_ROOT/usr/bin/x86_64-w64-mingw32-gcc-posix" \
  -DCMAKE_CXX_COMPILER="$MINGW_X64_ROOT/usr/bin/x86_64-w64-mingw32-g++-posix"
"$cmake_bin" --build "$build/speech" -j2
for tuple in x86_64 i686; do
  extra=(-DZHIMO_BUILD_DESKTOP_PANEL=OFF)
  if [[ "$tuple" == x86_64 ]]; then
    prefix="$MINGW_X64_ROOT"
    extra=(-DZHIMO_BUILD_DESKTOP_PANEL=ON "-DZHIMO_SPEECH_LIBRARY=$build/speech/libzhimo_speech.dll.a")
  else prefix="$MINGW_X86_ROOT"; fi
  PATH="$prefix/usr/bin:$PATH" "$cmake_bin" -S platform/windows-tsf -B "$build/$tuple" \
    -DCMAKE_SYSTEM_NAME=Windows -DCMAKE_BUILD_TYPE=Release \
    "-DCMAKE_CXX_COMPILER=$prefix/usr/bin/$tuple-w64-mingw32-g++-posix" \
    "-DZHIMO_IME_LIBRARY=$CARGO_TARGET_DIR/$tuple-pc-windows-gnu/release/libime_ffi.dll.a" \
    "-DZHIMO_IME_RUNTIME_DLL=$CARGO_TARGET_DIR/$tuple-pc-windows-gnu/release/ime_ffi.dll" "${extra[@]}"
  PATH="$prefix/usr/bin:$PATH" "$cmake_bin" --build "$build/$tuple" -j2
done
python3 tools/package-windows-test.py --build "$build/x86_64" \
  --runtime "$CARGO_TARGET_DIR/x86_64-pc-windows-gnu/release/ime_ffi.dll" \
  --mingw-root "$MINGW_X64_ROOT" --speech-runtime "$build/speech/libzhimo_speech.dll" \
  --x86-build "$build/i686" --x86-runtime "$CARGO_TARGET_DIR/i686-pc-windows-gnu/release/ime_ffi.dll" \
  --x86-mingw-root "$MINGW_X86_ROOT" --output "$output"
