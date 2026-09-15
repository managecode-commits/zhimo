// Copyright © 2026 立方田 <managecode@gmail.com>
#include "core_session.h"

#include "zhimo_ime.h"

#include <utility>

namespace zhimo {
CoreSession::CoreSession(const std::filesystem::path& data_directory) {
  const auto native_utf8 = data_directory.u8string();
  const std::string directory(native_utf8.begin(), native_utf8.end());
  handle_ = ime_runtime_new_with_data_dir("bilingual", directory.c_str());
  // The bilingual Runtime defaults to Latin, while the Windows service starts
  // with pinyin_=true. Establish the same mode before accepting the first key.
  if (handle_ && ime_runtime_switch_engine(handle_, "pinyin.reference") != 0) {
    ime_runtime_free(handle_);
    handle_ = nullptr;
  }
}

CoreSession::~CoreSession() {
  if (handle_) {
    (void)ime_runtime_flush(handle_);
    ime_runtime_free(handle_);
  }
}

CoreSession::CoreSession(CoreSession&& other) noexcept
    : handle_(std::exchange(other.handle_, nullptr)) {}

CoreSession& CoreSession::operator=(CoreSession&& other) noexcept {
  if (this != &other) {
    if (handle_) ime_runtime_free(handle_);
    handle_ = std::exchange(other.handle_, nullptr);
  }
  return *this;
}

bool CoreSession::valid() const noexcept { return handle_ != nullptr; }
bool CoreSession::feed(std::string_view utf8) {
  const std::string value(utf8);
  return handle_ && ime_runtime_feed_utf8(handle_, value.c_str()) == 0;
}
bool CoreSession::command(unsigned int command_value) {
  return handle_ && ime_runtime_send_command(handle_, command_value) == 0;
}
bool CoreSession::switch_engine(std::string_view engine_id) {
  const std::string value(engine_id);
  return handle_ && ime_runtime_switch_engine(handle_, value.c_str()) == 0;
}
bool CoreSession::set_password_scope(bool enabled) {
  return set_input_scope(enabled ? 1U : 0U);
}
bool CoreSession::set_input_scope(unsigned int scope) {
  return handle_ && ime_runtime_set_input_scope(handle_, scope) == 0;
}
bool CoreSession::set_context(std::string_view application_id, bool learning_allowed,
                              bool network_allowed) {
  const std::string value(application_id);
  return handle_ && ime_runtime_set_application_id(handle_, value.c_str()) == 0 &&
         ime_runtime_set_privacy_policy(handle_, learning_allowed ? 1 : 0,
                                        network_allowed ? 1 : 0) == 0;
}
bool CoreSession::select(std::string_view candidate_id) {
  const std::string value(candidate_id);
  return handle_ && ime_runtime_select_candidate(handle_, value.c_str()) == 0;
}
std::string CoreSession::actions() const {
  const char* value = handle_ ? ime_runtime_last_actions_json(handle_) : nullptr;
  return value ? value : "[]";
}
std::vector<Action> CoreSession::structured_actions() {
  std::vector<Action> result;
  if (!handle_) return result;
  const auto action_count = ime_runtime_action_count(handle_);
  result.reserve(action_count);
  for (size_t action_index = 0; action_index < action_count; ++action_index) {
    Action action;
    action.kind = ime_runtime_action_kind(handle_, action_index);
    if (const char* value = ime_runtime_action_text(handle_, action_index)) {
      action.text = value;
    }
    const auto candidate_count = ime_runtime_candidate_count(handle_, action_index);
    action.candidates.reserve(candidate_count);
    for (size_t candidate_index = 0; candidate_index < candidate_count; ++candidate_index) {
      Candidate candidate;
      const char* fields[4] = {};
      for (unsigned int field = 0; field < 4; ++field) {
        fields[field] = ime_runtime_candidate_field(handle_, action_index, candidate_index, field);
        const std::string copied = fields[field] ? fields[field] : "";
        switch (field) {
          case 0: candidate.id = copied; break;
          case 1: candidate.display = copied; break;
          case 2: candidate.commit = copied; break;
          case 3: candidate.annotation = copied; break;
        }
      }
      action.candidates.push_back(std::move(candidate));
    }
    result.push_back(std::move(action));
  }
  return result;
}
bool CoreSession::flush() const { return handle_ && ime_runtime_flush(handle_) == 0; }
}  // namespace zhimo
