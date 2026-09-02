#pragma once

#ifdef _WIN32
#include <msctf.h>
#include <windows.h>

#include <filesystem>
#include <string>
#include <vector>

#include "core_session.h"

namespace shurufa {

class TextService final :
#ifdef _MSC_VER
                          public ITfTextInputProcessorEx,
#else
                          public ITfTextInputProcessor,
#endif
                          public ITfKeyEventSink,
                          public ITfCompositionSink {
 public:
  TextService();
  ~TextService();

  HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void** object) override;
  ULONG STDMETHODCALLTYPE AddRef() override;
  ULONG STDMETHODCALLTYPE Release() override;

  HRESULT STDMETHODCALLTYPE Activate(ITfThreadMgr* thread_manager, TfClientId client_id) override;
#ifdef _MSC_VER
  HRESULT STDMETHODCALLTYPE ActivateEx(ITfThreadMgr* thread_manager, TfClientId client_id,
                                       DWORD flags) override;
#endif
  HRESULT STDMETHODCALLTYPE Deactivate() override;

  HRESULT STDMETHODCALLTYPE OnSetFocus(BOOL foreground) override;
  HRESULT STDMETHODCALLTYPE OnTestKeyDown(ITfContext* context, WPARAM wparam, LPARAM lparam,
                                          BOOL* eaten) override;
  HRESULT STDMETHODCALLTYPE OnKeyDown(ITfContext* context, WPARAM wparam, LPARAM lparam,
                                      BOOL* eaten) override;
  HRESULT STDMETHODCALLTYPE OnTestKeyUp(ITfContext* context, WPARAM wparam, LPARAM lparam,
                                        BOOL* eaten) override;
  HRESULT STDMETHODCALLTYPE OnKeyUp(ITfContext* context, WPARAM wparam, LPARAM lparam,
                                    BOOL* eaten) override;
  HRESULT STDMETHODCALLTYPE OnPreservedKey(ITfContext* context, REFGUID guid,
                                           BOOL* eaten) override;
  HRESULT STDMETHODCALLTYPE OnCompositionTerminated(TfEditCookie cookie,
                                                     ITfComposition* composition) override;

  HRESULT ApplyActions(TfEditCookie cookie, ITfContext* context,
                       const std::vector<Action>& actions);

 private:
  bool ShouldEat(ITfContext* context, WPARAM key) const;
  bool ProcessKey(ITfContext* context, WPARAM key);
  unsigned int DetectScope(ITfContext* context) const;
  HRESULT SetComposition(TfEditCookie cookie, ITfContext* context, const std::wstring& text);
  HRESULT Commit(TfEditCookie cookie, ITfContext* context, const std::wstring& text);
  void EndComposition(TfEditCookie cookie);
  void ShowCandidates(TfEditCookie cookie, ITfContext* context,
                      const std::vector<Candidate>& candidates);
  void HideCandidates();

  LONG references_ = 1;
  ITfThreadMgr* thread_manager_ = nullptr;
  TfClientId client_id_ = 0;
  ITfComposition* composition_ = nullptr;
  CoreSession session_;
  bool pinyin_ = true;
  bool composing_ = false;
  HWND candidate_window_ = nullptr;
};

}  // namespace shurufa
#endif
