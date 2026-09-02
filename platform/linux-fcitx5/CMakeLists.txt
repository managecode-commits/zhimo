cmake_minimum_required(VERSION 3.16)
project(fcitx5-shurufa VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(REQUIRED_FCITX_VERSION 5.0.0)

include(GNUInstallDirs)
find_package(Fcitx5Core ${REQUIRED_FCITX_VERSION} REQUIRED)

set(SHURUFA_IME_LIBRARY "" CACHE FILEPATH "Path to libime_ffi.so")
if(NOT SHURUFA_IME_LIBRARY)
  find_library(SHURUFA_IME_LIBRARY NAMES ime_ffi REQUIRED)
endif()

add_fcitx5_addon(shurufa shurufa.cpp)
target_include_directories(shurufa PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../../include")
target_link_libraries(shurufa Fcitx5::Core "${SHURUFA_IME_LIBRARY}")
set_target_properties(shurufa PROPERTIES INSTALL_RPATH "$ORIGIN/..")
install(TARGETS shurufa DESTINATION "${CMAKE_INSTALL_LIBDIR}/fcitx5")
install(FILES "${SHURUFA_IME_LIBRARY}" DESTINATION "${CMAKE_INSTALL_LIBDIR}")

configure_file(shurufa-addon.conf.in shurufa-addon.conf @ONLY)
install(FILES "${CMAKE_CURRENT_BINARY_DIR}/shurufa-addon.conf"
        RENAME shurufa.conf DESTINATION "${FCITX_INSTALL_PKGDATADIR}/addon")
install(FILES shurufa-inputmethod.conf
        RENAME shurufa.conf DESTINATION "${FCITX_INSTALL_PKGDATADIR}/inputmethod")
