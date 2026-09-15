# Bundled offline speech

The Android APK contains the multilingual Whisper **base**, Q5_1 GGML model,
not the English-only `.en` variant. Source, revision, size and SHA-256 are pinned
in `manifest.json`. We distribute the converted weights unmodified.

- Model source: https://huggingface.co/ggerganov/whisper.cpp
- Model license: MIT, retained as `WHISPER-MODEL-LICENSE` (https://github.com/openai/whisper/blob/main/LICENSE).
- Runtime: https://github.com/ggml-org/whisper.cpp/tree/v1.9.1 ; MIT notice retained as `WHISPER-CPP-LICENSE` (includes the vendored ggml authors).

Run `bash tools/prepare-offline-speech.sh` before Android builds. Where the
primary host is unavailable, set `ZHIMO_MODEL_HOST=https://hf-mirror.com`; the
same pinned hash is mandatory. Downloads occur only on the build machine.
The binary is ignored by Git and must be recreated with the script after clone.
No phone-side model download, cloud fallback, or audio upload is used in this path.

Android installs the checked model atomically into app-private no-backup storage
on first use. CPU inference uses two threads, PCM16 mono capture at 16 kHz, a
60-second recording bound, and cooperative cancellation / a 120-second inference
deadline. Audio is kept in memory, never written as WAV. Cold model initialization
is not interruptible, but cancelled requests cannot submit results.

This is stop-to-transcribe batch dictation, not streaming ASR. Chinese output can
contain simplified or traditional characters; no unverified normalization is
applied. Quantization, noise, accents, silence and hallucinations require a held-out
evaluation; the bundled model is not a guarantee of release-quality accuracy.
Only Android has this new built-in microphone integration. Desktop CLI still
requires its external executable/model configuration.
# 2026-09-14 语音优化补充

同时内置 Silero VAD v5.1.2（MIT，见 SILERO-LICENSE），固定来源和哈希在 manifest.json 中。当前为停止录音后的神经人声检测，不提供录音时自动断句。Android 默认确认后上屏；详见 docs/语音转录优化与验收.md。
