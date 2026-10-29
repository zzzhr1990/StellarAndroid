# Copyright (C) 2025 Rob Hall
# SPDX-License-Identifier: MIT

# libdvdnav source files
dvdnav_src = files(
    'dvdnav.c',
    'highlight.c',
    'logger.c',
    'navigation.c',
    'read_cache.c',
    'searching.c',
    'settings.c',
    'vm/decoder.c',
    'vm/getset.c',
    'vm/play.c',
    'vm/vm.c',
    'vm/vmcmd.c',
    'vm/vmget.c',
)

# Library definitions
#

if host_machine.system() == 'windows' and get_option('default_library') == 'static'
    api_export_flags = []
else
    api_export_flags = '-DDVDNAV_API_EXPORT'
endif

# The final libdvdnav library
libdvdnav = library('dvdnav', dvdnav_src,
    include_directories: dvdnav_inc_dirs,
    dependencies: [
        dvdread_dependency,
        thread_dependency,
    ],
    c_args: [api_export_flags],
    gnu_symbol_visibility: 'hidden',
    version: dvdnav_soname_version,
    install: true,
)

# Make the library usable as a dependency in this and other projects
dvdnav_dep = declare_dependency(link_with: libdvdnav,
    include_directories: include_directories('.'))

meson.override_dependency('dvdnav', dvdnav_dep)

# Generate pkg-config .pc file
pkg_mod = import('pkgconfig')
pkg_mod.generate(libraries: libdvdnav,
    version: meson.project_version(),
    filebase: 'dvdnav',
    name: 'libdvdnav',
    description: 'DVD Navigation library',
)
