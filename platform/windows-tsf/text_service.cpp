// Copyright © 2026 立方田 <managecode@gmail.com>
#ifdef _WIN32
#include "text_service.h"
#include "input_policy.h"
#include "language_bar.h"

#include <objbase.h>
#include <shlobj.h>
#ifndef _MSC_VER
// The isolated MinGW uuid library omits these inputscope GUID definitions.
#include <initguid.h>
#endif
#include <inputscope.h>

#include <algorithm>
#include <memory>
#include <new>
#include <limits>
#include <string_view>

namespace zhimo {
namespace {

std::filesystem::path DataDirectory() {
  PWSTR path = nullptr;
  std::filesystem::path result;
  if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_LocalAppData, KF_FLAG_CREATE, nullptr, &path))) {
    result = std::filesystem::path(path) / L"Zhimo" / L"user";
    CoTaskMemFree(path);
  } else {
    result = std::filesystem::temp_directory_path() / L"Zhimo" / L"user";
  }
  std::filesystem::create_directories(result);
  return result;
}

std::wstring Utf8ToWide(std::string_view value) {
  if (value.empty()) return {};
  const int size = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(),
                                       static_cast<int>(value.size()), nullptr, 0);
  if (size <= 0) return {};
  std::wstring result(static_cast<size_t>(size), L'\0');
  MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(),
                      static_cast<int>(value.size()), result.data(), size);
  return result;
}

class EditSession final : public ITfEditSession {
 public:
  EditSession(TextService* service, ITfContext* context, WPARAM key, bool shift_space, bool uppercase,
              bool mouse_switch = false)
      : service_(service), context_(context), key_(key), shift_space_(shift_space), uppercase_(uppercase),
        mouse_switch_(mouse_switch) {
    generation_ = service_->generation();
    service_->AddRef();
    context_->AddRef();
  }
  ~EditSession() {
    context_->Release();
    service_->Release();
  }
  HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void** object) override {
    if (!object) return E_INVALIDARG;
    *object = nullptr;
    if (iid == IID_IUnknown || iid == IID_ITfEditSession) {
      *object = static_cast<ITfEditSession*>(this);
      AddRef();
      return S_OK;
    }
    return E_NOINTERFACE;
  }
  ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&references_); }
  ULONG STDMETHODCALLTYPE Release() override {
    const ULONG value = InterlockedDecrement(&references_);
    if (!value) delete this;
    return value;
  }
  HRESULT STDMETHODCALLTYPE DoEditSession(TfEditCookie cookie) override {
    if (key_ == 0 && generation_ != service_->generation()) return S_OK;
    if (mouse_switch_ && (generation_ != service_->generation() || !service_->IsFocusedContext(context_))) return S_FALSE;
    // No Runtime mutation is permitted until TSF has granted the edit lock.
    return service_->ProcessKeyLocked(cookie, context_, key_, shift_space_, uppercase_);
  }

 private:
  LONG references_ = 1;
  TextService* service_;
  ITfContext* context_;
  WPARAM key_;
  bool shift_space_;
  bool uppercase_;
  bool mouse_switch_;
  unsigned long long generation_;
};

class DesktopCommitSession final : public ITfEditSession {
 public:
  DesktopCommitSession(TextService* service, ITfContext* context,
      unsigned long long revision, ITfRange* anchor, std::wstring text)
      : service_(service), context_(context), revision_(revision), text_(std::move(text)), anchor_(anchor) {
    service_->AddRef(); context_->AddRef(); anchor_->AddRef();
  }
  ~DesktopCommitSession() { anchor_->Release(); context_->Release(); service_->Release(); }
  HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void** out) override {
    if (!out) return E_INVALIDARG;
    *out = nullptr;
    if (iid != IID_IUnknown && iid != IID_ITfEditSession) return E_NOINTERFACE;
    *out = static_cast<ITfEditSession*>(this); AddRef(); return S_OK;
  }
  ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&refs_); }
  ULONG STDMETHODCALLTYPE Release() override { auto n=InterlockedDecrement(&refs_); if (!n) delete this; return n; }
  HRESULT STDMETHODCALLTYPE DoEditSession(TfEditCookie cookie) override {
    return service_->CommitDesktopText(cookie,context_,revision_,anchor_,text_);
  }
 private:
  LONG refs_=1;
  TextService* service_;
  ITfContext* context_;
  unsigned long long revision_;
  std::wstring text_;
  ITfRange* anchor_;
};

bool DesktopShortcut(WPARAM key) {
  return (key=='H' || key==VK_F10) && GetKeyState(VK_CONTROL)<0 && GetKeyState(VK_SHIFT)<0 &&
      GetKeyState(VK_MENU)>=0 && GetKeyState(VK_LWIN)>=0 && GetKeyState(VK_RWIN)>=0;
}
}  // namespace

TextService::TextService() : session_(DataDirectory()) {}
TextService::~TextService() {
  Deactivate();
  if (candidate_window_) DestroyWindow(candidate_window_);
}

HRESULT TextService::QueryInterface(REFIID iid, void** object) {
  if (!object) return E_INVALIDARG;
  *object = nullptr;
  if (iid == IID_IUnknown || iid == IID_ITfTextInputProcessor
#ifdef _MSC_VER
      || iid == IID_ITfTextInputProcessorEx
#endif
  ) {
#ifdef _MSC_VER
    *object = static_cast<ITfTextInputProcessorEx*>(this);
#else
    *object = static_cast<ITfTextInputProcessor*>(this);
#endif
  } else if (iid == IID_ITfKeyEventSink) {
    *object = static_cast<ITfKeyEventSink*>(this);
  } else if (iid == IID_ITfThreadFocusSink) {
    *object = static_cast<ITfThreadFocusSink*>(this);
  } else if (iid == IID_ITfCompositionSink) {
    *object = static_cast<ITfCompositionSink*>(this);
  } else {
    return E_NOINTERFACE;
  }
  AddRef();
  return S_OK;
}

