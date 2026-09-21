// Copyright © 2026 立方田 <managecode@gmail.com>
#pragma once
#include <string_view>

namespace zhimo {
constexpr bool UseNativeCase(bool caps_lock, bool shift, bool letter) {
  return caps_lock || (shift && letter);
}
// Win32 virtual-key values. Literal keys are forwarded to the host so its
// keyboard layout (including shifted symbols and dead keys) remains authoritative.
constexpr bool IsLiteralBoundaryKey(unsigned key) {
  return (key >= 0x30 && key <= 0x39) || (key >= 0x60 && key <= 0x6f) ||
         (key >= 0xba && key <= 0xc0) || (key >= 0xdb && key <= 0xdf) || key == 0xe2;
}
constexpr bool EqualAsciiInsensitive(std::wstring_view first, std::wstring_view second) {
  if (first.size() != second.size()) return false;
  for (size_t i = 0; i < first.size(); ++i) {
    const auto lower = [](wchar_t c) { return c >= L'A' && c <= L'Z' ? c + (L'a' - L'A') : c; };
    if (lower(first[i]) != lower(second[i])) return false;
  }
  return true;
}
constexpr bool IsEmEditorDocument(std::wstring_view executable, std::wstring_view window_class,
                                  bool same_process, bool related_to_context, bool password) {
  return same_process && related_to_context && !password &&
      EqualAsciiInsensitive(executable, L"EmEditor.exe") &&
      EqualAsciiInsensitive(window_class, L"EmEditorView");
}
// Stable Windows InputScope wire values, kept independent of Windows headers so
// privacy precedence can be regression-tested on every CI host.
constexpr unsigned MergeInputScope(unsigned current, int scope) {
  if (current == 1 || scope == 31 || (scope >= 63 && scope <= 66)) return 1;
  if (current == 5 || scope == 61) return 5;
  if (scope == 5) return 2;  // IS_EMAIL_SMTPEMAILADDRESS
  if (scope == 1) return 3;  // IS_URL
  return current;
}
}  // namespace zhimo
