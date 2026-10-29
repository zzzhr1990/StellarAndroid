# Shared implementation, compiled privately into each adapter. No cross-DSO C++
# objects or credentials: the native instance owns its own token registry.
add_library(stellar_remote_reader OBJECT smb_reader.cpp nfs_reader.cpp callback_reader.cpp cached_file.cpp)
set_target_properties(stellar_remote_reader PROPERTIES POSITION_INDEPENDENT_CODE ON CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
target_compile_features(stellar_remote_reader PRIVATE cxx_std_17)
target_compile_options(stellar_remote_reader PRIVATE -Wall -Wextra -Werror)
target_include_directories(stellar_remote_reader PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
set(REMOTE_VENDOR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/vendor")
target_include_directories(stellar_remote_reader PRIVATE "${REMOTE_VENDOR}/libsmb2/include" "${REMOTE_VENDOR}/libnfs/include")