ULONG TextService::AddRef() { return InterlockedIncrement(&references_); }
ULONG TextService::Release() {
  const ULONG value = InterlockedDecrement(&references_);
  if (!value) delete this;
  return value;
}

HRESULT TextService::Activate(ITfThreadMgr* manager, TfClientId id) {
#ifdef _MSC_VER
  return ActivateEx(manager, id, 0);
}
HRESULT TextService::ActivateEx(ITfThreadMgr* manager, TfClientId id, DWORD) {
#else
  if (!manager || !session_.valid()) return E_FAIL;
#endif
  if (!manager || !session_.valid()) return E_FAIL;
  thread_manager_ = manager;
  thread_manager_->AddRef();
  client_id_ = id;
  keyboard_foreground_ = true;
  ITfSource* focus_source = nullptr;
  if (SUCCEEDED(manager->QueryInterface(IID_ITfSource, reinterpret_cast<void**>(&focus_source)))) {
    focus_source->AdviseSink(IID_ITfThreadFocusSink, static_cast<ITfThreadFocusSink*>(this), &thread_focus_cookie_);
    focus_source->Release();
  }
  ITfKeystrokeMgr* keystrokes = nullptr;
  const HRESULT result = manager->QueryInterface(IID_PPV_ARGS(&keystrokes));
  if (SUCCEEDED(result)) {
    const HRESULT advise = keystrokes->AdviseKeyEventSink(id, this, TRUE);
    keystrokes->Release();
    if (SUCCEEDED(advise)) ShowModeIndicator();
    else Deactivate();
    return advise;
  }
  Deactivate();
  return result;
}
HRESULT TextService::Deactivate() {
  keyboard_foreground_ = false;
  status_bar_.Close();
  CloseDesktopPanel();
  if (mode_indicator_) {
    mode_indicator_->Detach();
    ITfLangBarItemMgr* manager = nullptr;
    if (thread_manager_ && SUCCEEDED(thread_manager_->QueryInterface(IID_ITfLangBarItemMgr,
        reinterpret_cast<void**>(&manager)))) {
      manager->RemoveItem(mode_indicator_);
      manager->Release();
    }
    mode_indicator_->Release();
    mode_indicator_ = nullptr;
  }
  HideCandidates();
  if (thread_manager_) {
    if (thread_focus_cookie_ != TF_INVALID_COOKIE) {
      ITfSource* source = nullptr;
      if (SUCCEEDED(thread_manager_->QueryInterface(IID_ITfSource, reinterpret_cast<void**>(&source)))) {
        source->UnadviseSink(thread_focus_cookie_);
        source->Release();
      }
      thread_focus_cookie_ = TF_INVALID_COOKIE;
    }
    ITfKeystrokeMgr* keystrokes = nullptr;
    if (SUCCEEDED(thread_manager_->QueryInterface(IID_PPV_ARGS(&keystrokes)))) {
      keystrokes->UnadviseKeyEventSink(client_id_);
      keystrokes->Release();
    }
    thread_manager_->Release();
    thread_manager_ = nullptr;
  }
  if (composition_) {
    composition_->Release();
    composition_ = nullptr;
  }
  client_id_ = 0;
  composing_ = false;
  return S_OK;
}

HRESULT TextService::OnSetThreadFocus() {
  ShowModeIndicator();
  return S_OK;
}

HRESULT TextService::OnKillThreadFocus() {
  status_bar_.Hide();
  return S_OK;
}

HRESULT TextService::OnSetFocus(BOOL foreground) {
  keyboard_foreground_ = foreground != FALSE;
  if (foreground) ShowModeIndicator();
  if (!foreground) {
    status_bar_.Hide();
    CloseDesktopPanel();
    ++generation_;
    HideCandidates();
    (void)session_.command(3);
    composing_ = false;
    ITfRange* range = nullptr;
    ITfContext* owner = nullptr;
    if (composition_ && SUCCEEDED(composition_->GetRange(&range)) && range) {
      range->GetContext(&owner);
      range->Release();
    }
    if (owner) {
      auto* edit = new (std::nothrow) EditSession(this, owner, 0, false, false);
      if (edit) {
        HRESULT result = E_FAIL;
        owner->RequestEditSession(client_id_, edit, TF_ES_ASYNC | TF_ES_READWRITE, &result);
        edit->Release();
      }
      owner->Release();
    }
  }
  return S_OK;
}
HRESULT TextService::OnTestKeyDown(ITfContext* context, WPARAM key, LPARAM, BOOL* eaten) {
  if (!eaten) return E_INVALIDARG;
  if (key == VK_CAPITAL || key == VK_SHIFT) ShowModeIndicator();
  // Even a native/pass-through key invalidates an outstanding panel result.
  if (desktop_context_) { ++generation_; CloseDesktopPanel(); }
  *eaten = ShouldEat(context, key);
  return S_OK;
}
HRESULT TextService::OnKeyDown(ITfContext* context, WPARAM key, LPARAM, BOOL* eaten) {
  if (!eaten) return E_INVALIDARG;
  ++generation_;
  CloseDesktopPanel();
  *eaten = ProcessKey(context, key);
  return S_OK;
}
HRESULT TextService::OnTestKeyUp(ITfContext*, WPARAM key, LPARAM, BOOL* eaten) {
  if (!eaten) return E_INVALIDARG;
  if (key == VK_CAPITAL || key == VK_SHIFT) ShowModeIndicator();
  *eaten = FALSE;
  return S_OK;
}
HRESULT TextService::OnKeyUp(ITfContext*, WPARAM key, LPARAM, BOOL* eaten) {
  if (!eaten) return E_INVALIDARG;
  if (key == VK_CAPITAL || key == VK_SHIFT) ShowModeIndicator();
  *eaten = FALSE;
  return S_OK;
}
HRESULT TextService::OnPreservedKey(ITfContext*, REFGUID, BOOL* eaten) {
  if (!eaten) return E_INVALIDARG;
  *eaten = FALSE;
  return S_OK;
}
HRESULT TextService::OnCompositionTerminated(TfEditCookie, ITfComposition* composition) {
  if (composition_ && composition_ == composition) {
    composition_->Release();
    composition_ = nullptr;
  } else {
    return S_OK;
  }
  composing_ = false;
  HideCandidates();
  (void)session_.command(3);
  return S_OK;
}

