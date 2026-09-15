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

# Sources are fetched and SHA-256 checked by tools/prepare-offline-speech.sh.
set(WHISPER_SOURCE "${ZHIMO_ROOT}/target/vendor/whisper.cpp-1.9.1")
if(NOT EXISTS "${WHISPER_SOURCE}/include/whisper.h")
    message(FATAL_ERROR "Run tools/prepare-offline-speech.sh before building Android")
endif()
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
endif()
# Quantized matrix kernels must remain optimized in installable debug builds too.
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O3")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O3")
add_subdirectory("${WHISPER_SOURCE}" "whisper-build")
add_library(zhimo_speech SHARED offline_speech.cpp "${ZHIMO_ROOT}/platform/shared-speech/speech.cpp")
target_compile_features(zhimo_speech PRIVATE cxx_std_17)
target_include_directories(zhimo_speech PRIVATE "${ZHIMO_ROOT}/include")
target_link_libraries(zhimo_speech PRIVATE whisper log)
