#!/usr/bin/env bash
# Copyright © 2026 立方田 <managecode@gmail.com>
set -euo pipefail
test -d /registry-source
if test -n "${ZHIMO_TARGET_DISTRO:-}"; then
  case "$ZHIMO_TARGET_DISTRO" in
    debian12) expected='debian 12' ;;
    debian13) expected='debian 13' ;;
    ubuntu2204) expected='ubuntu 22.04' ;;
    ubuntu2404) expected='ubuntu 24.04' ;;
    *) echo 'Unknown target distribution' >&2; exit 2 ;;
  esac
  . /etc/os-release
  test "$ID $VERSION_ID" = "$expected" || { echo 'Base image does not match target distribution' >&2; exit 2; }
fi
export CARGO_BUILD_JOBS=2
mkdir -p /cargo
cp -a /registry-source /cargo/registry
cargo build --offline --locked --release -p ime-ffi --features native-librime
triplet=$(dpkg-architecture -qDEB_HOST_MULTIARCH)
cmake -S platform/linux-fcitx5 -B /out/fcitx -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX=/usr -DCMAKE_INSTALL_LIBDIR="lib/$triplet" \
  -DZHIMO_IME_LIBRARY=/out/cargo/release/libime_ffi.so
cmake --build /out/fcitx -j2
DESTDIR=/out/stage cmake --install /out/fcitx
cmake -S platform/shared-speech -B /out/shared-speech -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX=/usr -DCMAKE_INSTALL_LIBDIR="lib/$triplet" \
  -DWHISPER_SOURCE=/src/target/vendor/whisper.cpp-1.9.1
cmake --build /out/shared-speech -j2
ctest --test-dir /out/shared-speech --output-on-failure
DESTDIR=/out/stage cmake --install /out/shared-speech
python3 -B platform/linux-ibus/test_keyboard.py
python3 -B tools/test-desktop-case.py
python3 -B platform/linux-ibus/test_desktop.py
python3 -B platform/linux-ibus/test_desktop_support.py
xvfb-run -a python3 -B platform/linux-ibus/test_desktop_gui.py /out/cargo/release/libime_ffi.so
python3 tools/verify-linux-rime.py --library /out/cargo/release/libime_ffi.so \
  --shared platform/android-ime/app/src/main/assets/rime
package_version="${ZHIMO_DEB_VERSION:-0.1.0~beta.20260914}${ZHIMO_TARGET_DISTRO:+.$ZHIMO_TARGET_DISTRO}"
package_output=${ZHIMO_PACKAGE_OUTPUT:-/out/packages}
python3 tools/package-linux-deb.py --stage /out/stage --output "$package_output" \
  --version "$package_version" --max-glibc "$GLIBC_CEILING"
# These writes occur only inside the disposable builder, never on the host.
dpkg -i "$package_output"/*.deb
python3 -c 'import ctypes, sys; ctypes.CDLL(sys.argv[1]); print("Installed Fcitx5 plugin dependency loading OK")' \
  "/usr/lib/$triplet/fcitx5/libzhimo.so"
python3 tools/verify-linux-rime.py --installed
dpkg --remove zhimo-ibus fcitx5-zhimo zhimo-core
