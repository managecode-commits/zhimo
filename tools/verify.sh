#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cargo build -p ime-ffi

cc -std=c11 -Wall -Wextra -Werror \
  -I include \
  platform/probes/c-abi-smoke.c \
  -L target/debug -lime_ffi \
  -o target/debug/c-abi-smoke

LD_LIBRARY_PATH="target/debug${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  target/debug/c-abi-smoke

if command -v c++ >/dev/null 2>&1; then
  c++ -std=c++17 -Wall -Wextra -Werror \
    -I include -I platform/windows-tsf \
    platform/windows-tsf/core_session.cpp platform/windows-tsf/probe.cpp \
    -L target/debug -lime_ffi -o target/debug/tsf-core-probe
  LD_LIBRARY_PATH="target/debug${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
    target/debug/tsf-core-probe
fi
