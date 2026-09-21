# Copyright © 2026 立方田 <managecode@gmail.com>
cmake_minimum_required(VERSION 3.22)
project(zhimo_android LANGUAGES CXX)

add_library(zhimo_ime SHARED IMPORTED)
set_target_properties(zhimo_ime PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libime_ffi.so"
    IMPORTED_NO_SONAME TRUE)

add_library(zhimo_android SHARED native_ime.cpp)
target_compile_features(zhimo_android PRIVATE cxx_std_17)
target_include_directories(zhimo_android PRIVATE "${ZHIMO_ROOT}/include")
target_link_libraries(zhimo_android PRIVATE zhimo_ime log)

# Android speech uses ASR-only sherpa JNI; Whisper is desktop-only.
