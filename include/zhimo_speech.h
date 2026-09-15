// Copyright © 2026 立方田 <managecode@gmail.com>
#pragma once
#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32) && defined(ZHIMO_SPEECH_EXPORTS)
#define ZHIMO_SPEECH_API __declspec(dllexport)
#else
#define ZHIMO_SPEECH_API
#endif
#ifdef __cplusplus
extern "C" {
#endif

enum zhimo_speech_status {
  ZHIMO_SPEECH_OK = 0,
  ZHIMO_SPEECH_INVALID = 1,
  ZHIMO_SPEECH_BUSY = 2,
  ZHIMO_SPEECH_CANCELLED = 3,
  ZHIMO_SPEECH_MODEL_ERROR = 4,
  ZHIMO_SPEECH_INFERENCE_ERROR = 5,
  ZHIMO_SPEECH_RESOURCE_ERROR = 6,
  ZHIMO_SPEECH_TIMEOUT = 7
};
ZHIMO_SPEECH_API uint32_t zhimo_speech_abi_version(void);
// A session is single-worker; cancellation is sticky. Release is safe while
// transcribe is running: in-flight work retains ownership and observes cancel.
ZHIMO_SPEECH_API uint64_t zhimo_speech_create(void);
ZHIMO_SPEECH_API void zhimo_speech_cancel(uint64_t session);
ZHIMO_SPEECH_API void zhimo_speech_release(uint64_t session);
// Synchronous, CPU-only, no networking or audio files. Caller supplies a verified
// local whisper model, 16 kHz mono float PCM [-1,1], 0.1..60 s, and zh/en/auto.
// On success *text is malloc-owned UTF-8 (including silence as ""); otherwise NULL.
// Model loading is not interruptible; inference checks cancellation and a 120 s
// deadline. Caller must run off the UI/IME event thread and reject stale results.
// A single model is cached process-wide. Call trim_cache on idle/memory pressure;
// simultaneous requests from different sessions return BUSY rather than queue.
ZHIMO_SPEECH_API int zhimo_speech_transcribe(uint64_t session, const char *model,
    const float *pcm, size_t count, const char *language, char **text);
// Optional local vocabulary hint (UTF-8, <= 2048 bytes), never surrounding editor text.
// vad_model may be empty to disable neural VAD. Old ABI remains available.
ZHIMO_SPEECH_API int zhimo_speech_transcribe_options(uint64_t session, const char *model,
    const float *pcm, size_t count, const char *language, const char *prompt,
    const char *vad_model, char **text);
// Non-blocking: false when inference owns the cache; caller may retry off-thread.
ZHIMO_SPEECH_API int zhimo_speech_trim_cache(void);
ZHIMO_SPEECH_API void zhimo_speech_text_free(char *text);

#ifdef __cplusplus
}
#endif
