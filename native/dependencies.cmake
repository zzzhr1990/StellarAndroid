# Built from the bundled source archives by native/build.py.
set(STELLAR_NATIVE_PREFIX "${CMAKE_CURRENT_LIST_DIR}/prefix/${ANDROID_ABI}")
if(NOT EXISTS "${STELLAR_NATIVE_PREFIX}/include/mpv/client.h")
    message(FATAL_ERROR "Native dependencies are missing. Run python3 native/build.py first; see README.md.")
endif()
foreach(component mpv avcodec avformat avutil avfilter swscale swresample smb2 nfs)
    if(NOT TARGET ${component})
        add_library(${component} SHARED IMPORTED GLOBAL)
        set_target_properties(${component} PROPERTIES
            IMPORTED_LOCATION "${STELLAR_NATIVE_PREFIX}/lib/lib${component}.so"
            INTERFACE_INCLUDE_DIRECTORIES "${STELLAR_NATIVE_PREFIX}/include")
    endif()
endforeach()

set_property(TARGET mpv PROPERTY INTERFACE_LINK_LIBRARIES "avformat;avcodec;avfilter;avutil;swscale;swresample")
