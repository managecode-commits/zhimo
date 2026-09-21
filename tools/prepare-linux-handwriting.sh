#!/usr/bin/env bash
# Copyright © 2026 立方田 <managecode@gmail.com>
set -euo pipefail
root=$(cd -- "$(dirname -- "$0")/.." && pwd)
case "${1:?Specify target distro}" in
  ubuntu2204) py=310 ;;
  debian12) py=311 ;;
  ubuntu2404) py=312 ;;
  debian13) py=313 ;;
  *) exit 2 ;;
esac
mkdir -p "$root/target/linux-handwriting-wheels/$py"
python3 -m pip download --only-binary=:all: --no-deps --require-hashes \
  --implementation cp --python-version "$py" --abi "cp$py" --abi abi3 --abi none \
  --platform manylinux_2_27_x86_64 --platform manylinux_2_28_x86_64 \
  --platform manylinux2014_x86_64 --platform manylinux_2_17_x86_64 \
  -r "$root/packaging/linux/handwriting-requirements.txt" \
  -d "$root/target/linux-handwriting-wheels/$py"
