# Copyright (C) 2025 Rob Hall
# SPDX-License-Identifier: MIT

if get_option('enable_devtools')

    executable('bdjo_dump', 'bdjo_dump.c',
        dependencies: libbluray_dep,
        include_directories: libbluray_inc_dirs)

    executable('bdj_test', 'bdj_test.c',
        dependencies: [libbluray_dep, getopt_dependency],
        include_directories: libbluray_inc_dirs)

    executable('clpi_dump', ['clpi_dump.c', 'util.c'],
        dependencies: [libbluray_dep, getopt_dependency],
        include_directories: libbluray_inc_dirs)

    executable('hdmv_test', 'hdmv_test.c',
        dependencies: libbluray_dep,
        include_directories: libbluray_inc_dirs)

    executable('mobj_dump', ['mobj_dump.c', '../libbluray/hdmv/mobj_print.c'],
        dependencies: libbluray_dep,
        include_directories: libbluray_inc_dirs)

    # Doesn't build on MSVC due to missing dirent.h and libgen.h
    executable('mpls_dump', ['mpls_dump.c', 'util.c'],
        dependencies: [libbluray_dep, getopt_dependency],
        build_by_default: cdata.has('HAVE_DIRENT_H'),
        include_directories: libbluray_inc_dirs)

endif
