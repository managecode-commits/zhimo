// Copyright © 2026 立方田 <managecode@gmail.com>
#ifdef _WIN32
#include <msctf.h>
#include <objbase.h>
#include <windows.h>

#include <new>
#include <string>

#include "text_service.h"
#include "tsf_guids.h"
#include "language_bar.h"

namespace {
HINSTANCE module_instance = nullptr;

std::wstring GuidString(REFGUID guid) {
  wchar_t value[64] = {};
  return StringFromGUID2(guid, value, 64) ? value : L"";
}

class ClassFactory final : public IClassFactory {
 public:
  HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void** object) override {
    if (!object) return E_INVALIDARG;
    *object = nullptr;
    if (iid == IID_IUnknown || iid == IID_IClassFactory) {
      *object = static_cast<IClassFactory*>(this);
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
  HRESULT STDMETHODCALLTYPE CreateInstance(IUnknown* outer, REFIID iid, void** object) override {
    if (!object) return E_POINTER;
    *object = nullptr;
    if (outer) return CLASS_E_NOAGGREGATION;
    zhimo::TextService* service = nullptr;
    try { service = new (std::nothrow) zhimo::TextService(); }
    catch (const std::bad_alloc&) { return E_OUTOFMEMORY; }
    catch (...) { return E_FAIL; }
    if (!service) return E_OUTOFMEMORY;
    const HRESULT result = service->QueryInterface(iid, object);
    service->Release();
    return result;
  }
  HRESULT STDMETHODCALLTYPE LockServer(BOOL) override { return S_OK; }

 private:
  LONG references_ = 1;
};

HRESULT RegisterComServer() {
  wchar_t module_path[MAX_PATH] = {};
  if (!GetModuleFileNameW(module_instance, module_path, MAX_PATH)) return HRESULT_FROM_WIN32(GetLastError());
  const std::wstring key = L"Software\\Classes\\CLSID\\" +
                           GuidString(CLSID_ZhimoTextService) + L"\\InprocServer32";
  HKEY handle = nullptr;
  LONG status = RegCreateKeyExW(HKEY_CURRENT_USER, key.c_str(), 0, nullptr, 0,
                                KEY_SET_VALUE, nullptr, &handle, nullptr);
  if (status != ERROR_SUCCESS) return HRESULT_FROM_WIN32(status);
  status = RegSetValueExW(handle, nullptr, 0, REG_SZ,
      reinterpret_cast<const BYTE*>(module_path),
      static_cast<DWORD>((wcslen(module_path) + 1) * sizeof(wchar_t)));
  const wchar_t model[] = L"Apartment";
  if (status == ERROR_SUCCESS) status = RegSetValueExW(handle, L"ThreadingModel", 0, REG_SZ,
      reinterpret_cast<const BYTE*>(model), sizeof(model));
  RegCloseKey(handle);
  return HRESULT_FROM_WIN32(status);
}

HRESULT RegisterProfile() {
  ITfInputProcessorProfiles* profiles = nullptr;
  HRESULT result = CoCreateInstance(CLSID_TF_InputProcessorProfiles, nullptr,
      CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&profiles));
  if (FAILED(result)) return result;
  result = profiles->Register(CLSID_ZhimoTextService);
  const wchar_t description[] = L"知墨输入法 · Zhimo";
  wchar_t icon_path[32768] = {};
  const DWORD icon_length = GetModuleFileNameW(module_instance, icon_path, 32768);
  if (!icon_length || icon_length >= 32768) {
    profiles->Release();
    return E_FAIL;
  }
  if (SUCCEEDED(result)) result = profiles->AddLanguageProfile(
      CLSID_ZhimoTextService, 0x0804, GUID_ZhimoProfile, description,
      static_cast<ULONG>(wcslen(description)), icon_path, icon_length, 0);
  profiles->Release();
  if (FAILED(result)) return result;
  ITfCategoryMgr* categories = nullptr;
  result = CoCreateInstance(CLSID_TF_CategoryMgr, nullptr, CLSCTX_INPROC_SERVER,
                            IID_PPV_ARGS(&categories));
  if (FAILED(result)) return result;
  const GUID category_ids[] = {GUID_TFCAT_TIP_KEYBOARD, zhimo::kSystraySupport
#ifdef _MSC_VER
                               ,
                               GUID_TFCAT_TIPCAP_IMMERSIVESUPPORT
#endif
  };
  for (const auto& category : category_ids) {
    result = categories->RegisterCategory(CLSID_ZhimoTextService, category,
                                          CLSID_ZhimoTextService);
    if (FAILED(result)) break;
  }
  categories->Release();
  return result;
}

void UnregisterProfile() {
  ITfCategoryMgr* categories = nullptr;
  if (SUCCEEDED(CoCreateInstance(CLSID_TF_CategoryMgr, nullptr, CLSCTX_INPROC_SERVER,
                                 IID_PPV_ARGS(&categories)))) {
    const GUID category_ids[] = {GUID_TFCAT_TIP_KEYBOARD, zhimo::kSystraySupport
#ifdef _MSC_VER
                                 ,
                                 GUID_TFCAT_TIPCAP_IMMERSIVESUPPORT
#endif
    };
    for (const auto& category : category_ids)
      categories->UnregisterCategory(CLSID_ZhimoTextService, category,
                                     CLSID_ZhimoTextService);
    categories->Release();
  }
  ITfInputProcessorProfiles* profiles = nullptr;
  if (SUCCEEDED(CoCreateInstance(CLSID_TF_InputProcessorProfiles, nullptr,
                                 CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&profiles)))) {
    profiles->RemoveLanguageProfile(CLSID_ZhimoTextService, 0x0804, GUID_ZhimoProfile);
    profiles->Unregister(CLSID_ZhimoTextService);
    profiles->Release();
  }
}
}  // namespace

BOOL APIENTRY DllMain(HINSTANCE instance, DWORD reason, LPVOID) {
  if (reason == DLL_PROCESS_ATTACH) {
    module_instance = instance;
    DisableThreadLibraryCalls(instance);
  }
  return TRUE;
}

STDAPI DllGetClassObject(REFCLSID clsid, REFIID iid,
                                                            void** object) {
  if (!IsEqualCLSID(clsid, CLSID_ZhimoTextService)) return CLASS_E_CLASSNOTAVAILABLE;
  auto* factory = new (std::nothrow) ClassFactory();
  if (!factory) return E_OUTOFMEMORY;
  const HRESULT result = factory->QueryInterface(iid, object);
  factory->Release();
  return result;
}
STDAPI DllCanUnloadNow() { return S_FALSE; }
STDAPI DllRegisterServer() {
  HRESULT result = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
  const bool uninitialize = SUCCEEDED(result);
  result = RegisterComServer();
  if (SUCCEEDED(result)) result = RegisterProfile();
  if (uninitialize) CoUninitialize();
  return result;
}
STDAPI DllUnregisterServer() {
  HRESULT result = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
  const bool uninitialize = SUCCEEDED(result);
  UnregisterProfile();
  const std::wstring key = L"Software\\Classes\\CLSID\\" + GuidString(CLSID_ZhimoTextService);
  RegDeleteTreeW(HKEY_CURRENT_USER, key.c_str());
  if (uninitialize) CoUninitialize();
  return S_OK;
}
#endif
