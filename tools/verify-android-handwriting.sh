#!/usr/bin/env bash
# Run on an explicitly selected, dedicated device. APK contains the model; no downloads.
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo_dir/tools/android-env.sh"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a dedicated, authorized Android test device}"
[[ $# == 0 ]] || { echo "Usage: ANDROID_SERIAL=... $0 (no model download needed)" >&2; exit 2; }
test "$(adb get-state)" = device
run_test() {
  local test_output
  test_output="$(adb shell am instrument -w "$@" dev.shurufa.ime.test/androidx.test.runner.AndroidJUnitRunner)"
  printf '%s\n' "$test_output"
  # am instrument may return exit code zero despite JUnit failure or a process crash.
  [[ "$test_output" =~ OK\ \([0-9]+\ tests?\) ]] || return 1
  [[ "$test_output" != *FAILURES* && "$test_output" != *INSTRUMENTATION_FAILED* ]] || return 1
}
run_test -e requireNativeRime true -e runKeyboardUi true \
  -e class dev.shurufa.ime.HandwritingSessionTest,dev.shurufa.ime.HandwritingPanelTest,dev.shurufa.ime.RuntimeSmokeTest,dev.shurufa.ime.KeyboardInteractionTest
run_test -e class dev.shurufa.ime.HandwritingModelTest
run_test -e runKeyboardUi true -e requireNativeRime true -e runRealInk true -e runLine true \
  -e class dev.shurufa.ime.KeyboardInteractionTest
run_test -e class dev.shurufa.ime.HandwritingImageModelTest,dev.shurufa.ime.LineHandwritingTest
run_test -e runKeyboardUi true -e requireNativeRime true -e runRealInk true -e runImageInk true -e runLine true \
  -e class dev.shurufa.ime.KeyboardInteractionTest
