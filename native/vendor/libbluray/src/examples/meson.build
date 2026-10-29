# Copyright (C) 2025 Rob Hall
# SPDX-License-Identifier: MIT

if get_option('enable_examples')

    executable('index_dump', 'index_dump.c',
        dependencies: libbluray_dep,
        include_directories: libbluray_inc_dirs)

    executable('libbluray_test', 'libbluray_test.c',
        dependencies: libbluray_dep,
        include_directories: libbluray_inc_dirs)

    executable('sound_dump', 'sound_dump.c',
        dependencies: libbluray_dep,
        include_directories: libbluray_inc_dirs)

endif
