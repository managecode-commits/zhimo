// Copyright © 2026 立方田 <managecode@gmail.com>
#pragma once
#include <windows.h>

namespace zhimo {
// Thread-affine lease: never waits on another application's UI thread.
// Named mutex abandonment releases ownership if a process/thread crashes.
class StatusBarLease {
 public:
  explicit StatusBarLease(const wchar_t* name = L"Local\\Zhimo.FloatingStatusBar.Visible.v2") : name_(name) {}
  StatusBarLease(const StatusBarLease&) = delete;
  StatusBarLease& operator=(const StatusBarLease&) = delete;
  ~StatusBarLease() { Release(); if (mutex_) CloseHandle(mutex_); }
  bool Acquire() {
    if (held_) return true;
    // A Win32 mutex is recursive. Do not let another object on the same
    // thread acquire the same visibility lease recursively.
    if (owner_ && owner_ != this) return false;
    if (!mutex_) mutex_ = CreateMutexW(nullptr, FALSE, name_);
    if (!mutex_) return false;
    const DWORD result = WaitForSingleObject(mutex_, 0);
    if (result != WAIT_OBJECT_0 && result != WAIT_ABANDONED) return false;
    held_ = true; owner_ = this;
    return true;
  }
  void Release() {
    if (held_) { ReleaseMutex(mutex_); held_ = false; }
    if (owner_ == this) owner_ = nullptr;
  }
 private:
  inline static thread_local StatusBarLease* owner_ = nullptr;
  const wchar_t* name_;
  HANDLE mutex_ = nullptr;
  bool held_ = false;
};
} // namespace zhimo
