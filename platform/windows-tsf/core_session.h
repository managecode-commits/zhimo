#pragma once

#include <filesystem>
#include <string>
#include <string_view>
#include <vector>

struct ImeHandle;

namespace shurufa {
struct Candidate final {
  std::string id;
  std::string display;
  std::string commit;
  std::string annotation;
};

struct Action final {
  unsigned int kind = 0;
  std::string text;
  std::vector<Candidate> candidates;
};

class CoreSession final {
 public:
  explicit CoreSession(const std::filesystem::path& data_directory);
  ~CoreSession();
  CoreSession(const CoreSession&) = delete;
  CoreSession& operator=(const CoreSession&) = delete;
  CoreSession(CoreSession&& other) noexcept;
  CoreSession& operator=(CoreSession&& other) noexcept;

  [[nodiscard]] bool valid() const noexcept;
  [[nodiscard]] bool feed(std::string_view utf8);
  [[nodiscard]] bool command(unsigned int command);
  [[nodiscard]] bool switch_engine(std::string_view engine_id);
  [[nodiscard]] bool set_password_scope(bool enabled);
  [[nodiscard]] bool set_input_scope(unsigned int scope);
  [[nodiscard]] bool set_context(std::string_view application_id, bool learning_allowed,
                                 bool network_allowed = false);
  [[nodiscard]] bool select(std::string_view candidate_id);
  [[nodiscard]] std::string actions() const;
  [[nodiscard]] std::vector<Action> structured_actions();
  [[nodiscard]] bool flush() const;

 private:
  ImeHandle* handle_ = nullptr;
};
}  // namespace shurufa
