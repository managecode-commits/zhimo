#include "core_session.h"

#include "shurufa_ime.h"

#include <utility>

namespace shurufa {
CoreSession::CoreSession(const std::filesystem::path& data_directory) {
  const std::string directory = data_directory.string();
  handle_ = ime_runtime_new_with_data_dir("bilingual", directory.c_str());
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
  return handle_ && ime_runtime_set_input_scope(handle_, enabled ? 1U : 0U) == 0;
}
bool CoreSession::select(std::string_view candidate_id) {
  const std::string value(candidate_id);
  return handle_ && ime_runtime_select_candidate(handle_, value.c_str()) == 0;
}
std::string CoreSession::actions() const {
  const char* value = handle_ ? ime_runtime_last_actions_json(handle_) : nullptr;
  return value ? value : "[]";
}
bool CoreSession::flush() const { return handle_ && ime_runtime_flush(handle_) == 0; }
}  // namespace shurufa
