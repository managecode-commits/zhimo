// Copyright © 2026 立方田 <managecode@gmail.com>
#ifndef ZHIMO_IME_H
#define ZHIMO_IME_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ImeHandle ImeHandle;
typedef struct ImeSpeechHandle ImeSpeechHandle;
typedef struct ImeHandwritingHandle ImeHandwritingHandle;

enum {
  ZHIMO_CAP_TEXT_INPUT = 1ULL << 0,
  ZHIMO_CAP_PERSISTENT_LEARNING = 1ULL << 1,
  ZHIMO_CAP_SYSTEM_SPEECH_EVENTS = 1ULL << 2,
  ZHIMO_CAP_WHISPER_CPP = 1ULL << 3,
  ZHIMO_CAP_NATIVE_LIBRIME = 1ULL << 4,
  ZHIMO_CAP_STRUCTURED_ACTIONS = 1ULL << 5,
  ZHIMO_CAP_HANDWRITING = 1ULL << 6,
  ZHIMO_CAP_ASYNC_LEARNING = 1ULL << 7
};

unsigned int ime_runtime_abi_version(void);
unsigned long long ime_runtime_capabilities(void);

/* ABI 1.3. Bundled zh-CN single-character model. All calls on a handle must
 * be serialized. Call off the input/UI thread. No network or editor access.
 * JSON input: {width,height,strokes:[[{x,y,time_ms},...],...]}
 * Whole-character bounds are centered and uniformly scaled before recognition.
 * Output: up to 100 [{text,score},...]. Score is an uncalibrated SVM margin.
 * Returned UTF-8 is borrowed until the next recognition or free; NULL on error.
 * Hosts must implement editor/session/revision checks before text submission. */
ImeHandwritingHandle *ime_handwriting_new(const char *model_path);
const char *ime_handwriting_recognize_json(ImeHandwritingHandle *handle, const char *ink_json);
void ime_handwriting_free(ImeHandwritingHandle *handle);

ImeHandle *ime_runtime_new(void);
ImeHandle *ime_runtime_new_with_engine(const char *engine_id);
ImeHandle *ime_runtime_new_with_data_dir(const char *engine_id, const char *data_dir);
ImeHandle *ime_runtime_new_with_rime(const char *engine_id, const char *data_dir,
                                     const char *rime_shared_dir, const char *rime_user_dir);
void ime_runtime_free(ImeHandle *handle);
int ime_runtime_flush(const ImeHandle *handle);
/* Additive async persistence API. Runtime calls must be serialized; workers own
 * snapshots, never the handle. flush/free wait for pending writes and may block.
 * schedule: 0 accepted, -1 invalid, -3 unavailable. status: 0 saved, 1 pending,
 * -2 write failed, -3 read failed/unavailable. Retry with schedule or flush. */
int ime_runtime_schedule_flush(const ImeHandle *handle);
int ime_runtime_learning_status(const ImeHandle *handle);
int ime_runtime_feed_utf8(ImeHandle *handle, const char *text);
const char *ime_runtime_commit(ImeHandle *handle);
int ime_runtime_switch_engine(ImeHandle *handle, const char *engine_id);
int ime_runtime_set_input_scope(ImeHandle *handle, unsigned int scope);
int ime_runtime_set_privacy_policy(ImeHandle *handle, int learning_allowed,
                                   int network_allowed);
int ime_runtime_set_application_id(ImeHandle *handle, const char *application_id);
/* Commands: 0 backspace, 1 enter, 2 space, 3 escape, 4 left, 5 right,
 * 6 previous native candidate page, 7 next native candidate page. */
int ime_runtime_send_command(ImeHandle *handle, unsigned int command);
int ime_runtime_select_candidate(ImeHandle *handle, const char *candidate_id);
const char *ime_runtime_last_actions_json(const ImeHandle *handle);
size_t ime_runtime_action_count(const ImeHandle *handle);
/* Kinds: 1 composition, 2 candidates, 3 commit, 4 close, 5 ignored,
 * 6 candidate page metadata (index/has_next in last_actions_json),
 * 7 optional PinyinReadings metadata (readings/selected in last_actions_json).
 * To filter T9 candidates without committing, select "pinyin-reading:<reading>";
 * an empty reading restores automatic matching.
 * Hosts must ignore unknown action kinds for forward compatibility. */
unsigned int ime_runtime_action_kind(const ImeHandle *handle, size_t action_index);
const char *ime_runtime_action_text(ImeHandle *handle, size_t action_index);
size_t ime_runtime_candidate_count(const ImeHandle *handle, size_t action_index);
const char *ime_runtime_candidate_field(ImeHandle *handle, size_t action_index,
                                        size_t candidate_index, unsigned int field);
int ime_runtime_speech_result(ImeHandle *handle, const char *text, const char *language,
                              float confidence, int final_result);
int ime_runtime_feedback(const ImeHandle *handle, const char *candidate_id,
                         const char *input_signature, const char *committed_text,
                         unsigned int kind, const char *language);

ImeSpeechHandle *ime_speech_whisper_new(const char *executable, const char *model,
                                        const char *temporary_directory);
void ime_speech_free(ImeSpeechHandle *handle);
int ime_speech_start(ImeSpeechHandle *speech, const ImeHandle *ime, const char *languages);
int ime_speech_start_with_hotwords(ImeSpeechHandle *speech, const ImeHandle *ime,
                                   const char *languages, const char *hotwords);
int ime_speech_push_f32(ImeSpeechHandle *speech, const float *samples, size_t sample_count,
                        unsigned int sample_rate_hz, unsigned short channels);
int ime_speech_finish(ImeSpeechHandle *speech);
int ime_speech_cancel(ImeSpeechHandle *speech);
int ime_speech_poll_into_runtime(ImeSpeechHandle *speech, ImeHandle *ime);

#ifdef __cplusplus
}
#endif

#endif
