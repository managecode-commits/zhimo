// Copyright © 2026 立方田 <managecode@gmail.com>
#include "core_session.h"
#include "input_policy.h"
#ifdef _WIN32
#include "language_bar_probe.h"
#include "dll_probe.h"
#include "status_bar.h"
#include <thread>
#include "status_bar_lease_probe.h"
#endif

#include <filesystem>
#include <iostream>

int main() {
#ifdef _WIN32
  if (!ProbeStatusBarLease()) {
    std::cerr << "Status bar visibility lease regression failed\n";
    return 1;
  }
  for (int dpi : {96, 120, 144, 192}) {
    for (int cell = 0; cell < 5; ++cell) {
      if (zhimo::StatusBar::HitTest(MulDiv(cell * 36 + 18, dpi, 96), MulDiv(17, dpi, 96), dpi) != cell) return 1;
      if (zhimo::StatusBar::HitTest(MulDiv(18, dpi, 96), MulDiv(cell * 34 + 17, dpi, 96), dpi, true) != cell) return 1;
    }
    if (zhimo::StatusBar::HitTest(-1, 0, dpi) != -1 ||
        zhimo::StatusBar::HitTest(MulDiv(180, dpi, 96), 0, dpi) != -1 ||
        zhimo::StatusBar::HitTest(0, MulDiv(34, dpi, 96), dpi) != -1) return 1;
    if (zhimo::StatusBar::HitTest(-1, 0, dpi, true) != -1 ||
        zhimo::StatusBar::HitTest(MulDiv(36, dpi, 96), 0, dpi, true) != -1 ||
        zhimo::StatusBar::HitTest(0, MulDiv(170, dpi, 96), dpi, true) != -1) return 1;
  }
  if (!ProbeDllExports()) {
    std::cerr << "TSF DLL loading / COM export ABI probe failed\n";
    return 1;
  }
  std::cout << "TSF DLL loaded and COM class factory callable (no registration)\n";
  if (!ProbeLanguageBar()) {
    std::cerr << "TSF language bar COM/icon/state probe failed\n";
    return 1;
  }
  std::cout << "TSF language bar COM/icon/state probe passed (not a shell UI test)\n";
#endif
  static_assert(zhimo::IsLiteralBoundaryKey('0'));
  static_assert(zhimo::IsLiteralBoundaryKey(0xbd)); // OEM minus / underscore
  static_assert(zhimo::IsLiteralBoundaryKey(0x60)); // numeric keypad
  static_assert(!zhimo::IsLiteralBoundaryKey('A'));
  static_assert(zhimo::UseNativeCase(true, false, true));
  static_assert(zhimo::UseNativeCase(true, true, true)); // OS supplies lowercase.
  static_assert(zhimo::UseNativeCase(false, true, true));
  static_assert(!zhimo::UseNativeCase(false, false, true));
  static_assert(!zhimo::UseNativeCase(false, true, false)); // Shift+symbols unchanged.
  static_assert(!zhimo::IsLiteralBoundaryKey(0x08)); // backspace
  static_assert(zhimo::IsEmEditorDocument(L"EmEditor.exe", L"EmEditorView", true, true, false));
  static_assert(zhimo::IsEmEditorDocument(L"EMEDITOR.EXE", L"emeditorview", true, true, false));
  static_assert(!zhimo::IsEmEditorDocument(L"Other.exe", L"EmEditorView", true, true, false));
  static_assert(!zhimo::IsEmEditorDocument(L"EmEditor.exe", L"Password", true, true, false));
  static_assert(!zhimo::IsEmEditorDocument(L"EmEditor.exe", L"EmEditorView", false, true, false));
  static_assert(!zhimo::IsEmEditorDocument(L"EmEditor.exe", L"EmEditorView", true, false, false));
  static_assert(!zhimo::IsEmEditorDocument(L"EmEditor.exe", L"EmEditorView", true, true, true));
  static_assert(zhimo::MergeInputScope(0, 31) == 1);
  static_assert(zhimo::MergeInputScope(0, 63) == 1);
  static_assert(zhimo::MergeInputScope(5, 64) == 1);
  static_assert(zhimo::MergeInputScope(0, 65) == 1);
  static_assert(zhimo::MergeInputScope(0, 66) == 1);
  static_assert(zhimo::MergeInputScope(1, 61) == 1);
  static_assert(zhimo::MergeInputScope(0, 61) == 5);
  static_assert(zhimo::MergeInputScope(5, 1) == 5);
  static_assert(zhimo::MergeInputScope(5, 5) == 5);
  static_assert(zhimo::MergeInputScope(0, 5) == 2);
  static_assert(zhimo::MergeInputScope(0, 1) == 3);
  static_assert(zhimo::MergeInputScope(0, 0) == 0);
  zhimo::CoreSession session(std::filesystem::temp_directory_path() / "zhimo-tsf-probe");
  if (!session.valid() || !session.set_context("tsf.probe", true) ||
      !session.feed("n") || !session.feed("i") || !session.feed("h") ||
      !session.feed("a") || !session.feed("o") ||
      session.actions().find("你好") == std::string::npos) {
    std::cerr << "Default Pinyin initialization/keystroke regression failed\n";
    return 1;
  }
  const auto actions = session.structured_actions();
  bool found_candidate = false;
  for (const auto& action : actions) {
    found_candidate = found_candidate ||
                      (action.kind == 2 && !action.candidates.empty() &&
                       action.candidates.front().commit == "你好");
  }
  if (!found_candidate || !session.command(2)) return 1;
  bool committed = false;
  for (const auto& action : session.structured_actions()) {
    if (action.kind == 3 && action.text == "你好") committed = true;
  }
  if (!committed) return 1;
  // Mirror Shift+Space twice, then prove Pinyin candidate selection still works.
  if (!session.switch_engine("latin") || !session.feed("hello") ||
      !session.command(3) || !session.switch_engine("pinyin.reference") ||
      !session.feed("nihao")) return 1;
  std::string candidate_id;
  for (const auto& action : session.structured_actions()) {
    for (const auto& candidate : action.candidates) {
      if (candidate.commit == "你好") candidate_id = candidate.id;
    }
  }
  if (candidate_id.empty() || !session.select(candidate_id)) return 1;
  committed = false;
  for (const auto& action : session.structured_actions()) {
    if (action.kind == 3 && action.text == "你好") committed = true;
  }
  if (!committed || !session.flush()) return 1;
  std::cout << "TSF core probe passed: default Pinyin, per-key nihao, space commit, mode switches, candidate selection\n";
  return 0;
}
