cmake_minimum_required(VERSION 3.22)
project(stellar_device_info LANGUAGES CXX)
add_library(stellar_device_info SHARED vulkan_info.cpp)
target_compile_features(stellar_device_info PRIVATE cxx_std_17)
target_compile_options(stellar_device_info PRIVATE -Wall -Wextra -Werror)
target_link_libraries(stellar_device_info PRIVATE dl)
target_link_options(stellar_device_info PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL")

# Reuse the playback readers without loading a playback engine for LAN serving.
set(SOURCE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../player-source/src/main/cpp")
include("${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/dependencies.cmake")
add_subdirectory("${SOURCE_ROOT}" remote-reader)
add_library(stellar_source SHARED "${SOURCE_ROOT}/remote_reader_jni.cpp")
target_compile_features(stellar_source PRIVATE cxx_std_17)
target_compile_options(stellar_source PRIVATE -Wall -Wextra -Werror)
target_link_libraries(stellar_source PRIVATE stellar_remote_reader
    smb2
    nfs)
target_link_options(stellar_source PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL")