bool TextService::ShouldEat(ITfContext* context, WPARAM key) const {
  if (context && DesktopShortcut(key) && DetectScope(context)!=1) return true;
  if (!context || GetKeyState(VK_CONTROL) < 0 || GetKeyState(VK_MENU) < 0 ||
      GetKeyState(VK_LWIN) < 0 || GetKeyState(VK_RWIN) < 0) return false;
  if (DetectScope(context) == 1) return false;
  if (key == VK_SPACE && GetKeyState(VK_SHIFT) < 0) return true;
  if (!pinyin_) return false; // Direct native English, with no auto-completion.
  if (key == VK_CAPITAL) return composing_;
  if (UseNativeCase((GetKeyState(VK_CAPITAL) & 1) != 0, GetKeyState(VK_SHIFT) < 0,
                    key >= 'A' && key <= 'Z')) return composing_;
  if ((key >= 'A' && key <= 'Z') || (key == VK_OEM_7 && GetKeyState(VK_SHIFT) >= 0)) return true;
  if (composing_ && IsLiteralBoundaryKey(static_cast<unsigned>(key))) return true;
  return composing_ && (key == VK_BACK || key == VK_SPACE || key == VK_RETURN ||
                        key == VK_ESCAPE || key == VK_LEFT || key == VK_RIGHT ||
                        key == VK_PRIOR || key == VK_NEXT || (key >= '1' && key <= '9'));
}

bool TextService::ProcessKey(ITfContext* context, WPARAM key) {
  if (!ShouldEat(context, key)) return false;
  const bool uppercase = (GetKeyState(VK_SHIFT) < 0) != ((GetKeyState(VK_CAPITAL) & 1) != 0);
  auto* edit = new (std::nothrow) EditSession(this, context, key,
      key == VK_SPACE && GetKeyState(VK_SHIFT) < 0, uppercase);
  if (!edit) return false;
  HRESULT session_result = E_FAIL;
  const HRESULT request = context->RequestEditSession(client_id_, edit,
      TF_ES_SYNC | TF_ES_READWRITE, &session_result);
  edit->Release();
  return SUCCEEDED(request) && session_result == S_OK;
}

