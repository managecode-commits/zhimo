#pragma once

#include <filesystem>
#include <string>
#include <string_view>

struct ImeHandle;

namespace shurufa {
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
  [[nodiscard]] bool select(std::string_view candidate_id);
  [[nodiscard]] std::string actions() const;
  [[nodiscard]] bool flush() const;

 private:
  ImeHandle* handle_ = nullptr;
};
}  // namespace shurufa
