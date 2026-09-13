// Test-only reference bridge. Compile against the pinned upstream BSD Zinnia sources.
#include "zinnia.h"
#include <iostream>
#include <memory>
#include <string>
int main(int argc, char **argv) {
  if (argc != 2) return 2;
  std::unique_ptr<zinnia::Recognizer> recognizer(zinnia::Recognizer::create());
  if (!recognizer->open(argv[1])) return 3;
  std::string line;
  while (std::getline(std::cin, line)) {
    std::unique_ptr<zinnia::Character> character(zinnia::Character::create());
    if (!character->parse(line.c_str())) return 4;
    std::unique_ptr<zinnia::Result> result(recognizer->classify(*character, 5));
    if (!result) return 5;
    for (size_t i = 0; i < result->size(); ++i) {
      if (i) std::cout << '\t';
      std::cout << result->value(i);
    }
    std::cout << '\n';
  }
}