HRESULT TextService::ProcessKeyLocked(TfEditCookie cookie, ITfContext* context, WPARAM key,
                                     bool shift_space, bool uppercase) {
  if (key == 0 && !composition_) return S_OK;
  // A cookie belongs to one context only. Never apply it to an old composition.
  if (composition_) {
    ITfRange* range = nullptr;
    ITfContext* owner = nullptr;
    IUnknown* owner_identity = nullptr;
    IUnknown* current_identity = nullptr;
    if (SUCCEEDED(composition_->GetRange(&range)) && range) {
      range->GetContext(&owner);
      range->Release();
    }
    if (owner) owner->QueryInterface(IID_IUnknown, reinterpret_cast<void**>(&owner_identity));
    context->QueryInterface(IID_IUnknown, reinterpret_cast<void**>(&current_identity));
    const bool same = owner_identity && owner_identity == current_identity;
    if (owner_identity) owner_identity->Release();
    if (current_identity) current_identity->Release();
    if (owner) owner->Release();
    if (!same) {
      HideCandidates();
      (void)session_.command(3);
      return S_FALSE;
    }
  }
  if (key == 0) {
    const HRESULT result = SetComposition(cookie, context, L"");
    EndComposition(cookie);
    return result;
  }
  const auto scope = DetectScopeLocked(cookie, context);
  ++generation_;
  // Unknown/custom controls are passed through rather than treated as safe text fields.
  if (scope == 1 || scope == std::numeric_limits<unsigned>::max()) {
    (void)session_.set_context("", false);
    (void)session_.command(3);
    EndComposition(cookie);
    return S_FALSE;
  }
  wchar_t application[32768]{};
  const DWORD length = GetModuleFileNameW(nullptr, application, 32768);
  std::string application_id;
  if (length > 0 && length < 32768) {
    const int size = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, application,
        static_cast<int>(length), nullptr, 0, nullptr, nullptr);
    if (size > 0) {
      application_id.resize(size);
      WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, application,
          static_cast<int>(length), application_id.data(), size, nullptr, nullptr);
    }
  }
  if (!session_.set_input_scope(scope == 5 ? 0 : scope) ||
      !session_.set_context(application_id, scope != 5 && !application_id.empty())) return E_FAIL;
  if (DesktopShortcut(key) || key==0x10001 || key==0x10002) {
    (void)session_.command(3);
    // Opening a panel must not delete an existing selection in the editor.
    if (composition_) {
      const HRESULT cleared=SetComposition(cookie, context, L"");
      if (FAILED(cleared)) return cleared;
      EndComposition(cookie);
    }
    HideCandidates();
    OpenDesktopPanel(cookie, context, key==VK_F10 || key==0x10002);
    return S_OK;
  }
  if (!shift_space && (key == VK_CAPITAL ||
      UseNativeCase((GetKeyState(VK_CAPITAL) & 1) != 0, GetKeyState(VK_SHIFT) < 0,
                    key >= 'A' && key <= 'Z'))) {
    // Preserve the raw spelling before returning the original key to the host.
    // Do not choose a Chinese candidate or synthesize US-layout uppercase text.
    if (composing_) {
      if (!session_.command(1)) return E_FAIL;
      const auto result = ApplyActions(cookie, context, session_.structured_actions());
      if (FAILED(result)) return S_OK; // Never replay after a partial edit.
    }
    ShowModeIndicator();
    return S_FALSE;
  } else if (shift_space) {
    if (composing_) (void)session_.command(3);
    pinyin_ = !pinyin_;
    if (!session_.switch_engine(pinyin_ ? "pinyin.reference" : "latin")) {
      pinyin_ = !pinyin_;
      return E_FAIL;
    }
    ShowModeIndicator();
  } else if (composing_ && IsLiteralBoundaryKey(static_cast<unsigned>(key)) &&
             !(key == VK_OEM_7 && GetKeyState(VK_SHIFT) >= 0) &&
             !(key >= '1' && key <= '9' && GetKeyState(VK_SHIFT) >= 0)) {
    if (!session_.command(2)) return E_FAIL;
    // Finish TSF composition synchronously, then let the host insert the original
    // key. No US-layout punctuation table and no ToUnicode dead-key side effects.
    const auto result = ApplyActions(cookie, context, session_.structured_actions());
    if (FAILED(result)) {
      (void)session_.command(3);
      EndComposition(cookie);
      return S_OK; // Do not replay a key after a partially applied edit.
    }
    return S_FALSE;
  } else if (key >= '1' && key <= '9' && composing_ && GetKeyState(VK_SHIFT) >= 0) {
    const auto actions = session_.structured_actions();
    for (const auto& action : actions) {
      if (action.kind == 2 && static_cast<size_t>(key - '1') < action.candidates.size()) {
        if (!session_.select(action.candidates[static_cast<size_t>(key - '1')].id)) return E_FAIL;
        break;
      }
    }
  } else if (key >= 'A' && key <= 'Z') {
    char value[2] = {static_cast<char>(pinyin_ || !uppercase ? key - 'A' + 'a' : key), 0};
    if (!session_.feed(value)) return E_FAIL;
  } else if (key == VK_OEM_7) {
    if (!session_.feed("'")) return E_FAIL;
  } else {
    unsigned command = 3;
    if (key == VK_BACK) command = 0;
    if (key == VK_RETURN) command = 1;
    if (key == VK_SPACE) command = 2;
    if (key == VK_LEFT) command = 4;
    if (key == VK_RIGHT) command = 5;
    if (key == VK_PRIOR) command = 6;
    if (key == VK_NEXT) command = 7;
    if (!session_.command(command)) return E_FAIL;
  }
  const auto result = ApplyActions(cookie, context, session_.structured_actions());
  if (FAILED(result)) {
    (void)session_.command(3);
    EndComposition(cookie);
    // A partial document edit cannot safely be replayed as an unhandled key.
    return S_OK;
  }
  return result;
}

unsigned int TextService::DetectScopeLocked(TfEditCookie cookie, ITfContext* context) const {
  if (DetectScope(context) == 1) return 1;
  unsigned result = std::numeric_limits<unsigned>::max();
  TF_SELECTION selection{};
  ULONG fetched = 0;
  if (FAILED(context->GetSelection(cookie, TF_DEFAULT_SELECTION, 1, &selection, &fetched)) || fetched != 1 || !selection.range)
    return result;
  ITfReadOnlyProperty* property = nullptr;
  if (SUCCEEDED(context->GetAppProperty(GUID_PROP_INPUTSCOPE, &property)) && property) {
    VARIANT value;
    VariantInit(&value);
    if (SUCCEEDED(property->GetValue(cookie, selection.range, &value)) &&
        value.vt == VT_UNKNOWN && value.punkVal) {
      ITfInputScope* input_scope = nullptr;
      if (SUCCEEDED(value.punkVal->QueryInterface(IID_ITfInputScope,
          reinterpret_cast<void**>(&input_scope)))) {
        InputScope* scopes = nullptr;
        UINT count = 0;
        if (SUCCEEDED(input_scope->GetInputScopes(&scopes, &count)) && scopes && count) {
          result = 0;
          for (UINT i = 0; i < count; ++i) {
            result = MergeInputScope(result, static_cast<int>(scopes[i]));
          }
        }
        CoTaskMemFree(scopes);
        input_scope->Release();
      }
    }
    VariantClear(&value);
    property->Release();
  }
  selection.range->Release();
  if (result != std::numeric_limits<unsigned>::max()) return result;
  ITfContextView* view = nullptr;
  HWND window = nullptr;
  if (SUCCEEDED(context->GetActiveView(&view))) { view->GetWnd(&window); view->Release(); }
  wchar_t name[128]{};
  if (window && GetClassNameW(window, name, 128) &&
      (_wcsicmp(name, L"Edit") == 0 || _wcsnicmp(name, L"RichEdit", 8) == 0)) return 0;
  // EmEditor's documented custom document window is not an Edit/RichEdit.
  // Only allow the focused document window in the current EmEditor process;
  // dialogs and unrelated custom controls remain unknown. No learning fallback.
  HWND focus = GetFocus();
  wchar_t focused_class[128]{};
  wchar_t executable[32768]{};
  DWORD process = 0;
  if (focus && GetClassNameW(focus, focused_class, 128) &&
      _wcsicmp(focused_class, L"EmEditorView") == 0 &&
      window && (focus == window || IsChild(window, focus))) {
    GetWindowThreadProcessId(focus, &process);
    const DWORD length = GetModuleFileNameW(nullptr, executable, 32768);
    const wchar_t* basename = wcsrchr(executable, L'\\');
    if (length > 0 && length < 32768 && IsEmEditorDocument(
        basename ? basename + 1 : executable, focused_class,
        process == GetCurrentProcessId(), focus == window || IsChild(window, focus),
        (GetWindowLongPtr(focus, GWL_STYLE) & ES_PASSWORD) != 0)) return 5;
  }
  return result;
}

