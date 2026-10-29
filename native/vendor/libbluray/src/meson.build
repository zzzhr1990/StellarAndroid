# Copyright (C) 2025 Rob Hall
# SPDX-License-Identifier: MIT

# libbluray source files
libbluray_src = files(
    'libbluray/bluray.c',
    'libbluray/register.c',
    'libbluray/bdj/bdj.c',
    'libbluray/bdj/bdjo_parse.c',
    'libbluray/bdj/bdjo_parse.c',
    'libbluray/bdj/native/bdjo.c',
    'libbluray/bdj/native/java_awt_BDFontMetrics.c',
    'libbluray/bdj/native/java_awt_BDGraphics.c',
    'libbluray/bdj/native/org_videolan_Libbluray.c',
    'libbluray/bdj/native/org_videolan_Logger.c',
    'libbluray/bdj/native/register_native.c',
    'libbluray/bdj/native/util.c',
    'libbluray/bdnav/navigation.c',
    'libbluray/bdnav/mpls_parse.c',
    'libbluray/bdnav/meta_parse.c',
    'libbluray/bdnav/extdata_parse.c',
    'libbluray/bdnav/bdmv_parse.c',
    'libbluray/bdnav/sound_parse.c',
    'libbluray/bdnav/uo_mask.c',
    'libbluray/bdnav/bdid_parse.c',
    'libbluray/bdnav/clpi_parse.c',
    'libbluray/bdnav/index_parse.c',
    'libbluray/decoders/textst_decode.c',
    'libbluray/decoders/textst_render.c',
    'libbluray/decoders/pg_decode.c',
    'libbluray/decoders/ig_decode.c',
    'libbluray/decoders/pes_buffer.c',
    'libbluray/decoders/graphics_processor.c',
    'libbluray/decoders/m2ts_demux.c',
    'libbluray/decoders/rle.c',
    'libbluray/decoders/m2ts_filter.c',
    'libbluray/decoders/graphics_controller.c',
    'libbluray/disc/aacs.c',
    'libbluray/disc/bdplus.c',
    'libbluray/disc/dec.c',
    'libbluray/disc/disc.c',
    'libbluray/disc/properties.c',
    'libbluray/disc/udf_fs.c',
    'libbluray/hdmv/mobj_print.c',
    'libbluray/hdmv/mobj_parse.c',
    'libbluray/hdmv/hdmv_vm.c',
    'file/file.c',
    'file/filesystem.c',
    'util/bits.c',
    'util/logging.c',
    'util/refcnt.c',
    'util/time.c',
    'util/array.c',
    'util/mutex.c',
    'util/event_queue.c',
    'util/strutl.c',
)

# Platform-specific source files
if host_machine.system() == 'windows'
    libbluray_src += files(
        'file/dir_win32.c',
        'file/dirs_win32.c',
        'file/dl_win32.c',
        'file/file_win32.c',
        'file/mount.c',
    )

    if freetype_dependency.found()
        # for EnumFontFamiliesExW
        extra_dependencies += cc.find_library('gdi32')
    endif

elif host_machine.system() == 'darwin'
    libbluray_src += files(
        'file/dir_posix.c',
        'file/dirs_darwin.c',
        'file/dl_posix.c',
        'file/file_posix.c',
        'file/mount_darwin.c',
    )
else
    libbluray_src += files(
        'file/dir_posix.c',
        'file/dirs_xdg.c',
        'file/dl_posix.c',
        'file/file_posix.c',
    )
    if cdata.has('HAVE_GETFSSTAT') or cdata.has('HAVE_GETVFSSTAT')
        libbluray_src += files('file/mount_getfsstat.c')
    else
        libbluray_src += files('file/mount.c')
    endif
endif

#
# Library definitions
#

if host_machine.system() == 'windows' and get_option('default_library') == 'static'
    api_export_flags = []
else
    api_export_flags = '-DBLURAY_API_EXPORT'
endif

# The final libbluray library
libbluray = library('bluray', libbluray_src,
    include_directories: libbluray_inc_dirs,
    dependencies: [
        fontconfig_dependency,
        freetype_dependency,
        libdl_dependency,
        libudfread_dependency,
        libxml2_dependency,
        thread_dependency,
        extra_dependencies,
    ],
    c_args: [api_export_flags],
    gnu_symbol_visibility: 'hidden',
    version: libbluray_soname_version,
    install: true,
)

# Make the library usable as a dependency in this and other projects
libbluray_dep = declare_dependency(link_with: libbluray,
    sources: libbluray_internal_headers,
    include_directories: '.')

meson.override_dependency('libbluray', libbluray_dep)

# Generate pkg-config .pc file
pkg_mod = import('pkgconfig')
pkg_mod.generate(libraries: libbluray,
    version: meson.project_version(),
    name: 'libbluray',
    description: 'library supporting Blu-ray playback',
)
