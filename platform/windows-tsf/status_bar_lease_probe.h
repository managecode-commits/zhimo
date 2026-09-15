// Copyright © 2026 立方田 <managecode@gmail.com>
#pragma once
#include "status_bar_lease.h"
#include <string>
#include <thread>

inline bool ProbeStatusBarLease() {
  const auto name = L"Local\\Zhimo.StatusBar.Probe." + std::to_wstring(GetCurrentProcessId());
  zhimo::StatusBarLease first(name.c_str()), second(name.c_str());
  if (!first.Acquire() || !first.Acquire() || second.Acquire()) return false;
  struct Request { const wchar_t* name; bool acquired; } request{name.c_str(), false};
  auto attempt = [](LPVOID data) -> DWORD {
    auto* r = static_cast<Request*>(data);
    zhimo::StatusBarLease lease(r->name);
    r->acquired = lease.Acquire();
    return 0;
  };
  // std::thread handles the calling convention on both x86 and x64.
  std::thread contender([&] { attempt(&request); }); contender.join();
  if (request.acquired) return false;
  first.Release();
  if (!second.Acquire()) return false;
  second.Release();
  std::thread next([&] { attempt(&request); }); next.join();
  if (!request.acquired) return false;
  // Keep a handle open while its owning worker exits without ReleaseMutex.
  HANDLE abandoned = CreateMutexW(nullptr, FALSE, name.c_str());
  if (!abandoned) return false;
  bool locked = false;
  std::thread exiting([&] { locked = WaitForSingleObject(abandoned, 0) == WAIT_OBJECT_0; });
  exiting.join();
  const bool recovered = locked && first.Acquire();
  first.Release(); CloseHandle(abandoned);
  return recovered;
}
