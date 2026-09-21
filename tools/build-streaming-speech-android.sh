#!/usr/bin/env bash
# Copyright © 2026 立方田 <managecode@gmail.com>
set -euo pipefail
root=$(cd -- "$(dirname -- "$0")/.." && pwd)
source "$root/tools/android-env.sh"
work="$root/target/streaming-speech"
mkdir -p "$work/deps"
python3 "$root/tools/prepare-streaming-speech-runtime.py"
fetch() {
  local file=$1 hash=$2 url=$3
  if [[ ! -f "$file" ]]; then
    curl -fL --proto '=https' --proto-redir '=https' --retry 3 --retry-all-errors \
      --connect-timeout 15 --max-time 600 "$url" -o "$file.part"
    echo "$hash  $file.part" | sha256sum -c -
    mv "$file.part" "$file"
  fi
  echo "$hash  $file" | sha256sum -c -
}
fetch "$work/sherpa-source-full.tar.gz" 49ea0985748c35a16c1544e07cec13c43e5a76db83bbb6f42bae5c850c85d2e3 \
  https://codeload.github.com/k2-fsa/sherpa-onnx/tar.gz/refs/tags/v1.12.11
fetch "$work/deps/kaldi-native-fbank-1.21.3.tar.gz" d409eddae5a46dc796f0841880f489ff0728b96ae26218702cd438c28667c70e \
  https://codeload.github.com/csukuangfj/kaldi-native-fbank/tar.gz/refs/tags/v1.21.3
fetch "$work/deps/kaldi-decoder-0.2.6.tar.gz" b13c78b37495cafc6ef3f8a7b661b349c55a51abbd7f7f42f389408dcf86a463 \
  https://codeload.github.com/k2-fsa/kaldi-decoder/tar.gz/refs/tags/v0.2.6
fetch "$work/deps/simple-sentencepiece-0.7.tar.gz" 1748a822060a35baa9f6609f84efc8eb54dc0e74b9ece3d82367b7119fdc75af \
  https://codeload.github.com/pkufool/simple-sentencepiece/tar.gz/refs/tags/v0.7
fetch "$work/deps/cppjieba-sherpa-onnx-2024-04-19.tar.gz" 03e5264687f0efaef05487a07d49c3f4c0f743347bfbf825df4b30cc75ac5288 \
  https://codeload.github.com/csukuangfj/cppjieba/tar.gz/refs/tags/sherpa-onnx-2024-04-19
fetch "$work/deps/kissfft.zip" 497103e664168ebe39580b757adbe616f6cf85a16572af581ca7bc42d0ab13fd \
  https://codeload.github.com/mborgerding/kissfft/zip/febd4caeed32e33ad8b2e0bb5ea77542c40f18ec
fetch "$work/deps/kaldifst-1.7.13.tar.gz" f8dc15fdaf314d7c9c3551ad8c11ed15da0f34de36446798bbd1b90fa7946eb2 \
  https://codeload.github.com/k2-fsa/kaldifst/tar.gz/refs/tags/v1.7.13
fetch "$work/deps/openfst-sherpa-onnx-2024-06-13.tar.gz" f10a71c6b64d89eabdc316d372b956c30c825c7c298e2f20c780320e8181ffb6 \
  https://codeload.github.com/csukuangfj/openfst/tar.gz/refs/tags/sherpa-onnx-2024-06-13
fetch "$work/deps/eigen-3.4.0.tar.gz" 8586084f71f9bde545ee7fa6d00288b264a2b7ac3607b974e54d13e7162c1c72 \
  https://gitlab.com/libeigen/eigen/-/archive/3.4.0/eigen-3.4.0.tar.gz
mkdir -p "$work/source-full"
tar -xzf "$work/sherpa-source-full.tar.gz" -C "$work/source-full" --strip-components=1
cp "$work/deps/"*.tar.gz "$work/source-full/"
for archive in "$work/deps/"*.tar.gz; do
  name=$(basename "$archive" .tar.gz)
  mkdir -p "$work/deps/extracted/$name"
  tar -xzf "$archive" -C "$work/deps/extracted/$name" --strip-components=1