unsigned int TextService::DetectScope(ITfContext* context) const {
  ITfContextView* view = nullptr;
  HWND window = nullptr;
  if (context && SUCCEEDED(context->GetActiveView(&view))) {
    view->GetWnd(&window);
    view->Release();
  }
  if (window && (GetWindowLongPtr(window, GWL_STYLE) & ES_PASSWORD)) return 1;
  return 0;
}

HRESULT TextService::SetComposition(TfEditCookie cookie, ITfContext* context,
                                    const std::wstring& text) {
  ITfRange* range = nullptr;
  if (composition_) {
    const HRESULT result = composition_->GetRange(&range);
    if (FAILED(result) || !range) return E_FAIL;
  } else {
    TF_SELECTION selection{};
    ULONG fetched = 0;
    HRESULT result = context->GetSelection(cookie, TF_DEFAULT_SELECTION, 1, &selection, &fetched);
    if (FAILED(result) || fetched != 1) return E_FAIL;
    range = selection.range;
    ITfContextComposition* compositions = nullptr;
    result = context->QueryInterface(IID_PPV_ARGS(&compositions));
    if (SUCCEEDED(result)) {
      result = compositions->StartComposition(cookie, range, this, &composition_);
      compositions->Release();
    }
    if (FAILED(result)) { range->Release(); return result; }
  }
  HRESULT result = range->SetText(cookie, 0, text.c_str(), static_cast<LONG>(text.size()));
  if (SUCCEEDED(result)) result = range->Collapse(cookie, TF_ANCHOR_END);
  TF_SELECTION selection{range, {TF_AE_END, FALSE}};
  if (SUCCEEDED(result)) result = context->SetSelection(cookie, 1, &selection);
  range->Release();
  composing_ = !text.empty();
  if (SUCCEEDED(result)) composition_text_ = text;
  return result;
}

HRESULT TextService::Commit(TfEditCookie cookie, ITfContext* context, const std::wstring& text) {
  HRESULT result = SetComposition(cookie, context, text);
  if (SUCCEEDED(result)) EndComposition(cookie);
  return result;
}
void TextService::EndComposition(TfEditCookie cookie) {
  if (composition_) {
    // EndComposition may synchronously re-enter OnCompositionTerminated.
    auto* composition = composition_;
    composition_ = nullptr;
    composition->EndComposition(cookie);
    composition->Release();
  }
  composing_ = false;
  composition_text_.clear();
  HideCandidates();
}

HRESULT TextService::ApplyActions(TfEditCookie cookie, ITfContext* context,
                                  const std::vector<Action>& actions) {
  HRESULT result = S_OK;
  for (const auto& action : actions) {
    if (action.kind == 1) result = SetComposition(cookie, context, Utf8ToWide(action.text));
    else if (action.kind == 2) ShowCandidates(cookie, context, action.candidates);
    else if (action.kind == 3) result = Commit(cookie, context, Utf8ToWide(action.text));
    else if (action.kind == 4) EndComposition(cookie);
    if (FAILED(result)) return result;
  }
  return result;
}

void TextService::ShowModeIndicator() {
  if (!thread_manager_) return;
  if (!mode_indicator_) {
    ITfLangBarItemMgr* manager = nullptr;
    if (FAILED(thread_manager_->QueryInterface(IID_ITfLangBarItemMgr,
        reinterpret_cast<void**>(&manager)))) return;
    auto* item = new (std::nothrow) ModeIndicator([this] {
      AddRef();
      ClickModeIndicator();
      Release();
    }, [this](POINT point) {
      AddRef();
      StatusAction(4, point);
      Release();
    });
    if (item) {
      if (SUCCEEDED(manager->AddItem(item))) mode_indicator_ = item;
      else item->Release();
    }
    manager->Release();
  }
  if (mode_indicator_) mode_indicator_->Update(pinyin_, (GetKeyState(VK_CAPITAL) & 1) != 0);
  DWORD foreground = 0;
  const DWORD foreground_thread = GetWindowThreadProcessId(GetForegroundWindow(), &foreground);
  if (keyboard_foreground_ && foreground == GetCurrentProcessId() && foreground_thread == GetCurrentThreadId()) {
    status_bar_.Show(pinyin_, [this](unsigned action, POINT point) {
      AddRef(); StatusAction(action, point); Release();
    });
  } else status_bar_.Hide();
}

