# Copyright (C) 2025 Rob Hall
# SPDX-License-Identifier: MIT

dvdread_api_headers = files(
    'attributes.h',
    'bitreader.h',
    'dvd_filesystem.h',
    'dvd_reader.h',
    'dvd_udf.h',
    'ifo_print.h',
    'ifo_read.h',
    'ifo_types.h',
    'nav_print.h',
    'nav_read.h',
    'nav_types.h',
)

dvdread_api_headers += configure_file(input: 'version.h.in',
    output: 'version.h',
    configuration: {'DVDREAD_VERSION_MAJOR': dvdread_version_split[0],
                    'DVDREAD_VERSION_MINOR': dvdread_version_split[1],
                    'DVDREAD_VERSION_MICRO': dvdread_version_split[2]})

# Install headers
install_headers(dvdread_api_headers,
                subdir: 'dvdread')