done
unzip -o "$work/deps/kissfft.zip" -d "$work/deps/extracted" >/dev/null
if [[ -n "${ZHIMO_ORT_AAR:-}" ]]; then
  ort_aar="$ZHIMO_ORT_AAR"
else
  ort_aar="$work/deps/onnxruntime-android-1.22.0.aar"
  ort_cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/com.microsoft.onnxruntime/onnxruntime-android/1.22.0"
  if [[ ! -f "$ort_aar" && -d "$ort_cache" ]]; then
    ort_candidate=$(rg --files --hidden --no-ignore "$ort_cache" | rg '/onnxruntime-android-1.22.0.aar$' | head -1 || true)
    if [[ -n "$ort_candidate" ]]; then cp "$ort_candidate" "$ort_aar"; fi
  fi
  # Fresh machines cannot rely on Gradle's cache before the native preBuild gate.
  fetch "$ort_aar" 04a4617a9c797cf49225595e45b5546081cb34c86ac817581141577d3b7dbfe2 \
    https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.22.0/onnxruntime-android-1.22.0.aar
fi
echo "04a4617a9c797cf49225595e45b5546081cb34c86ac817581141577d3b7dbfe2  $ort_aar" | sha256sum -c -
unzip -o "$ort_aar" 'headers/*' 'jni/*/libonnxruntime.so' -d "$work/ort-1.22" >/dev/null
cmake_bin=${CMAKE:-$ANDROID_HOME/cmake/3.22.1/bin/cmake}
export GIT_CEILING_DIRECTORIES="$root/target" # Do not mistake Zhimo's commit for sherpa's source commit.
abis=(arm64-v8a armeabi-v7a x86_64)
for abi in "${abis[@]}"; do
  export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$work/ort-1.22/headers"
  export SHERPA_ONNXRUNTIME_LIB_DIR="$work/ort-1.22/jni/$abi"
  "$cmake_bin" -S "$work/source-full" -B "$work/build-$abi" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
    -DFETCHCONTENT_SOURCE_DIR_KISSFFT="$work/deps/extracted/kissfft-febd4caeed32e33ad8b2e0bb5ea77542c40f18ec" \
    -DFETCHCONTENT_SOURCE_DIR_KALDI_NATIVE_FBANK="$work/deps/extracted/kaldi-native-fbank-1.21.3" \
    -DFETCHCONTENT_SOURCE_DIR_KALDI_DECODER="$work/deps/extracted/kaldi-decoder-0.2.6" \
    -DFETCHCONTENT_SOURCE_DIR_KALDIFST="$work/deps/extracted/kaldifst-1.7.13" \
    -DFETCHCONTENT_SOURCE_DIR_OPENFST="$work/deps/extracted/openfst-sherpa-onnx-2024-06-13" \
    -DFETCHCONTENT_SOURCE_DIR_EIGEN="$work/deps/extracted/eigen-3.4.0" \
    -DFETCHCONTENT_SOURCE_DIR_SIMPLE-SENTENCEPIECE="$work/deps/extracted/simple-sentencepiece-0.7" \
    -DFETCHCONTENT_SOURCE_DIR_CPPJIEBA="$work/deps/extracted/cppjieba-sherpa-onnx-2024-04-19" \
    -DSHERPA_ONNX_ENABLE_TTS=OFF -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF \
    -DSHERPA_ONNX_ENABLE_BINARY=OFF -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF \
    -DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF -DSHERPA_ONNX_ENABLE_C_API=OFF \
    -DSHERPA_ONNX_ENABLE_JNI=ON -DSHERPA_ONNX_ENABLE_PYTHON=OFF -DSHERPA_ONNX_ENABLE_TESTS=OFF
  "$cmake_bin" --build "$work/build-$abi" --target sherpa-onnx-jni -j2
done
echo 'ASR-only native build finished. Package/verify assets before building Android.'
