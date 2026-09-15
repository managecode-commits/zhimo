#!/usr/bin/env bash
# Copyright © 2026 立方田 <managecode@gmail.com>
set -euo pipefail

if [[ -z "${RIME_ROOT:-}" ]]; then
  echo "RIME_ROOT must point to a librime prefix containing include/rime_api.h and lib/librime." >&2
  exit 2
fi

test -f "$RIME_ROOT/include/rime_api.h"
test -d "$RIME_ROOT/lib"
test_data="${ZHIMO_RIME_TEST_DATA:-$RIME_ROOT/share/rime-data}"
if [[ ! -f "$test_data/default.yaml" && -f "$RIME_ROOT/data/minimal/default.yaml" ]]; then
  test_data="$RIME_ROOT/data/minimal"
fi
test -f "$test_data/default.yaml"

ZHIMO_RIME_TEST_DATA="$test_data" \
LD_LIBRARY_PATH="$RIME_ROOT/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  cargo test -p ime-engine-rime --features native-librime

ZHIMO_RIME_TEST_DATA="$test_data" \
LD_LIBRARY_PATH="$RIME_ROOT/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  cargo test -p ime-ffi --features native-librime
