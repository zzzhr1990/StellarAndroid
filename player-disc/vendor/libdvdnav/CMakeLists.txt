cmake_minimum_required(VERSION 3.22)
project(stellar_dvdnav C)
set(DVDNAV_MAJOR 7)
set(DVDNAV_MINOR 0)
set(DVDNAV_SUB 0)
configure_file(src/dvdnav/version.h.in ${CMAKE_CURRENT_BINARY_DIR}/dvdnav/version.h @ONLY)
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/config.h "#define HAVE_GETTIMEOFDAY 1\n#define HAVE_SYS_TIME_H 1\n#define HAVE_UNISTD_H 1\n#define HAVE_INTTYPES_H 1\n#define HAVE_STDINT_H 1\n#define VERSION \"7.0.0\"\n")
add_library(dvdnav STATIC src/dvdnav.c src/highlight.c src/logger.c src/navigation.c
    src/read_cache.c src/searching.c src/settings.c src/vm/decoder.c src/vm/getset.c
    src/vm/play.c src/vm/vm.c src/vm/vmcmd.c src/vm/vmget.c reader_bridge.c)
set_target_properties(dvdnav PROPERTIES POSITION_INDEPENDENT_CODE ON C_STANDARD 17 C_VISIBILITY_PRESET hidden)
target_compile_definitions(dvdnav PRIVATE HAVE_CONFIG_H _DEFAULT_SOURCE)
# Redirect only the VM's initial reader acquisition. Upstream sources stay intact.
set_source_files_properties(src/vm/vm.c PROPERTIES COMPILE_DEFINITIONS "DVDOpenStream2=stellar_dvdnav_reader")
target_include_directories(dvdnav PUBLIC src ${CMAKE_CURRENT_BINARY_DIR} PRIVATE src/dvdnav src/vm)
target_link_libraries(dvdnav PUBLIC dvdread)
