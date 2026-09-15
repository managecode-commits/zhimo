// Copyright © 2026 立方田 <managecode@gmail.com>
#pragma once
#include <windows.h>
#include <windowsx.h>
#include <algorithm>
#include <functional>
#include <cstring>
#include <utility>
#include "status_bar_lease.h"

namespace zhimo {
// No-activate floating toolbar; the native TSF language-bar item stays available.
class StatusBar {
 public:
  ~StatusBar() { Close(); }
  static bool Enabled() { return Read(L"Visible", 1) != 0; }
  static void SetEnabled(bool enabled) { Write(L"Visible", enabled ? 1 : 0); }
  static bool Vertical() { return Read(L"Vertical", 0) != 0; }
  static void SetVertical(bool vertical) { Write(L"Vertical", vertical ? 1 : 0); }
  static int HitTest(int x, int y, int dpi, bool vertical = false) {
    dpi = std::max(96, dpi);
    for (int i = 0; i < 5; ++i) {
      RECT cell = CellRect(i, dpi, vertical);
      if (x >= cell.left && x < cell.right && y >= cell.top && y < cell.bottom) return i;
    }
    return -1;
  }
  void Close() {
    Hide();
    callback_ = {};
    if (window_) { DestroyWindow(window_); window_ = nullptr; }
  }
  void Hide() {
    wanted_ = false;
    if (window_) KillTimer(window_, kFocusTimer);
    Withdraw();
  }
  void Show(bool pinyin, std::function<void(unsigned, POINT)> callback) {
    pinyin_ = pinyin;
    vertical_ = Vertical();
    callback_ = std::move(callback);
    if (!Enabled()) { Hide(); return; }
    if (!ForegroundThread()) { Hide(); return; }
    if (!window_) {
      WNDCLASSW cls{};
      cls.lpfnWndProc = Proc;
      cls.hInstance = GetModuleHandleW(nullptr);
      cls.lpszClassName = L"Zhimo.FloatingStatusBar.v1";
      cls.hCursor = LoadCursorW(nullptr, MAKEINTRESOURCEW(32512));
      if (!RegisterClassW(&cls) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) return;
      window_ = CreateWindowExW(WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW | WS_EX_TOPMOST,
          cls.lpszClassName, L"Zhimo 状态栏（右键菜单，拖动 Z 移动）", WS_POPUP,
          0, 0, 1, 1, nullptr, nullptr, cls.hInstance, this);
      if (!window_) return;
    }
    wanted_ = true;
    // A TSF service is per thread, not a desktop singleton. A named mutex is
    // held only while visible; abandonment recovers if the owner process exits.
    // Win32 mutexes are recursive on the same thread. Prevent two service
    // objects on this thread from each believing they have exclusive ownership.
    if (visible_owner_ && visible_owner_ != this) visible_owner_->Hide();
    SetTimer(window_, kFocusTimer, 150, nullptr);
    HDC dc = GetDC(window_);
    dpi_ = dc ? GetDeviceCaps(dc, LOGPIXELSX) : 96;
    if (dc) ReleaseDC(window_, dc);
    using WindowDpi = UINT (WINAPI*)(HWND);
    auto address = GetProcAddress(GetModuleHandleW(L"user32.dll"), "GetDpiForWindow");
    WindowDpi window_dpi = nullptr;
    static_assert(sizeof(window_dpi) == sizeof(address));
    std::memcpy(&window_dpi, &address, sizeof(window_dpi));
    if (window_dpi) { const UINT value = window_dpi(window_); if (value) dpi_ = static_cast<int>(value); }
    RECT work{};
    SystemParametersInfoW(SPI_GETWORKAREA, 0, &work, 0);
    POINT point{static_cast<LONG>(Read(L"X", static_cast<DWORD>(work.right - Scale(200)))),
                static_cast<LONG>(Read(L"Y", static_cast<DWORD>(work.bottom - Scale(48))))};
    Place(point);
    RefreshVisibility();
    InvalidateRect(window_, nullptr, FALSE);
  }
 private:
  static constexpr UINT_PTR kFocusTimer = 0x5a01;
  inline static thread_local StatusBar* visible_owner_ = nullptr;
  static bool ForegroundThread() {
    DWORD process = 0;
    const DWORD thread = GetWindowThreadProcessId(GetForegroundWindow(), &process);
    return process == GetCurrentProcessId() && thread == GetCurrentThreadId();
  }
  void Withdraw() {
    if (window_ && GetCapture() == window_) ReleaseCapture();
    if (window_ && IsWindowVisible(window_)) ShowWindow(window_, SW_HIDE);
    // Release after hiding, so the next owner cannot overlap this window.
    lease_.Release();
    if (visible_owner_ == this) visible_owner_ = nullptr;
  }
  void RefreshVisibility() {
    if (!wanted_ || !ForegroundThread() || !Enabled()) { Withdraw(); return; }
    if (visible_owner_ && visible_owner_ != this) return;
    if (!lease_.Acquire()) return;
    visible_owner_ = this;
    // Recheck after acquiring: focus may change between the two operations.
    if (!ForegroundThread()) { Withdraw(); return; }
    if (!IsWindowVisible(window_)) ShowWindow(window_, SW_SHOWNOACTIVATE);
  }
  static RECT CellRect(int i, int dpi, bool vertical) {
    return vertical ? RECT{0, MulDiv(i * 34, dpi, 96), MulDiv(36, dpi, 96), MulDiv((i + 1) * 34, dpi, 96)}
                    : RECT{MulDiv(i * 36, dpi, 96), 0, MulDiv((i + 1) * 36, dpi, 96), MulDiv(34, dpi, 96)};
  }
  static constexpr const wchar_t* kSettings = L"Software\\Zhimo\\StatusBar";
  static DWORD Read(const wchar_t* name, DWORD fallback) {
    HKEY key = nullptr;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, kSettings, 0, KEY_QUERY_VALUE | KEY_WOW64_64KEY, &key) != ERROR_SUCCESS) return fallback;
    DWORD value = fallback, type = 0, size = sizeof(value);
    if (RegQueryValueExW(key, name, nullptr, &type, reinterpret_cast<BYTE*>(&value), &size) != ERROR_SUCCESS || type != REG_DWORD || size != sizeof(value)) value = fallback;
    RegCloseKey(key);
    return value;
  }
  static void Write(const wchar_t* name, DWORD value) {
    HKEY key = nullptr;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, kSettings, 0, nullptr, 0, KEY_SET_VALUE | KEY_WOW64_64KEY, nullptr, &key, nullptr) == ERROR_SUCCESS) {
      RegSetValueExW(key, name, 0, REG_DWORD, reinterpret_cast<const BYTE*>(&value), sizeof(value));
      RegCloseKey(key);
    }
  }
  int Scale(int n) const { return MulDiv(n, dpi_, 96); }
  void Place(POINT point) {
    const int width = Scale(vertical_ ? 36 : 180);
    const int height = Scale(vertical_ ? 170 : 34);
    MONITORINFO monitor{sizeof(MONITORINFO), {}, {}, 0};
    if (GetMonitorInfoW(MonitorFromPoint(point, MONITOR_DEFAULTTONEAREST), &monitor)) {
      point.x = std::clamp(point.x, monitor.rcWork.left, std::max(monitor.rcWork.left, monitor.rcWork.right - width));
      point.y = std::clamp(point.y, monitor.rcWork.top, std::max(monitor.rcWork.top, monitor.rcWork.bottom - height));
    }
    SetWindowPos(window_, HWND_TOPMOST, point.x, point.y, width, height, SWP_NOACTIVATE);
    HRGN region = CreateRoundRectRgn(0, 0, width + 1, height + 1, Scale(8), Scale(8));
    if (region && !SetWindowRgn(window_, region, TRUE)) DeleteObject(region);
  }
  void Paint() {
    PAINTSTRUCT paint{};
    HDC dc = BeginPaint(window_, &paint);
    RECT rect{}; GetClientRect(window_, &rect);
    HIGHCONTRASTW contrast{sizeof(HIGHCONTRASTW), 0, nullptr};
    SystemParametersInfoW(SPI_GETHIGHCONTRAST, sizeof(contrast), &contrast, 0);
    const bool accessible = (contrast.dwFlags & HCF_HIGHCONTRASTON) != 0;
    HBRUSH background = CreateSolidBrush(accessible ? GetSysColor(COLOR_WINDOW) : RGB(250,251,253));
    FillRect(dc, &rect, background); DeleteObject(background);
    SetBkMode(dc, TRANSPARENT);
    SetTextColor(dc, accessible ? GetSysColor(COLOR_WINDOWTEXT) : RGB(35,114,218));
    HFONT font = CreateFontW(-Scale(16), 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE,
        DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
        DEFAULT_PITCH, L"Microsoft YaHei UI");
    HGDIOBJ old = SelectObject(dc, font);
    const wchar_t* labels[] = {L"Z", pinyin_ ? L"中" : L"英", L"手", L"麦", L"⋯"};
    for (int i = 0; i < 5; ++i) {
      RECT cell = CellRect(i, dpi_, vertical_);
      if (i == hover_) {
        HBRUSH brush = CreateSolidBrush(accessible ? GetSysColor(COLOR_HIGHLIGHT) : RGB(225,237,253));
        FillRect(dc, &cell, brush); DeleteObject(brush);
      }
      SetTextColor(dc, accessible && i == hover_ ? GetSysColor(COLOR_HIGHLIGHTTEXT) : accessible ? GetSysColor(COLOR_WINDOWTEXT) : RGB(35,114,218));
      DrawTextW(dc, labels[i], -1, &cell, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    }
    SelectObject(dc, old); DeleteObject(font);
    EndPaint(window_, &paint);
  }
  static LRESULT CALLBACK Proc(HWND window, UINT message, WPARAM wp, LPARAM lp) {
    auto* self = reinterpret_cast<StatusBar*>(GetWindowLongPtrW(window, GWLP_USERDATA));
    if (message == WM_NCCREATE) {
      self = static_cast<StatusBar*>(reinterpret_cast<CREATESTRUCTW*>(lp)->lpCreateParams);
      self->window_ = window;
      SetWindowLongPtrW(window, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(self));
    }
    if (!self) return DefWindowProcW(window, message, wp, lp);
    switch (message) {
      case WM_MOUSEACTIVATE: return MA_NOACTIVATE;
      case WM_TIMER:
        if (wp == kFocusTimer) { self->RefreshVisibility(); return 0; }
        break;
      case WM_ERASEBKGND: return 1;
      case WM_PAINT: self->Paint(); return 0;
      case WM_DPICHANGED: {
        self->dpi_ = HIWORD(wp);
        const RECT* rect = reinterpret_cast<const RECT*>(lp);
        self->Place({rect->left, rect->top}); return 0;
      }
      case WM_LBUTTONDOWN:
        if (!ForegroundThread()) { self->Hide(); return 0; }
        self->pressed_ = HitTest(GET_X_LPARAM(lp), GET_Y_LPARAM(lp), self->dpi_, self->vertical_);
        if (self->pressed_ == 0) { GetCursorPos(&self->drag_); GetWindowRect(window, &self->origin_); }
        SetCapture(window); return 0;
      case WM_MOUSEMOVE: {
        if (GetCapture() == window && self->pressed_ == 0) {
          POINT point{}; GetCursorPos(&point);
          self->Place({self->origin_.left + point.x - self->drag_.x, self->origin_.top + point.y - self->drag_.y});
        }
        self->hover_ = HitTest(GET_X_LPARAM(lp), GET_Y_LPARAM(lp), self->dpi_, self->vertical_);
        TRACKMOUSEEVENT track{sizeof(TRACKMOUSEEVENT), TME_LEAVE, window, 0}; TrackMouseEvent(&track);
        InvalidateRect(window, nullptr, FALSE); return 0;
      }
      case WM_MOUSELEAVE: self->hover_ = -1; InvalidateRect(window, nullptr, FALSE); return 0;
      case WM_CAPTURECHANGED: self->pressed_ = -1; return 0;
      case WM_LBUTTONUP: {
        if (!ForegroundThread()) { ReleaseCapture(); self->Hide(); return 0; }
        const int pressed = self->pressed_;
        const int cell = HitTest(GET_X_LPARAM(lp), GET_Y_LPARAM(lp), self->dpi_, self->vertical_);
        ReleaseCapture();
        if (pressed == 0) {
          RECT rect{}; GetWindowRect(window, &rect);
          Write(L"X", static_cast<DWORD>(rect.left)); Write(L"Y", static_cast<DWORD>(rect.top));
        } else if (pressed == cell && cell >= 1 && cell <= 4) {
          POINT point{}; GetCursorPos(&point);
          auto callback = self->callback_;
          if (callback) callback(static_cast<unsigned>(cell), point);
        }
        return 0;
      }
      case WM_CONTEXTMENU: {
        if (!ForegroundThread()) { self->Hide(); return 0; }
        POINT point{}; GetCursorPos(&point);
        auto callback = self->callback_; if (callback) callback(4, point); return 0;
      }
      case WM_NCDESTROY:
        self->Withdraw();
        self->window_ = nullptr;
        SetWindowLongPtrW(window, GWLP_USERDATA, 0);
        break;
    }
    return DefWindowProcW(window, message, wp, lp);
  }
  HWND window_ = nullptr;
  StatusBarLease lease_;
  bool wanted_ = false;
  int dpi_ = 96, hover_ = -1, pressed_ = -1;
  bool pinyin_ = true, vertical_ = false;
  POINT drag_{};
  RECT origin_{};
  std::function<void(unsigned, POINT)> callback_;
};
} // namespace zhimo
