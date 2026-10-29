# Copyright (C) 2025 Rob Hall
# SPDX-License-Identifier: MIT

dvdnav_api_headers = files(
    'dvdnav.h',
    'dvdnav_events.h',
    'dvd_types.h',
)

dvdnav_api_headers += configure_file(input: 'version.h.in',
    output: 'version.h',
    configuration: {'DVDNAV_MAJOR': dvdnav_version_split[0],
                    'DVDNAV_MINOR': dvdnav_version_split[1],
                    'DVDNAV_SUB':   dvdnav_version_split[2]})

# Install headers
install_headers(dvdnav_api_headers,
                subdir: 'dvdnav')