void TextService::StatusAction(unsigned action, POINT point) {
  if (action == 4) {
    HMENU menu = CreatePopupMenu();
    if (!menu) return;
    AppendMenuW(menu, MF_STRING, 1, pinyin_ ? L"切换到英文" : L"切换到中文拼音");
    AppendMenuW(menu, MF_STRING, 2, L"手写输入（Ctrl+Shift+H）");
    AppendMenuW(menu, MF_STRING, 3, L"离线语音（Ctrl+Shift+F10）");
    AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(menu, MF_STRING, 5, StatusBar::Enabled() ? L"隐藏浮动状态栏" : L"显示浮动状态栏");
    AppendMenuW(menu, MF_STRING | (!StatusBar::Vertical() ? MF_CHECKED : 0), 6, L"水平显示");
    AppendMenuW(menu, MF_STRING | (StatusBar::Vertical() ? MF_CHECKED : 0), 7, L"垂直显示");
    action = TrackPopupMenu(menu, TPM_RETURNCMD | TPM_NONOTIFY, point.x, point.y, 0, GetForegroundWindow(), nullptr);
    DestroyMenu(menu);
  }
  if (action == 5) {
    StatusBar::SetEnabled(!StatusBar::Enabled());
    ShowModeIndicator();
    return;
  }
  if (action == 1) { ClickModeIndicator(); return; }
  if (action == 6 || action == 7) {
    StatusBar::SetVertical(action == 7);
    ShowModeIndicator();
    return;
  }
  if (action != 2 && action != 3) return;
  ITfDocumentMgr* document = nullptr;
  ITfContext* context = nullptr;
  if (thread_manager_ && SUCCEEDED(thread_manager_->GetFocus(&document)) && document) {
    document->GetTop(&context); document->Release();
  }
  if (!context) return;
  auto* edit = new(std::nothrow) EditSession(this, context, action == 3 ? 0x10002 : 0x10001, false, false, true);
  if (edit) {
    HRESULT result = E_FAIL;
    context->RequestEditSession(client_id_, edit, TF_ES_ASYNCDONTCARE | TF_ES_READWRITE, &result);
    edit->Release();
  }
  context->Release();
}

void TextService::CloseDesktopPanel() {
  desktop_client_.Stop();
  if (desktop_anchor_) { desktop_anchor_->Release(); desktop_anchor_=nullptr; }
  if (desktop_context_) { desktop_context_->Release(); desktop_context_=nullptr; }
}

void TextService::OpenDesktopPanel(TfEditCookie cookie, ITfContext* context, bool speech) {
  CloseDesktopPanel();
  if (!context || !IsFocusedContext(context) || DetectScope(context)==1) return;
  TF_SELECTION selection{}; ULONG fetched=0;
  if (FAILED(context->GetSelection(cookie,TF_DEFAULT_SELECTION,1,&selection,&fetched)) || fetched!=1 || !selection.range) return;
  desktop_anchor_=selection.range;
  HMODULE module=nullptr;
  GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS|GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
      reinterpret_cast<LPCWSTR>(&CandidateWindowProc),&module);
  wchar_t path[32768]{};
  if (!GetModuleFileNameW(module,path,32768)) { CloseDesktopPanel(); return; }
  auto executable=(std::filesystem::path(path).parent_path()/L"zhimo-desktop-panel.exe").wstring();
#if !defined(_WIN64)
  // The combined x64-Windows package keeps the isolated recognition process
  // next to the x64 DLL, one level above this x86 TSF DLL.
  if (!std::filesystem::exists(executable) && std::filesystem::path(path).parent_path().filename() == L"x86") {
    executable=(std::filesystem::path(path).parent_path().parent_path()/L"zhimo-desktop-panel.exe").wstring();
  }
#endif
  desktop_context_=context; context->AddRef();
  const auto revision=generation_;
  if (!desktop_client_.Start(module,executable,speech,[this,context,revision](const std::string &text) {
    if (generation_!=revision || !IsFocusedContext(context)) { CloseDesktopPanel(); return; }
    auto* edit=new(std::nothrow) DesktopCommitSession(this,context,revision,desktop_anchor_,Utf8ToWide(text));
    if (edit) {
      HRESULT result=E_FAIL;
      context->RequestEditSession(client_id_,edit,TF_ES_ASYNCDONTCARE|TF_ES_READWRITE,&result);
      edit->Release();
    }
    CloseDesktopPanel();
  })) {
    CloseDesktopPanel();
    OutputDebugStringW(L"Zhimo desktop companion launch failed; install full desktop package\n");
  }
}

HRESULT TextService::CommitDesktopText(TfEditCookie cookie, ITfContext* context,
    unsigned long long revision, ITfRange* anchor, const std::wstring &text) {
  if (revision!=generation_ || !IsFocusedContext(context) || text.empty() || text.size()>16384) return S_FALSE;
  auto scope=DetectScopeLocked(cookie,context);
  if (scope==1 || scope==std::numeric_limits<unsigned>::max()) return S_FALSE;
  TF_SELECTION selection{}; ULONG fetched=0;
  if (!anchor || FAILED(context->GetSelection(cookie,TF_DEFAULT_SELECTION,1,&selection,&fetched)) || fetched!=1 || !selection.range) return S_FALSE;
  LONG start=1,end=1;
  HRESULT compared=selection.range->CompareStart(cookie,anchor,TF_ANCHOR_START,&start);
  if (SUCCEEDED(compared)) compared=selection.range->CompareEnd(cookie,anchor,TF_ANCHOR_END,&end);
  selection.range->Release();
  if (FAILED(compared) || start!=0 || end!=0) return S_FALSE;
  ++generation_;
  return Commit(cookie,context,text);
}

