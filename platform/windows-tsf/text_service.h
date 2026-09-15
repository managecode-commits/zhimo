// Copyright © 2026 立方田 <managecode@gmail.com>
#pragma once

#ifdef _WIN32
#include <msctf.h>
#include <windows.h>

#include <filesystem>
#include <string>
#include <vector>

#include "core_session.h"
#include "desktop_client.h"
#include "status_bar.h"

namespace zhimo {
class ModeIndicator;

class TextService final :
#ifdef _MSC_VER
                          public ITfTextInputProcessorEx,
#else
                          public ITfTextInputProcessor,
#endif
                          public ITfKeyEventSink,
                          public ITfThreadFocusSink,
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
  HRESULT STDMETHODCALLTYPE OnSetThreadFocus() override;
  HRESULT STDMETHODCALLTYPE OnKillThreadFocus() override;
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
  HRESULT ProcessKeyLocked(TfEditCookie cookie, ITfContext* context, WPARAM key,
                           bool shift_space, bool uppercase);
  unsigned long long generation() const { return generation_; }
  bool IsFocusedContext(ITfContext* context) const;
  HRESULT CommitDesktopText(TfEditCookie cookie, ITfContext* context,
                            unsigned long long revision, ITfRange* anchor, const std::wstring& text);

 private:
  bool ShouldEat(ITfContext* context, WPARAM key) const;
  bool ProcessKey(ITfContext* context, WPARAM key);
  unsigned int DetectScope(ITfContext* context) const;
  unsigned int DetectScopeLocked(TfEditCookie cookie, ITfContext* context) const;
  HRESULT SetComposition(TfEditCookie cookie, ITfContext* context, const std::wstring& text);
  HRESULT Commit(TfEditCookie cookie, ITfContext* context, const std::wstring& text);
  void EndComposition(TfEditCookie cookie);
  void ShowCandidates(TfEditCookie cookie, ITfContext* context,
                      const std::vector<Candidate>& candidates);
  void HideCandidates();
  static LRESULT CALLBACK CandidateWindowProc(HWND window, UINT message, WPARAM wparam, LPARAM lparam);
  void PaintCandidates(HWND window);
  void ClickCandidate(POINT point);
  void ShowModeIndicator();
  void ClickModeIndicator();
  void StatusAction(unsigned action, POINT point);
  void OpenDesktopPanel(TfEditCookie cookie, ITfContext* context, bool speech);
  void CloseDesktopPanel();

  LONG references_ = 1;
  unsigned long long generation_ = 0;
  ITfThreadMgr* thread_manager_ = nullptr;
  TfClientId client_id_ = 0;
  DWORD thread_focus_cookie_ = TF_INVALID_COOKIE;
  ITfComposition* composition_ = nullptr;
  CoreSession session_;
  bool pinyin_ = true;
  bool keyboard_foreground_ = true;
  bool composing_ = false;
  HWND candidate_window_ = nullptr;
  ModeIndicator* mode_indicator_ = nullptr;
  StatusBar status_bar_;
  DesktopClient desktop_client_;
  ITfContext* desktop_context_ = nullptr;
  ITfRange* desktop_anchor_ = nullptr;
  std::wstring composition_text_;
  std::vector<std::wstring> candidate_labels_;
  std::vector<RECT> candidate_cells_;
  RECT previous_page_{};
  RECT next_page_{};
  int candidate_dpi_ = 96;
};

}  // namespace zhimo
#endif
