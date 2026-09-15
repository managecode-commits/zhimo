// Copyright © 2026 立方田 <managecode@gmail.com>
#include "zhimo_speech.h"
#include <cmath>
#include <iostream>
#include <vector>

int main() {
  if (zhimo_speech_abi_version() != 0x00010000) return 9;
  auto session = zhimo_speech_create();
  if (!session) return 1;
  std::vector<float> samples(1600, 0);
  char *text = nullptr;
  auto run = [&](size_t count, const char *language) {
    return zhimo_speech_transcribe(session, "unused-model", samples.data(), count, language, &text);
  };
  if (run(1600, "en") != ZHIMO_SPEECH_OK || !text || *text) return 2;
  zhimo_speech_text_free(text);
  if (zhimo_speech_transcribe_options(session, "unused-model", samples.data(), 1600,
      "zh", nullptr, "", &text) != ZHIMO_SPEECH_INVALID || text) return 10;
  const std::string too_long(2049, 'a');
  if (zhimo_speech_transcribe_options(session, "unused-model", samples.data(), 1600,
      "zh", too_long.c_str(), "", &text) != ZHIMO_SPEECH_INVALID || text) return 11;
  if (!zhimo_speech_trim_cache()) return 12;
  if (run(1599, "en") != ZHIMO_SPEECH_INVALID || text) return 3;
  if (run(1600, "unsupported") != ZHIMO_SPEECH_INVALID || text) return 4;
  samples[0] = NAN;
  if (run(1600, "en") != ZHIMO_SPEECH_INVALID) return 5;
  samples[0] = 2;
  if (run(1600, "en") != ZHIMO_SPEECH_INVALID) return 6;
  samples[0] = 0;
  zhimo_speech_cancel(session);
  if (run(1600, "en") != ZHIMO_SPEECH_CANCELLED || text) return 7;
  zhimo_speech_release(session);
  if (run(1600, "en") != ZHIMO_SPEECH_INVALID || text) return 8;
  zhimo_speech_release(session);
  zhimo_speech_cancel(session);
  zhimo_speech_text_free(nullptr);
  std::cout << "Shared speech boundary probe passed\n";
}
