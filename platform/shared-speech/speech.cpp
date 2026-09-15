// Copyright © 2026 立方田 <managecode@gmail.com>
#include "zhimo_speech.h"
#include <whisper.h>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

namespace {
struct Session {
  std::atomic<bool> cancelled{false};
  std::chrono::steady_clock::time_point deadline;
  std::mutex inference;
};
std::mutex sessions_mutex;
std::unordered_map<uint64_t, std::shared_ptr<Session>> sessions;
uint64_t next_id = 0;
std::mutex model_mutex;
std::unique_ptr<whisper_context, decltype(&whisper_free)> cached_model(nullptr, whisper_free);
std::string cached_path;
std::shared_ptr<Session> find(uint64_t id) {
  std::lock_guard<std::mutex> lock(sessions_mutex);
  auto it = sessions.find(id);
  return it == sessions.end() ? nullptr : it->second;
}
bool aborted(void *data) {
  auto *session = static_cast<Session *>(data);
  return session->cancelled.load() || std::chrono::steady_clock::now() >= session->deadline;
}
int copy_text(const std::string &value, char **out) {
  *out = static_cast<char *>(std::malloc(value.size() + 1));
  if (!*out) return ZHIMO_SPEECH_RESOURCE_ERROR;
  std::memcpy(*out, value.c_str(), value.size() + 1);
  return ZHIMO_SPEECH_OK;
}
}

uint32_t zhimo_speech_abi_version(void) { return 0x00010000; }

uint64_t zhimo_speech_create(void) try {
  std::lock_guard<std::mutex> lock(sessions_mutex);
  if (next_id == std::numeric_limits<uint64_t>::max()) return 0;
  const auto id = ++next_id;
  sessions.emplace(id, std::make_shared<Session>());
  return id;
} catch (...) { return 0; }

void zhimo_speech_cancel(uint64_t id) try {
  if (auto session = find(id)) session->cancelled.store(true);
} catch (...) {}

void zhimo_speech_release(uint64_t id) try {
  std::lock_guard<std::mutex> lock(sessions_mutex);
  auto it = sessions.find(id);
  if (it != sessions.end()) {
    it->second->cancelled.store(true);
    sessions.erase(it);
  }
} catch (...) {}

int zhimo_speech_transcribe(uint64_t id, const char *model, const float *pcm,
                           size_t count, const char *language, char **text) {
  return zhimo_speech_transcribe_options(id, model, pcm, count, language, "", "", text);
}

int zhimo_speech_trim_cache(void) {
  std::unique_lock<std::mutex> lock(model_mutex, std::try_to_lock);
  if (!lock.owns_lock()) return 0;
  cached_model.reset();
  cached_path.clear();
  return 1;
}

int zhimo_speech_transcribe_options(uint64_t id, const char *model, const float *pcm,
                           size_t count, const char *language, const char *prompt,
                           const char *vad_model, char **text) try {
  if (!text) return ZHIMO_SPEECH_INVALID;
  *text = nullptr;
  const auto session = find(id);
  if (!session || !model || !*model || !pcm || !language || count < 1600 || count > 960000)
    return ZHIMO_SPEECH_INVALID;
  if (!prompt || !vad_model || std::strlen(prompt) > 2048) return ZHIMO_SPEECH_INVALID;
  const std::string lang(language);
  if (lang != "zh" && lang != "en" && lang != "auto") return ZHIMO_SPEECH_INVALID;
  std::unique_lock<std::mutex> lock(session->inference, std::try_to_lock);
  if (!lock.owns_lock()) return ZHIMO_SPEECH_BUSY;
  session->deadline = std::chrono::steady_clock::now() + std::chrono::seconds(120);
  double energy = 0;
  for (size_t i = 0; i < count; ++i) {
    const float sample = pcm[i];
    if (!std::isfinite(sample) || std::abs(sample) > 1.0f) return ZHIMO_SPEECH_INVALID;
    energy += sample * sample;
  }
  if (aborted(session.get())) return session->cancelled.load() ? ZHIMO_SPEECH_CANCELLED : ZHIMO_SPEECH_TIMEOUT;
  // Digital silence gate, not speech VAD. No model is loaded for silence.
  if (energy / count < 0.000001) return copy_text("", text);
  std::unique_lock<std::mutex> model_lock(model_mutex, std::try_to_lock);
  if (!model_lock.owns_lock()) return ZHIMO_SPEECH_BUSY;
  auto cp = whisper_context_default_params();
  cp.use_gpu = false;
  if (!cached_model || cached_path != model) {
    cached_model.reset();
    cached_path.clear();
    cached_model.reset(whisper_init_from_file_with_params(model, cp));
    if (!cached_model) return ZHIMO_SPEECH_MODEL_ERROR;
    cached_path = model;
  }
  auto *ctx = cached_model.get();
  if (aborted(session.get())) return session->cancelled.load() ? ZHIMO_SPEECH_CANCELLED : ZHIMO_SPEECH_TIMEOUT;
  auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
  params.n_threads = 2;
  params.translate = false;
  params.language = lang.c_str();
  params.no_context = true;
  params.initial_prompt = *prompt ? prompt : nullptr;
  params.vad = *vad_model != '\0';
  params.vad_model_path = vad_model;
  params.no_timestamps = true;
  params.print_realtime = params.print_progress = params.print_timestamps = params.print_special = false;
  params.suppress_blank = true;
  params.suppress_nst = true;
  params.temperature_inc = 0.0f;
  params.abort_callback = aborted;
  params.abort_callback_user_data = session.get();
  const int result = whisper_full(ctx, params, pcm, static_cast<int>(count));
  if (aborted(session.get())) return session->cancelled.load() ? ZHIMO_SPEECH_CANCELLED : ZHIMO_SPEECH_TIMEOUT;
  if (result != 0) return ZHIMO_SPEECH_INFERENCE_ERROR;
  std::string value;
  for (int i = 0; i < whisper_full_n_segments(ctx); ++i)
    value += whisper_full_get_segment_text(ctx, i);
  return copy_text(value, text);
} catch (...) {
  if (text) *text = nullptr;
  return ZHIMO_SPEECH_RESOURCE_ERROR;
}

void zhimo_speech_text_free(char *text) { std::free(text); }
