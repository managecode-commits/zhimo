#include "core_session.h"

#include <filesystem>
#include <iostream>

int main() {
  shurufa::CoreSession session(std::filesystem::temp_directory_path() / "shurufa-tsf-probe");
  if (!session.valid() || !session.switch_engine("pinyin.reference") || !session.feed("nihao") ||
      session.actions().find("你好") == std::string::npos || !session.command(2) || !session.flush()) {
    return 1;
  }
  std::cout << "TSF core boundary probe passed\n";
  return 0;
}