bool TextService::IsFocusedContext(ITfContext* context) const {
  if (!thread_manager_) return false;
  ITfDocumentMgr* document = nullptr;
  ITfContext* current = nullptr;
  if (SUCCEEDED(thread_manager_->GetFocus(&document)) && document) {
    document->GetTop(&current);
    document->Release();
  }
  const bool matches = current && current == context;
  if (current) current->Release();
  return matches;
}

void TextService::ClickModeIndicator() {
  CloseDesktopPanel();
  if (!thread_manager_) return;
  ITfDocumentMgr* document = nullptr;
  ITfContext* context = nullptr;
  if (SUCCEEDED(thread_manager_->GetFocus(&document)) && document) {
    document->GetTop(&context);
    document->Release();
  }
  if (!context) return;
  // Same locked mode transition as Shift+Space; no focus-stealing keyboard injection.
  auto* edit = new (std::nothrow) EditSession(this, context, VK_SPACE, true, false, true);
  if (edit) {
    HRESULT result = E_FAIL;
    context->RequestEditSession(client_id_, edit, TF_ES_ASYNCDONTCARE | TF_ES_READWRITE, &result);
    edit->Release();
  }
  context->Release();
}


void TextService::ShowCandidates(TfEditCookie cookie, ITfContext* context,
                                 const std::vector<Candidate>& candidates) {
  if (candidates.empty()) { HideCandidates(); return; }
  RECT anchor{};
  bool positioned = false;
  ITfContextView* view = nullptr;
  HWND host = nullptr;
  if (SUCCEEDED(context->GetActiveView(&view)) && view) {
    view->GetWnd(&host);
    ITfRange* range = nullptr;
    BOOL clipped = FALSE;
    if (composition_) composition_->GetRange(&range);
    if (range) {
      positioned = SUCCEEDED(view->GetTextExt(cookie, range, &anchor, &clipped)) && !clipped;
      range->Release();
    }
    view->Release();
  }
  if (!positioned) {
    GUITHREADINFO info{};
    info.cbSize = sizeof(info);
    if (GetGUIThreadInfo(GetCurrentThreadId(), &info) && info.hwndCaret &&
        host && (info.hwndCaret == host || IsChild(host, info.hwndCaret))) {
      POINT start{info.rcCaret.left, info.rcCaret.top};
      POINT end{info.rcCaret.right, info.rcCaret.bottom};
      if (ClientToScreen(info.hwndCaret, &start) && ClientToScreen(info.hwndCaret, &end)) {
        anchor = {start.x, start.y, end.x, end.y};
        positioned = true;
      }
    }
  }
  if (!positioned) { HideCandidates(); return; }
  candidate_dpi_ = host ? static_cast<int>(GetDpiForWindow(host)) : 96;
  if (candidate_dpi_ <= 0) candidate_dpi_ = 96;
  const auto px = [this](int value) { return MulDiv(value, candidate_dpi_, 96); };
  if (!candidate_window_) {
    HMODULE module = nullptr;
    GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
        GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
        reinterpret_cast<LPCWSTR>(&CandidateWindowProc), &module);
    WNDCLASSW type{};
    type.lpfnWndProc = CandidateWindowProc;
    type.hInstance = module;
    type.hCursor = LoadCursor(nullptr, IDC_ARROW);
    type.lpszClassName = L"Zhimo.CompactCandidates.v1";
    if (!RegisterClassW(&type) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) return;
    candidate_window_ = CreateWindowExW(WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_TOPMOST,
        type.lpszClassName, L"Zhimo", WS_POPUP, 0, 0, 0, 0,
        nullptr, nullptr, module, this);
  }
  if (!candidate_window_) return;
  candidate_labels_.clear();
  candidate_cells_.clear();
  MONITORINFO monitor{};
  monitor.cbSize = sizeof(monitor);
  if (!GetMonitorInfoW(MonitorFromRect(&anchor, MONITOR_DEFAULTTONEAREST), &monitor)) return;
  HDC dc = GetDC(candidate_window_);
  HFONT font = CreateFontW(-px(17), 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE,
      DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
      DEFAULT_PITCH, L"Microsoft YaHei UI");
  const auto previous = SelectObject(dc, font);
  std::vector<int> widths;
  int total = px(16 + 48);
  for (size_t i = 0; i < candidates.size() && i < 9; ++i) {
    auto label = std::to_wstring(i + 1) + L"." + Utf8ToWide(candidates[i].display);
    SIZE size{};
    GetTextExtentPoint32W(dc, label.c_str(), static_cast<int>(label.size()), &size);
    widths.push_back(std::clamp(static_cast<int>(size.cx) + px(14), px(48), px(180)));
    total += widths.back();
    candidate_labels_.push_back(std::move(label));
  }
  SelectObject(dc, previous);
  DeleteObject(font);
  ReleaseDC(candidate_window_, dc);
  const int width = std::min(std::max(px(340), total),
      static_cast<int>(monitor.rcWork.right - monitor.rcWork.left) - px(8));
  const int height = px(66);
  int left = px(8);
  const int available = std::max(0, width - px(64));
  const int required = total - px(64);
  for (size_t i = 0; i < widths.size(); ++i) {
    const int cell_width = required > available ? MulDiv(widths[i], available, required) : widths[i];
    candidate_cells_.push_back({left, px(32), left + cell_width, height - px(4)});
    left += cell_width;
  }
  previous_page_ = {width - px(48), px(32), width - px(26), height - px(4)};
  next_page_ = {width - px(26), px(32), width - px(4), height - px(4)};
  const int x = std::clamp(static_cast<int>(anchor.left),
      static_cast<int>(monitor.rcWork.left), static_cast<int>(monitor.rcWork.right) - width);
  int y = anchor.bottom + px(2);
  if (y + height > monitor.rcWork.bottom) y = anchor.top - height - px(2);
  y = std::max(y, static_cast<int>(monitor.rcWork.top));
  SetWindowPos(candidate_window_, HWND_TOPMOST, x, y, width, height,
      SWP_NOACTIVATE | SWP_SHOWWINDOW);
  InvalidateRect(candidate_window_, nullptr, FALSE);
}

