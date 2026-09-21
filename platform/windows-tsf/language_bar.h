// Copyright © 2026 立方田 <managecode@gmail.com>
#pragma once
#include <windows.h>
#include <msctf.h>
#include <ctfutb.h>
#include <olectl.h>
#include <functional>
#include <utility>
#include "tsf_guids.h"

// Older MinGW omits this interface. Keep its Windows SDK vtable ABI.
#ifndef __ITfLangBarItemButton_INTERFACE_DEFINED__
enum TfLBIClick { TF_LBI_CLK_RIGHT = 1, TF_LBI_CLK_LEFT = 2 };
struct ITfMenu;
struct ITfLangBarItemButton : ITfLangBarItem {
  virtual HRESULT STDMETHODCALLTYPE OnClick(TfLBIClick, POINT, const RECT*) = 0;
  virtual HRESULT STDMETHODCALLTYPE InitMenu(ITfMenu*) = 0;
  virtual HRESULT STDMETHODCALLTYPE OnMenuSelect(UINT) = 0;
  virtual HRESULT STDMETHODCALLTYPE GetIcon(HICON*) = 0;
  virtual HRESULT STDMETHODCALLTYPE GetText(BSTR*) = 0;
};
#endif

namespace zhimo {
inline constexpr GUID kInputModeItem = {0x2c77a81e,0x41cc,0x4178,{0xa3,0xa7,0x5f,0x8a,0x98,0x75,0x68,0xe6}};
inline constexpr GUID kSystraySupport = {0x25504fb4,0x7bab,0x4bc1,{0x9c,0x69,0xcf,0x81,0x89,0x0f,0x0e,0xf5}};
inline constexpr GUID kButtonInterface = {0x28c7f1d0,0xde25,0x11d2,{0xaf,0xdd,0,0x10,0x5a,0x27,0x99,0xb5}};

class ModeIndicator final : public ITfLangBarItemButton, public ITfSource {
 public:
  explicit ModeIndicator(std::function<void()> click, std::function<void(POINT)> menu = {})
      : click_(std::move(click)), menu_(std::move(menu)) {}
  void Detach() { click_ = {}; menu_ = {}; }
  void Update(bool pinyin, bool caps_lock = false) {
    pinyin_ = pinyin;
    caps_lock_ = caps_lock;
    Notify(0x7); // TF_LBI_ICON | TF_LBI_TEXT | TF_LBI_TOOLTIP
  }
  HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void** out) override {
    if (!out) return E_INVALIDARG;
    *out = nullptr;
    if (iid == IID_IUnknown || iid == IID_ITfLangBarItem || iid == kButtonInterface)
      *out = static_cast<ITfLangBarItemButton*>(this);
    else if (iid == IID_ITfSource) *out = static_cast<ITfSource*>(this);
    else return E_NOINTERFACE;
    AddRef();
    return S_OK;
  }
  ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&refs_); }
  ULONG STDMETHODCALLTYPE Release() override {
    const ULONG count = InterlockedDecrement(&refs_);
    if (!count) delete this;
    return count;
  }
  HRESULT STDMETHODCALLTYPE GetInfo(TF_LANGBARITEMINFO* info) override {
    if (!info) return E_INVALIDARG;
    *info = {};
    info->clsidService = CLSID_ZhimoTextService;
    info->guidItem = kInputModeItem;
    // BTN_BUTTON + TEXTCOLORICON: Windows recolors monochrome ink for its theme.
    info->dwStyle = 0x10000 | 0x20;
    lstrcpynW(info->szDescription, L"Zhimo 中英文切换", TF_LBI_DESC_MAXLEN);
    return S_OK;
  }
  HRESULT STDMETHODCALLTYPE GetStatus(DWORD* status) override {
    if (!status) return E_INVALIDARG;
    *status = 0;
    return S_OK;
  }
  HRESULT STDMETHODCALLTYPE Show(BOOL) override { return S_OK; }
  HRESULT STDMETHODCALLTYPE GetTooltipString(BSTR* text) override {
    if (caps_lock_) return String(text, L"Zhimo：大写锁定 · 英文直输（关闭 Caps Lock 恢复原模式；Shift 临时小写）");
    return String(text, pinyin_ ? L"Zhimo：中文拼音（点击或 Shift+Space 切换）" :
                                L"Zhimo：英文直输（点击或 Shift+Space 切换）");
  }
  HRESULT STDMETHODCALLTYPE GetText(BSTR* text) override {
    return String(text, caps_lock_ ? L"A" : pinyin_ ? L"中" : L"英");
  }
  HRESULT STDMETHODCALLTYPE OnClick(TfLBIClick button, POINT point, const RECT*) override {
    if (button == TF_LBI_CLK_RIGHT && menu_) { auto menu = menu_; menu(point); }
    if (button == TF_LBI_CLK_LEFT && click_) {
      auto callback = click_;
      callback();
    }
    return S_OK;
  }
  HRESULT STDMETHODCALLTYPE InitMenu(ITfMenu*) override { return S_OK; }
  HRESULT STDMETHODCALLTYPE OnMenuSelect(UINT) override { return S_OK; }
  HRESULT STDMETHODCALLTYPE GetIcon(HICON* icon) override {
    if (!icon) return E_INVALIDARG;
    *icon = nullptr;
    const int size = GetSystemMetrics(SM_CXSMICON);
    HDC dc = CreateCompatibleDC(nullptr);
    if (!dc) return E_OUTOFMEMORY;
    // Monochrome icon: top half is AND mask, lower half is XOR mask.
    HBITMAP bitmap = CreateBitmap(size, size * 2, 1, 1, nullptr);
    if (!bitmap) { DeleteDC(dc); return E_OUTOFMEMORY; }
    const auto old = SelectObject(dc, bitmap);
    PatBlt(dc, 0, 0, size, size, WHITENESS);
    PatBlt(dc, 0, size, size, size, BLACKNESS);
    HFONT font = CreateFontW(-size, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE,
        DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, NONANTIALIASED_QUALITY,
        DEFAULT_PITCH, L"Microsoft YaHei UI");
    auto old_font = SelectObject(dc, font ? font : GetStockObject(DEFAULT_GUI_FONT));
    SetBkMode(dc, TRANSPARENT);
    SetTextColor(dc, RGB(0,0,0));
    RECT bounds{0,0,size,size};
    DrawTextW(dc, caps_lock_ ? L"A" : pinyin_ ? L"中" : L"英", -1, &bounds, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    SelectObject(dc, old_font);
    if (font) DeleteObject(font);
    SelectObject(dc, old);
    ICONINFO info{};
    info.fIcon = TRUE;
    info.hbmMask = bitmap;
    *icon = CreateIconIndirect(&info);
    DeleteObject(bitmap);
    DeleteDC(dc);
    return *icon ? S_OK : E_FAIL;
  }
  HRESULT STDMETHODCALLTYPE AdviseSink(REFIID iid, IUnknown* object, DWORD* cookie) override {
    if (!object || !cookie) return E_INVALIDARG;
    *cookie = 0;
    if (iid != IID_ITfLangBarItemSink) return CONNECT_E_CANNOTCONNECT;
    if (sink_) return CONNECT_E_ADVISELIMIT;
    HRESULT hr = object->QueryInterface(IID_ITfLangBarItemSink, reinterpret_cast<void**>(&sink_));
    if (SUCCEEDED(hr)) *cookie = 1;
    return hr;
  }
  HRESULT STDMETHODCALLTYPE UnadviseSink(DWORD cookie) override {
    if (cookie != 1 || !sink_) return CONNECT_E_NOCONNECTION;
    sink_->Release(); sink_ = nullptr;
    return S_OK;
  }
 private:
  ~ModeIndicator() { if (sink_) sink_->Release(); }
  static HRESULT String(BSTR* target, const wchar_t* value) {
    if (!target) return E_INVALIDARG;
    *target = SysAllocString(value);
    return *target ? S_OK : E_OUTOFMEMORY;
  }
  void Notify(DWORD flags) {
    if (!sink_) return;
    auto* sink = sink_;
    sink->AddRef();
    sink->OnUpdate(flags);
    sink->Release();
  }
  LONG refs_ = 1;
  bool pinyin_ = true;
  bool caps_lock_ = false;
  ITfLangBarItemSink* sink_ = nullptr;
  std::function<void()> click_;
  std::function<void(POINT)> menu_;
};
} // namespace zhimo
