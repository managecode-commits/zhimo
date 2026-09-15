// Copyright © 2026 立方田 <managecode@gmail.com>
#pragma once
#include <windows.h>
#include <objbase.h>
#include <cstring>
#include <filesystem>
#include "tsf_guids.h"

inline bool ProbeDllExports() {
  wchar_t path[32768]{};
  const DWORD length = GetModuleFileNameW(nullptr, path, 32768);
  if (!length || length >= 32768) return false;
  auto dll = std::filesystem::path(path).parent_path() / L"ZhimoTsf.dll";
  if (!std::filesystem::exists(dll)) {
    dll = std::filesystem::path(path).parent_path() / L"libZhimoTsf.dll";
  }
  HMODULE module = LoadLibraryW(dll.c_str());
  if (!module) return false;
  using GetFactory = HRESULT (STDAPICALLTYPE*)(REFCLSID, REFIID, void**);
  const auto address = GetProcAddress(module, "DllGetClassObject");
  GetFactory get_factory = nullptr;
  static_assert(sizeof(get_factory) == sizeof(address));
  std::memcpy(&get_factory, &address, sizeof(get_factory));
  bool valid = get_factory && GetProcAddress(module, "DllCanUnloadNow") &&
      GetProcAddress(module, "DllRegisterServer") && GetProcAddress(module, "DllUnregisterServer");
  if (valid) {
    IClassFactory* factory = nullptr;
    valid = SUCCEEDED(get_factory(CLSID_ZhimoTextService, IID_IClassFactory,
                                   reinterpret_cast<void**>(&factory))) && factory;
    if (factory) factory->Release();
  }
  FreeLibrary(module);
  return valid;
}