LRESULT CALLBACK TextService::CandidateWindowProc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
  auto* service = reinterpret_cast<TextService*>(GetWindowLongPtr(window, GWLP_USERDATA));
  if (message == WM_NCCREATE) {
    service = static_cast<TextService*>(reinterpret_cast<CREATESTRUCT*>(lparam)->lpCreateParams);
    SetWindowLongPtr(window, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(service));
  }
  if (message == WM_MOUSEACTIVATE) return MA_NOACTIVATE;
  if (message == WM_ERASEBKGND) return 1;
  if (service && message == WM_PAINT) { service->PaintCandidates(window); return 0; }
  if (service && message == WM_LBUTTONUP) {
    const POINT point{static_cast<short>(LOWORD(lparam)), static_cast<short>(HIWORD(lparam))};
    service->ClickCandidate(point);
    return 0;
  }
  return DefWindowProcW(window, message, wparam, lparam);
}

void TextService::PaintCandidates(HWND window) {
  PAINTSTRUCT paint{};
  HDC dc = BeginPaint(window, &paint);
  RECT bounds{};
  GetClientRect(window, &bounds);
  FillRect(dc, &bounds, static_cast<HBRUSH>(GetStockObject(WHITE_BRUSH)));
  const auto px = [this](int value) { return MulDiv(value, candidate_dpi_, 96); };
  HPEN border = CreatePen(PS_SOLID, 1, RGB(188, 191, 202));
  const auto old_pen = SelectObject(dc, border);
  const auto old_brush = SelectObject(dc, GetStockObject(NULL_BRUSH));
  Rectangle(dc, 0, 0, bounds.right, bounds.bottom);
  MoveToEx(dc, 1, px(30), nullptr);
  LineTo(dc, bounds.right - 1, px(30));
  SetBkMode(dc, TRANSPARENT);
  HFONT font = CreateFontW(-px(17), 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE,
      DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
      DEFAULT_PITCH, L"Microsoft YaHei UI");
  const auto old_font = SelectObject(dc, font);
  SetTextColor(dc, RGB(77, 111, 255));
  RECT header{px(9), px(3), bounds.right - px(52), px(29)};
  DrawTextW(dc, composition_text_.c_str(), -1, &header, DT_SINGLELINE | DT_VCENTER | DT_END_ELLIPSIS | DT_NOPREFIX);
  RECT brand{bounds.right - px(48), px(3), bounds.right - px(6), px(29)};
  SetTextColor(dc, RGB(154, 171, 222));
  DrawTextW(dc, L"知墨", -1, &brand, DT_SINGLELINE | DT_VCENTER | DT_RIGHT);
  for (size_t i = 0; i < candidate_labels_.size(); ++i) {
    SetTextColor(dc, i == 0 ? RGB(238, 65, 79) : RGB(77, 111, 255));
    auto cell = candidate_cells_[i];
    DrawTextW(dc, candidate_labels_[i].c_str(), -1, &cell,
        DT_SINGLELINE | DT_VCENTER | DT_END_ELLIPSIS | DT_NOPREFIX);
  }
  SetTextColor(dc, RGB(111, 115, 143));
  DrawTextW(dc, L"‹", -1, &previous_page_, DT_SINGLELINE | DT_VCENTER | DT_CENTER);
  DrawTextW(dc, L"›", -1, &next_page_, DT_SINGLELINE | DT_VCENTER | DT_CENTER);
  SelectObject(dc, old_font);
  SelectObject(dc, old_pen);
  SelectObject(dc, old_brush);
  DeleteObject(font);
  DeleteObject(border);
  EndPaint(window, &paint);
}

void TextService::ClickCandidate(POINT point) {
  WPARAM key = 0;
  for (size_t i = 0; i < candidate_cells_.size(); ++i) {
    if (PtInRect(&candidate_cells_[i], point)) key = '1' + i;
  }
  if (PtInRect(&previous_page_, point)) key = VK_PRIOR;
  if (PtInRect(&next_page_, point)) key = VK_NEXT;
  if (!key || !composition_ || !composing_) return;
  ITfRange* range = nullptr;
  ITfContext* owner = nullptr;
  if (SUCCEEDED(composition_->GetRange(&range)) && range) {
    range->GetContext(&owner);
    range->Release();
  }
  if (owner) { (void)ProcessKey(owner, key); owner->Release(); }
}
void TextService::HideCandidates() {
  if (candidate_window_) ShowWindow(candidate_window_, SW_HIDE);
}

}  // namespace zhimo
#endif
