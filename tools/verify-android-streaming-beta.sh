#!/usr/bin/env bash
# Copyright © 2026 立方田 <managecode@gmail.com>
# Compatibility entry point: all Android builds now use streaming speech.
set -euo pipefail
root=$(cd -- "$(dirname -- "$0")/.." && pwd)
exec bash "$root/tools/verify-android-beta.sh" "$@"
