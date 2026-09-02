#include "core_session.h"

#include <filesystem>
#include <iostream>

int main() {
  shurufa::CoreSession session(std::filesystem::temp_directory_path() / "shurufa-tsf-probe");
  if (!session.valid() || !session.set_context("tsf.probe", true) ||
      !session.switch_engine("pinyin.reference") || !session.feed("nihao") ||
      session.actions().find("你好") == std::string::npos) {
    return 1;
  }
  const auto actions = session.structured_actions();
  bool found_candidate = false;
  for (const auto& action : actions) {
    found_candidate = found_candidate ||
                      (action.kind == 2 && !action.candidates.empty() &&
                       action.candidates.front().commit == "你好");
  }
  if (!found_candidate || !session.command(2) || !session.flush()) return 1;
  std::cout << "TSF core boundary probe passed\n";
  return 0;
}
