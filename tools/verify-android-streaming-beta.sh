#!/usr/bin/env bash
# Copyright © 2026 立方田 <managecode@gmail.com>
# Optional experimental package; does not change the default Whisper engine.
set -euo pipefail
root=$(cd -- "$(dirname -- "$0")/.." && pwd)
source "$root/tools/android-env.sh"
export PATH="$JAVA_HOME/bin:$PATH"
python3 "$root/tools/prepare-streaming-speech-model.py" --model zipformer
bash "$root/tools/build-streaming-speech-android.sh"
python3 "$root/tools/package-streaming-speech.py"
python3 "$root/tools/test-streaming-speech-runtime.py"
python3 "$root/tools/test-streaming-speech-evaluation.py"
# Existing Rust/Rime, handwriting and Whisper native inputs must be prepared first
# with tools/verify-android-beta.sh. Gradle validates bundled models as usual.
cd "$root/platform/android-ime"
./gradlew --no-daemon '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8' \
  -Pzhimo.streamingSpeech=true :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest
bash "$root/tools/verify-android-apk.sh" app/build/outputs/apk/debug/app-debug.apk --require-rime
python3 "$root/tools/verify-streaming-speech-apk.py" app/build/outputs/apk/debug/app-debug.apk
echo 'Experimental APK built; enable streaming in settings. Phone validation has NOT run.'
