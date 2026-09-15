#!/usr/bin/env bash
# Copyright © 2026 立方田 <managecode@gmail.com>
set -euo pipefail
root=$(cd -- "$(dirname -- "$0")/.." && pwd)
distro=${1:-debian12}
case "$distro" in
  debian12) base=debian:bookworm; glibc=2.36 ;;
  debian13) base=debian:trixie; glibc=2.41 ;;
  ubuntu2204) base=ubuntu:22.04; glibc=2.35 ;;
  ubuntu2404) base=ubuntu:24.04; glibc=2.39 ;;
  *) echo 'Use debian12, debian13, ubuntu2204 or ubuntu2404' >&2; exit 2 ;;
esac
# Optional official registry alternative, without changing Docker daemon settings.
base=${ZHIMO_LINUX_BASE_IMAGE:-$base}
test "$(uname -m)" = x86_64 || { echo 'This build wrapper currently supports x86_64 only' >&2; exit 2; }
toolchain=$(dirname "$(dirname "$(rustup which rustc)")")
cargo_cache=${CARGO_HOME:-$HOME/.cargo}
test -d "$cargo_cache/registry"
test -f "$root/target/vendor/whisper.cpp-1.9.1/include/whisper.h" && \
test -f "$root/models/speech/ggml-base-q5_1.bin" || {
  echo 'Run bash tools/prepare-offline-speech.sh before building full desktop packages.' >&2
  exit 2
}
mkdir -p "$root/target"
output=$(mktemp -d "$root/target/zhimo-$distro.XXXXXX")
source_head=$(git -C "$root" rev-parse HEAD)
source_dirty=false
if test -n "$(git -C "$root" status --porcelain)"; then source_dirty=true; fi
docker build --build-arg "BASE=$base" -t "zhimo-desktop-builder:$distro" -f "$root/packaging/linux/Dockerfile" "$root/packaging/linux"
docker run --rm --network none \
  --mount "type=bind,src=$root,dst=/src,readonly" \
  --mount "type=bind,src=$output,dst=/out" \
  --mount "type=bind,src=$toolchain,dst=/toolchain,readonly" \
  --mount "type=bind,src=$cargo_cache/registry,dst=/registry-source,readonly" \
  -e "GLIBC_CEILING=$glibc" -e "ZHIMO_SOURCE_HEAD=$source_head" -e "ZHIMO_SOURCE_DIRTY=$source_dirty" \
  -e "ZHIMO_TARGET_DISTRO=$distro" -e "ZHIMO_BASE_IMAGE_USED=$base" \
  "zhimo-desktop-builder:$distro" \
  bash /src/tools/build-linux-deb-container.sh
echo "Packages: $output/packages"
