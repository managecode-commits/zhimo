#!/usr/bin/env bash
# Copyright © 2026 立方田 <managecode@gmail.com>
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
rime_root="${ANDROID_RIME_ROOT:-$repo_dir/target/android-rime}"

"$repo_dir/tools/build-android-librime.sh" "$rime_root"
ANDROID_RIME_ROOT="$rime_root" "$repo_dir/tools/verify-android-beta.sh"

apk_dir="$repo_dir/platform/android-ime/app/build/outputs/apk/debug"
cp "$apk_dir/app-debug.apk" "$apk_dir/app-debug-rime.apk"
sha256sum "$apk_dir/app-debug-rime.apk" >"$apk_dir/app-debug-rime.apk.sha256"
echo "Rime APK: $apk_dir/app-debug-rime.apk"
