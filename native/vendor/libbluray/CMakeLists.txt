cmake_minimum_required(VERSION 3.22)
project(stellar_bluray C)
set(BLURAY_VERSION_MAJOR 1)
set(BLURAY_VERSION_MINOR 5)
set(BLURAY_VERSION_MICRO 0)
configure_file(src/libbluray/bluray-version.h.in ${CMAKE_CURRENT_BINARY_DIR}/libbluray/bluray-version.h @ONLY)
add_library(bluray STATIC
  src/libbluray/bluray.c
  src/libbluray/register.c
  src/libbluray/bdj/bdj.c
  src/libbluray/bdj/bdjo_parse.c
  src/libbluray/bdj/native/bdjo.c
  src/libbluray/bdj/native/java_awt_BDFontMetrics.c
  src/libbluray/bdj/native/java_awt_BDGraphics.c
  src/libbluray/bdj/native/org_videolan_Libbluray.c
  src/libbluray/bdj/native/org_videolan_Logger.c
  src/libbluray/bdj/native/register_native.c
  src/libbluray/bdj/native/util.c
  src/libbluray/bdnav/navigation.c
  src/libbluray/bdnav/mpls_parse.c
  src/libbluray/bdnav/meta_parse.c
  src/libbluray/bdnav/extdata_parse.c
  src/libbluray/bdnav/bdmv_parse.c
  src/libbluray/bdnav/sound_parse.c
  src/libbluray/bdnav/uo_mask.c
  src/libbluray/bdnav/bdid_parse.c
  src/libbluray/bdnav/clpi_parse.c
  src/libbluray/bdnav/index_parse.c
  src/libbluray/decoders/textst_decode.c
  src/libbluray/decoders/textst_render.c
  src/libbluray/decoders/pg_decode.c
  src/libbluray/decoders/ig_decode.c
  src/libbluray/decoders/pes_buffer.c
  src/libbluray/decoders/graphics_processor.c
  src/libbluray/decoders/m2ts_demux.c
  src/libbluray/decoders/rle.c
  src/libbluray/decoders/m2ts_filter.c
  src/libbluray/decoders/graphics_controller.c
  src/libbluray/disc/aacs.c
  src/libbluray/disc/bdplus.c
  src/libbluray/disc/dec.c
  src/libbluray/disc/disc.c
  src/libbluray/disc/properties.c
  src/libbluray/disc/udf_fs.c
  src/libbluray/hdmv/mobj_print.c
  src/libbluray/hdmv/mobj_parse.c
  src/libbluray/hdmv/hdmv_vm.c
  src/file/file.c
  src/file/filesystem.c
  src/util/bits.c
  src/util/logging.c
  src/util/refcnt.c
  src/util/time.c
  src/util/array.c
  src/util/mutex.c
  src/util/event_queue.c
  src/util/strutl.c
  src/file/dir_posix.c
  src/file/dirs_xdg.c
  src/file/dl_posix.c
  src/file/file_posix.c
  src/file/mount.c
)
set_target_properties(bluray PROPERTIES POSITION_INDEPENDENT_CODE ON C_VISIBILITY_PRESET hidden)
target_include_directories(bluray PUBLIC src ${CMAKE_CURRENT_BINARY_DIR} PRIVATE ${CMAKE_CURRENT_BINARY_DIR}/libbluray android src/libbluray src/libbluray/disc)
target_compile_definitions(bluray PRIVATE HAVE_CONFIG_H=1 _POSIX_C_SOURCE=200809L)
target_link_libraries(bluray PRIVATE udfread dl)
