# Copyright (C) 2025 Rob Hall
# SPDX-License-Identifier: MIT

if get_option('enable_tools')

    executable('bd_info', 'bd_info.c',
        dependencies: [libbluray_dep, getopt_dependency],
        include_directories: libbluray_inc_dirs,
        install: true)

    executable('bd_list_titles', 'bd_list_titles.c',
        dependencies: [libbluray_dep, getopt_dependency],
        include_directories: libbluray_inc_dirs,
        install: true)

    executable('bd_splice', 'bd_splice.c',
        dependencies: [libbluray_dep, getopt_dependency],
        include_directories: libbluray_inc_dirs,
        install: true)

endif
