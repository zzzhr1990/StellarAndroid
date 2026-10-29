# Copyright (C) 2025 Rob Hall
# SPDX-License-Identifier: MIT

# libdvdread source files
dvdread_src = files(
    'bitreader.c',
    'dvd_input.c',
    'dvd_reader.c',
    'dvd_udf.c',
    'ifo_print.c',
    'ifo_read.c',
    'logger.c',
    'md5.c',
    'nav_print.c',
    'nav_read.c',
)

# Platform-specific filesystem implementation
if host_machine.system() == 'windows'
    dvdread_src += files('file/file_win32.c')
else
    dvdread_src += files('file/file_posix.c')
endif

#
# Library definitions
#

if host_machine.system() == 'windows' and get_option('default_library') == 'static'
    api_export_flags = []
else
    api_export_flags = ['-DDVDREAD_API_EXPORT']
endif

if host_machine.system() == 'windows'
    api_export_flags = [api_export_flags, '-D_CRT_SECURE_NO_WARNINGS']
endif

# The final libdvdread library
libdvdread = library('dvdread', dvdread_src,
    include_directories: dvdread_inc_dirs,
    dependencies: [
        libdvdcss_dependency,
        libdl_dependency,
    ],
    c_args: api_export_flags,
    gnu_symbol_visibility: 'hidden',
    version: dvdread_soname_version,
    install: true,
)

# Make the library usable as a dependency in this and other projects
dvdread_dep = declare_dependency(link_with: libdvdread,
    include_directories: include_directories('.'))

meson.override_dependency('dvdread', dvdread_dep)

# Generate pkg-config .pc file
pkg_mod = import('pkgconfig')
pkg_mod.generate(libraries: libdvdread,
    version: meson.project_version(),
    filebase: 'dvdread',
    name: 'libdvdread',
    description: 'Low level DVD access library',
)
