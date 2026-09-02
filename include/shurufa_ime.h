#ifndef SHURUFA_IME_H
#define SHURUFA_IME_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ImeHandle ImeHandle;
typedef struct ImeSpeechHandle ImeSpeechHandle;

enum {
  SHURUFA_CAP_TEXT_INPUT = 1ULL << 0,
  SHURUFA_CAP_PERSISTENT_LEARNING = 1ULL << 1,
  SHURUFA_CAP_SYSTEM_SPEECH_EVENTS = 1ULL << 2,
  SHURUFA_CAP_WHISPER_CPP = 1ULL << 3,
  SHURUFA_CAP_NATIVE_LIBRIME = 1ULL << 4,
  SHURUFA_CAP_STRUCTURED_ACTIONS = 1ULL << 5
};

unsigned int ime_runtime_abi_version(void);
unsigned long long ime_runtime_capabilities(void);

ImeHandle *ime_runtime_new(void);
ImeHandle *ime_runtime_new_with_engine(const char *engine_id);
ImeHandle *ime_runtime_new_with_data_dir(const char *engine_id, const char *data_dir);
ImeHandle *ime_runtime_new_with_rime(const char *engine_id, const char *data_dir,
                                     const char *rime_shared_dir, const char *rime_user_dir);
void ime_runtime_free(ImeHandle *handle);
int ime_runtime_flush(const ImeHandle *handle);
int ime_runtime_feed_utf8(ImeHandle *handle, const char *text);
const char *ime_runtime_commit(ImeHandle *handle);
int ime_runtime_switch_engine(ImeHandle *handle, const char *engine_id);
int ime_runtime_set_input_scope(ImeHandle *handle, unsigned int scope);
int ime_runtime_set_privacy_policy(ImeHandle *handle, int learning_allowed,
                                   int network_allowed);
int ime_runtime_set_application_id(ImeHandle *handle, const char *application_id);
int ime_runtime_send_command(ImeHandle *handle, unsigned int command);
int ime_runtime_select_candidate(ImeHandle *handle, const char *candidate_id);
const char *ime_runtime_last_actions_json(const ImeHandle *handle);
size_t ime_runtime_action_count(const ImeHandle *handle);
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
