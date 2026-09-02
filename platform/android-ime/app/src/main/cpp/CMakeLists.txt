cmake_minimum_required(VERSION 3.22)
project(shurufa_android LANGUAGES CXX)

add_library(shurufa_ime SHARED IMPORTED)
set_target_properties(shurufa_ime PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libime_ffi.so")

add_library(shurufa_android SHARED native_ime.cpp)
target_compile_features(shurufa_android PRIVATE cxx_std_17)
target_include_directories(shurufa_android PRIVATE "${SHURUFA_ROOT}/include")
target_link_libraries(shurufa_android PRIVATE shurufa_ime log)
