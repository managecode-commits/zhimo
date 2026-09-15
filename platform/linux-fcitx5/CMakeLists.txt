# Copyright © 2026 立方田 <managecode@gmail.com>
cmake_minimum_required(VERSION 3.16)
project(fcitx5-zhimo VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(REQUIRED_FCITX_VERSION 5.0.0)

include(GNUInstallDirs)
find_package(Fcitx5Core ${REQUIRED_FCITX_VERSION} REQUIRED)

set(ZHIMO_IME_LIBRARY "" CACHE FILEPATH "Path to libime_ffi.so")
if(NOT ZHIMO_IME_LIBRARY)
  find_library(ZHIMO_IME_LIBRARY NAMES ime_ffi REQUIRED)
endif()

if(COMMAND add_fcitx5_addon)
  add_fcitx5_addon(zhimo zhimo.cpp)
else()
  # Fcitx 5.0 as shipped by Debian 12 / Ubuntu 22.04 has no addon helper.
  add_library(zhimo MODULE zhimo.cpp)
endif()
target_include_directories(zhimo PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../../include")
target_link_libraries(zhimo Fcitx5::Core "${ZHIMO_IME_LIBRARY}")
set_target_properties(zhimo PROPERTIES INSTALL_RPATH "$ORIGIN/../zhimo")
install(TARGETS zhimo DESTINATION "${CMAKE_INSTALL_LIBDIR}/fcitx5")
install(FILES "${ZHIMO_IME_LIBRARY}" DESTINATION "${CMAKE_INSTALL_LIBDIR}/zhimo")
install(DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../../models/handwriting"
        DESTINATION "${CMAKE_INSTALL_DATADIR}/zhimo/models")

configure_file(zhimo-addon.conf.in zhimo-addon.conf @ONLY)
install(FILES "${CMAKE_CURRENT_BINARY_DIR}/zhimo-addon.conf"
        RENAME zhimo.conf DESTINATION "${FCITX_INSTALL_PKGDATADIR}/addon")
install(FILES zhimo-inputmethod.conf
        RENAME zhimo.conf DESTINATION "${FCITX_INSTALL_PKGDATADIR}/inputmethod")
install(FILES "${CMAKE_CURRENT_SOURCE_DIR}/../../assets/branding/zhimo.svg"
        DESTINATION "${CMAKE_INSTALL_DATADIR}/icons/hicolor/scalable/apps")
