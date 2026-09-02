#ifdef _WIN32
#include "text_service.h"

#include <inputscope.h>
#include <objbase.h>
#include <shlobj.h>

#include <algorithm>
#include <memory>
#include <new>
#include <string_view>

namespace shurufa {
namespace {

std::filesystem::path DataDirectory() {
  PWSTR path = nullptr;
  std::filesystem::path result;
  if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_LocalAppData, KF_FLAG_CREATE, nullptr, &path))) {
    result = std::filesystem::path(path) / L"Shurufa" / L"user";
    CoTaskMemFree(path);
  } else {
    result = std::filesystem::temp_directory_path() / L"Shurufa" / L"user";
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
  EditSession(TextService* service, ITfContext* context, std::vector<Action> actions)
      : service_(service), context_(context), actions_(std::move(actions)) {
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
    return service_->ApplyActions(cookie, context_, actions_);
  }

 private:
  LONG references_ = 1;
  TextService* service_;
  ITfContext* context_;
  std::vector<Action> actions_;
};

}  // namespace

TextService::TextService() : session_(DataDirectory()) {}
TextService::~TextService() { Deactivate(); }

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
  ITfKeystrokeMgr* keystrokes = nullptr;
  const HRESULT result = manager->QueryInterface(IID_PPV_ARGS(&keystrokes));
  if (SUCCEEDED(result)) {
    const HRESULT advise = keystrokes->AdviseKeyEventSink(id, this, TRUE);
    keystrokes->Release();
    return advise;
  }
  return result;
}
HRESULT TextService::Deactivate() {
  HideCandidates();
  if (thread_manager_) {
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

HRESULT TextService::OnSetFocus(BOOL) { return S_OK; }
HRESULT TextService::OnTestKeyDown(ITfContext* context, WPARAM key, LPARAM, BOOL* eaten) {
  if (!eaten) return E_INVALIDARG;
  *eaten = ShouldEat(context, key);
  return S_OK;
}
HRESULT TextService::OnKeyDown(ITfContext* context, WPARAM key, LPARAM, BOOL* eaten) {
  if (!eaten) return E_INVALIDARG;
  *eaten = ProcessKey(context, key);
  return S_OK;
}
HRESULT TextService::OnTestKeyUp(ITfContext*, WPARAM, LPARAM, BOOL* eaten) {
  if (!eaten) return E_INVALIDARG;
  *eaten = FALSE;
  return S_OK;
}
HRESULT TextService::OnKeyUp(ITfContext*, WPARAM, LPARAM, BOOL* eaten) {
  if (!eaten) return E_INVALIDARG;
  *eaten = FALSE;
  return S_OK;
}
HRESULT TextService::OnPreservedKey(ITfContext*, REFGUID, BOOL* eaten) {
  if (!eaten) return E_INVALIDARG;
  *eaten = FALSE;
  return S_OK;
}
HRESULT TextService::OnCompositionTerminated(TfEditCookie, ITfComposition* composition) {
  if (composition_ == composition) {
    composition_->Release();
    composition_ = nullptr;
  }
  composing_ = false;
  HideCandidates();
  (void)session_.command(3);
  return S_OK;
}

bool TextService::ShouldEat(ITfContext* context, WPARAM key) const {
  if (!context || GetKeyState(VK_CONTROL) < 0 || GetKeyState(VK_MENU) < 0) return false;
  if (DetectScope(context) == 1) return false;
  if (key == VK_SPACE && GetKeyState(VK_SHIFT) < 0) return true;
  if ((key >= 'A' && key <= 'Z') || key == VK_OEM_7) return true;
  return composing_ && (key == VK_BACK || key == VK_SPACE || key == VK_RETURN ||
                        key == VK_ESCAPE || (key >= '1' && key <= '9'));
}

bool TextService::ProcessKey(ITfContext* context, WPARAM key) {
  if (!ShouldEat(context, key)) return false;
  const auto scope = DetectScope(context);
  (void)session_.set_input_scope(scope);
  (void)session_.set_context("windows.tsf", scope != 1);
  if (key == VK_SPACE && GetKeyState(VK_SHIFT) < 0) {
    if (composing_) (void)session_.command(3);
    pinyin_ = !pinyin_;
    if (!session_.switch_engine(pinyin_ ? "pinyin.reference" : "latin")) return false;
  } else if (key >= '1' && key <= '9' && composing_) {
    const auto actions = session_.structured_actions();
    for (const auto& action : actions) {
      if (action.kind == 2 && static_cast<size_t>(key - '1') < action.candidates.size()) {
        if (!session_.select(action.candidates[static_cast<size_t>(key - '1')].id)) return false;
        break;
      }
    }
  } else if (key >= 'A' && key <= 'Z') {
    const bool uppercase = (GetKeyState(VK_SHIFT) < 0) != ((GetKeyState(VK_CAPITAL) & 1) != 0);
    char value[2] = {static_cast<char>(pinyin_ || !uppercase ? key - 'A' + 'a' : key), 0};
    if (!session_.feed(value)) return false;
  } else if (key == VK_OEM_7) {
    if (!session_.feed("'")) return false;
  } else {
    unsigned command = 3;
    if (key == VK_BACK) command = 0;
    if (key == VK_RETURN) command = 1;
    if (key == VK_SPACE) command = 2;
    if (!session_.command(command)) return false;
  }
  auto* edit = new (std::nothrow) EditSession(this, context, session_.structured_actions());
  if (!edit) return false;
  HRESULT session_result = E_FAIL;
  const HRESULT request = context->RequestEditSession(client_id_, edit,
      TF_ES_SYNC | TF_ES_READWRITE, &session_result);
  edit->Release();
  return SUCCEEDED(request) && SUCCEEDED(session_result);
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
    composition_->GetRange(&range);
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
  const HRESULT result = range->SetText(cookie, 0, text.c_str(), static_cast<LONG>(text.size()));
  range->Collapse(cookie, TF_ANCHOR_END);
  TF_SELECTION selection{range, {TF_AE_END, FALSE}};
  context->SetSelection(cookie, 1, &selection);
  range->Release();
  composing_ = !text.empty();
  return result;
}

HRESULT TextService::Commit(TfEditCookie cookie, ITfContext* context, const std::wstring& text) {
  HRESULT result = SetComposition(cookie, context, text);
  if (SUCCEEDED(result)) EndComposition(cookie);
  return result;
}
void TextService::EndComposition(TfEditCookie cookie) {
  if (composition_) {
    composition_->EndComposition(cookie);
    composition_->Release();
    composition_ = nullptr;
  }
  composing_ = false;
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

void TextService::ShowCandidates(TfEditCookie cookie, ITfContext* context,
                                 const std::vector<Candidate>& candidates) {
  if (candidates.empty()) { HideCandidates(); return; }
  std::wstring text;
  for (size_t index = 0; index < candidates.size() && index < 9; ++index) {
    text += std::to_wstring(index + 1) + L". " + Utf8ToWide(candidates[index].display);
    if (!candidates[index].annotation.empty())
      text += L"  " + Utf8ToWide(candidates[index].annotation);
    text += L"\r\n";
  }
  if (!candidate_window_) {
    candidate_window_ = CreateWindowExW(WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_TOPMOST,
        L"STATIC", L"", WS_POPUP | WS_BORDER | SS_LEFT, 0, 0, 360, 180, nullptr, nullptr,
        GetModuleHandleW(nullptr), nullptr);
  }
  RECT rect{100, 100, 100, 100};
  ITfContextView* view = nullptr;
  BOOL clipped = FALSE;
  if (candidate_window_ && SUCCEEDED(context->GetActiveView(&view))) {
    ITfRange* range = nullptr;
    if (composition_) composition_->GetRange(&range);
    if (range) { view->GetTextExt(cookie, range, &rect, &clipped); range->Release(); }
    view->Release();
    SetWindowTextW(candidate_window_, text.c_str());
    SetWindowPos(candidate_window_, HWND_TOPMOST, rect.left, rect.bottom, 420, 190,
                 SWP_NOACTIVATE | SWP_SHOWWINDOW);
  }
}
void TextService::HideCandidates() {
  if (candidate_window_) ShowWindow(candidate_window_, SW_HIDE);
}

}  // namespace shurufa
#endif
