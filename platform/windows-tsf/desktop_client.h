// Copyright © 2026 立方田 <managecode@gmail.com>
#pragma once
#include <windows.h>
#include <functional>
#include <string>
#include <vector>

namespace zhimo {
class DesktopClient {
 public:
  ~DesktopClient() { Stop(); if (window_) DestroyWindow(window_); }
  void Stop() {
    if (window_) KillTimer(window_, 1);
    if (job_) { CloseHandle(job_); job_ = nullptr; }
    if (process_) { CloseHandle(process_); process_ = nullptr; }
    if (pipe_) { CloseHandle(pipe_); pipe_ = nullptr; }
    output_.clear(); callback_ = {};
  }
  bool Start(HINSTANCE module, const std::wstring &executable, bool speech,
             std::function<void(const std::string&)> callback) {
    Stop();
    if (!window_) {
      WNDCLASSW type{}; type.lpfnWndProc = Proc; type.hInstance = module;
      type.lpszClassName = L"Zhimo.DesktopIPC.v1";
      if (!RegisterClassW(&type) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) return false;
      window_ = CreateWindowW(type.lpszClassName, L"", 0, 0,0,0,0, HWND_MESSAGE, nullptr, module, this);
      if (!window_) return false;
    }
    SECURITY_ATTRIBUTES security{sizeof(security), nullptr, TRUE};
    HANDLE write = nullptr;
    if (!CreatePipe(&pipe_, &write, &security, 0)) return false;
    SetHandleInformation(pipe_, HANDLE_FLAG_INHERIT, 0);
    HANDLE null = CreateFileW(L"NUL", GENERIC_READ | GENERIC_WRITE,
        FILE_SHARE_READ | FILE_SHARE_WRITE, &security, OPEN_EXISTING, 0, nullptr);
    if (null == INVALID_HANDLE_VALUE) { CloseHandle(write); Stop(); return false; }
    SIZE_T size = 0;
    InitializeProcThreadAttributeList(nullptr, 1, 0, &size);
    std::vector<unsigned char> attributes(size);
    STARTUPINFOEXW startup{}; startup.StartupInfo.cb = sizeof(startup);
    startup.lpAttributeList = reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(attributes.data());
    if (!InitializeProcThreadAttributeList(startup.lpAttributeList, 1, 0, &size)) {
      CloseHandle(write); CloseHandle(null); Stop(); return false;
    }
    HANDLE inherited[] = {write, null};
    bool ok = UpdateProcThreadAttribute(startup.lpAttributeList, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
        inherited, sizeof(inherited), nullptr, nullptr) != FALSE;
    startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    startup.StartupInfo.hStdInput = startup.StartupInfo.hStdError = null;
    startup.StartupInfo.hStdOutput = write;
    std::wstring command = L"\"" + executable + (speech ? L"\" speech" : L"\" handwriting");
    PROCESS_INFORMATION process{};
    if (ok) ok = CreateProcessW(executable.c_str(), command.data(), nullptr, nullptr, TRUE,
        EXTENDED_STARTUPINFO_PRESENT | CREATE_NO_WINDOW | CREATE_SUSPENDED,
        nullptr, nullptr, &startup.StartupInfo, &process) != FALSE;
    DeleteProcThreadAttributeList(startup.lpAttributeList);
    CloseHandle(write); CloseHandle(null);
    if (!ok) { Stop(); return false; }
    process_ = process.hProcess;
    job_ = CreateJobObjectW(nullptr, nullptr);
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    if (!job_ || !SetInformationJobObject(job_, JobObjectExtendedLimitInformation, &limits, sizeof(limits)) ||
        !AssignProcessToJobObject(job_, process_)) {
      TerminateProcess(process_, 1); CloseHandle(process.hThread); Stop(); return false;
    }
    callback_ = std::move(callback);
    started_ = GetTickCount64();
    ResumeThread(process.hThread); CloseHandle(process.hThread);
    if (!SetTimer(window_, 1, 50, nullptr)) { Stop(); return false; }
    return true;
  }
 private:
  void Poll() {
    if (!process_) return;
    if (GetTickCount64() - started_ > 300000) { Stop(); return; }
    DWORD bytes = 0;
    while (PeekNamedPipe(pipe_, nullptr, 0, nullptr, &bytes, nullptr) && bytes) {
      char buffer[4096]; DWORD count = 0;
      if (!ReadFile(pipe_, buffer, bytes < sizeof(buffer) ? bytes : sizeof(buffer), &count, nullptr)) { Stop(); return; }
      output_.append(buffer, count);
      if (output_.size() > 16384) { Stop(); return; }
    }
    if (WaitForSingleObject(process_, 0) != WAIT_OBJECT_0) return;
    // Drain again on the next tick if data appeared while checking process exit.
    if (PeekNamedPipe(pipe_, nullptr, 0, nullptr, &bytes, nullptr) && bytes) return;
    DWORD status = 1; GetExitCodeProcess(process_, &status);
    auto text = output_; auto callback = callback_;
    Stop();
    if (status == 0 && !text.empty() && text.find('\0') == std::string::npos && callback) callback(text);
  }
  static LRESULT CALLBACK Proc(HWND window, UINT message, WPARAM wp, LPARAM lp) {
    auto* self = reinterpret_cast<DesktopClient*>(GetWindowLongPtrW(window, GWLP_USERDATA));
    if (message == WM_NCCREATE) {
      self = static_cast<DesktopClient*>(reinterpret_cast<CREATESTRUCTW*>(lp)->lpCreateParams);
      SetWindowLongPtrW(window, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(self));
    }
    if (message == WM_TIMER && self) { self->Poll(); return 0; }
    return DefWindowProcW(window, message, wp, lp);
  }
  HWND window_ = nullptr;
  HANDLE process_ = nullptr, pipe_ = nullptr, job_ = nullptr;
  ULONGLONG started_ = 0;
  std::string output_;
  std::function<void(const std::string&)> callback_;
};
}
