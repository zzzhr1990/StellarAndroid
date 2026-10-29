# Copyright (C) 2025 Rob Hall
# SPDX-License-Identifier: MIT

libbluray_internal_headers = []
libbluray_api_headers = files(
    '../file/filesystem.h',
    '../util/log_control.h',
    'bluray.h',
    'bdnav/clpi_data.h',
    'keys.h',
    'bdnav/meta_data.h',
    'decoders/overlay.h',
    'player_settings.h',
)

# Copy headers to correct path for use as subproject
foreach h: libbluray_api_headers
    basename = fs.name(h)
    if meson.version().version_compare('>= 0.64.0')
        libbluray_internal_headers += fs.copyfile(h, basename)
    else
        configure_file(input: h, output: basename, copy: true)
    endif
endforeach

libbluray_api_headers += configure_file(
    input: 'bluray-version.h.in',
    output: 'bluray-version.h',
    configuration: {'BLURAY_VERSION_MAJOR': libbluray_version_split[0],
                    'BLURAY_VERSION_MINOR': libbluray_version_split[1],
                    'BLURAY_VERSION_MICRO': libbluray_version_split[2]})

# Install headers
install_headers(libbluray_api_headers,
                subdir: 'libbluray')
