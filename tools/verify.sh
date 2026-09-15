#!/usr/bin/env bash
# Copyright © 2026 立方田 <managecode@gmail.com>
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

cargo fmt --all -- --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked
cargo build -p ime-ffi --locked

python3 tools/test_stage_linux_ibus.py
python3 tools/test-release-contract.py
python3 tools/sync-curated-lexicon.py --check

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
