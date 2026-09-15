#!/usr/bin/env bash
# Copyright © 2026 立方田 <managecode@gmail.com>
set -euo pipefail

: "${WHISPER_CPP_BIN:?path to whisper-cli is required}"
: "${WHISPER_CPP_MODEL:?path to a ggml Whisper model is required}"
: "${WHISPER_CPP_TEST_WAV:?path to a PCM16 test WAV is required}"

test -x "$WHISPER_CPP_BIN"
test -f "$WHISPER_CPP_MODEL"
test -f "$WHISPER_CPP_TEST_WAV"

cargo test -p ime-speech --test whisper_cpp -- --ignored --nocapture
cargo test -p ime-ffi speech_abi_transcribes_real_audio_into_input_actions -- --ignored --nocapture
