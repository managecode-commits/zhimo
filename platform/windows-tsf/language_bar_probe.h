// Copyright © 2026 立方田 <managecode@gmail.com>
#pragma once
#include "language_bar.h"

inline bool ProbeLanguageBar() {
  struct Sink final : ITfLangBarItemSink {
    LONG refs = 1;
    DWORD updates = 0;
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void** out) override {
      if (!out) return E_INVALIDARG;
      *out = nullptr;
      if (iid != IID_IUnknown && iid != IID_ITfLangBarItemSink) return E_NOINTERFACE;
      *out = static_cast<ITfLangBarItemSink*>(this); AddRef(); return S_OK;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return ++refs; }
    ULONG STDMETHODCALLTYPE Release() override { return --refs; }
    HRESULT STDMETHODCALLTYPE OnUpdate(DWORD flags) override { updates |= flags; return S_OK; }
  } sink;
  int clicks = 0;
  auto* item = new zhimo::ModeIndicator([&] { ++clicks; });
  TF_LANGBARITEMINFO info{};
  bool good = SUCCEEDED(item->GetInfo(&info)) && info.guidItem == zhimo::kInputModeItem;
  ITfSource* source = nullptr;
  good = good && SUCCEEDED(item->QueryInterface(IID_ITfSource, reinterpret_cast<void**>(&source)));
  if (source) source->Release();
  DWORD cookie = 0;
  good = good && SUCCEEDED(item->AdviseSink(IID_ITfLangBarItemSink, &sink, &cookie));
  for (bool chinese : {true, false, true}) {
    item->Update(chinese);
    BSTR text = nullptr;
    good = good && SUCCEEDED(item->GetText(&text));
    good = good && text && wcscmp(text, chinese ? L"中" : L"英") == 0;
    SysFreeString(text);
    HICON icon = nullptr;
    good = good && SUCCEEDED(item->GetIcon(&icon)) && icon;
    if (icon) DestroyIcon(icon);
  }
  good = good && (sink.updates & 7) == 7;
  item->OnClick(TF_LBI_CLK_RIGHT, {}, nullptr);
  good = good && clicks == 0;
  item->OnClick(TF_LBI_CLK_LEFT, {}, nullptr);
  good = good && clicks == 1;
  item->Detach();
  item->OnClick(TF_LBI_CLK_LEFT, {}, nullptr);
  good = good && clicks == 1;
  const HRESULT unadvise = item->UnadviseSink(cookie);
  good = good && SUCCEEDED(unadvise) && sink.refs == 1;
  item->Release();
  return good;
}
