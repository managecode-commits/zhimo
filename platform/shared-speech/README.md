<!-- Copyright © 2026 立方田 &lt;managecode@gmail.com&gt; -->
# Shared offline speech

Android JNI and the desktop library compile the same `speech.cpp`. The C ABI
is declared in `include/zhimo_speech.h`; it is separate from Rust `ime_*` because
model inference should not be forced into every host application's IME process.

```bash
bash tools/prepare-offline-speech.sh
cmake -S platform/shared-speech -B target/shared-speech -DCMAKE_BUILD_TYPE=Release
cmake --build target/shared-speech --parallel 2
ctest --test-dir target/shared-speech --output-on-failure
```

On Windows select the matching CMake/MSVC toolchain. On Apple use the target SDK
and architecture. Source portability is **not** evidence of either platform's
build or device verification. The CPU implementation does not imply NPU support.

Call `create`, run `transcribe` on a worker, consume/free the returned UTF-8 text,
then `release`. `cancel` may run concurrently and is sticky. A new recording needs
a new session. The API accepts only bounded, normalized 16 kHz mono PCM. It does
not record audio, download models, access the network or commit text to an editor.
The host must verify the model's hash/license and enforce microphone permission,
password/private-field policy, focus revision and cancellation before committing.

The 120-second deadline bounds inference, not an uninterruptible model load.
Do not interpret the digital-silence gate as VAD or hallucination prevention for
all background noise. Chinese output may be traditional. Windows non-ASCII model
paths and target-device latency remain acceptance cases.

Optional public-fixture integration check (fixture preparation is described in
`docs/内置离线语音交付.md`):

```bash
python3 tools/verify-shared-speech.py \
  --library target/shared-speech/libzhimo_speech.so \
  --model models/speech/ggml-base-q5_1.bin \
  --english target/vendor/whisper.cpp-1.9.1/bindings/go/samples/jfk.wav \
  --chinese target/speech-downloads/zh-test.wav
```

This checks the actual shared library, English keyword output, Chinese-character
output and cancellation. It is not a WER/CER benchmark. Shipping a desktop
recording UI, secure per-platform IPC and editor handoff is still separate work.
