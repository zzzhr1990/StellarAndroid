# Copyright (C) 2025 Rob Hall
# SPDX-License-Identifier: MIT

project('libbluray', 'c',
    version: '1.5.0',
    subproject_dir: 'contrib',
    default_options: ['buildtype=debugoptimized',
                      'c_std=c99',
                      'default_library=both',
                      'optimization=3',
                      'warning_level=2'],
    meson_version: '>= 0.60.0')

libbluray_src_root = meson.current_source_dir()
cc = meson.get_compiler('c')
fs = import('fs')

# Configuration data for config.h
cdata = configuration_data()
add_project_arguments('-DHAVE_CONFIG_H=1', language: 'c')

# Include directories
libbluray_inc_dirs = [include_directories('.', 'src', 'src/libbluray', 'src/libbluray/disc')]

# The version number for the shared library
libbluray_soname_version = '4.0.0'

libbluray_version = meson.project_version()
libbluray_version_split = libbluray_version.split('.')
cdata.set_quoted('VERSION', libbluray_version)

#
# OS/Compiler checks and defines
#

# Arguments in test_args will be used even on feature tests
test_args = []

optional_arguments = []
optional_link_arguments = []
extra_dependencies = []

if host_machine.system() == 'windows'
    test_args += ['-D_WIN32_WINNT=0x0502', '-D_WIN32_IE=0x0501']
    if cc.get_define('_MSC_VER') == ''
        extra_dependencies += cc.find_library('ssp')
        test_args += '-D__USE_MINGW_ANSI_STDIO=1'
    else
        test_args += ['-D_CRT_NONSTDC_NO_WARNINGS', '-D_CRT_SECURE_NO_WARNINGS']
    endif
else
    test_args += '-D_POSIX_C_SOURCE=200809L'
endif

if host_machine.system() == 'darwin'
    extra_dependencies += dependency('appleframeworks',
        modules: ['CoreFoundation', 'DiskArbitration'])
elif host_machine.system() == 'netbsd'
    test_args += '-D_NETBSD_SOURCE'
elif host_machine.system() == 'openbsd'
    test_args += '-D_BSD_SOURCE'
elif host_machine.system() == 'freebsd'
    test_args += ['-D__BSD_VISIBLE=1', '-D__XSI_VISIBLE=1']
elif host_machine.system() == 'sunos'
    test_args += '-D__EXTENSIONS__'
elif host_machine.system() == 'linux'
    test_args += '-D_DEFAULT_SOURCE'
endif

# Java compilation options

jdk_home = get_option('jdk_home')
bdj_type = get_option('bdj_type')

if host_machine.system() == 'windows'
    java_os = 'win32'
elif host_machine.system() == 'sunos'
    java_os = 'solaris'
else
    java_os = host_machine.system()
endif

if host_machine.cpu_family() == 'x86_64'
    java_arch = 'amd64'
elif host_machine.cpu_family() == 'aarch64'
    java_arch = 'arm'
elif host_machine.cpu_family() == 'x86'
    java_arch = 'i386'
else
    java_arch = host_machine.cpu_family()
endif

cdata.set_quoted('JAVA_ARCH', java_arch)
cdata.set_quoted('JDK_HOME', jdk_home)

if bdj_type == 'j2me'
    cdata.set('HAVE_BDJ_J2ME', 1)
endif

if jdk_home == ''
    # Use fallback instead of looking for a directory that doesn't exist
    if not fs.exists('jni' / java_os / 'jni_md.h')
        warning(f'No jni_md.h found in jni/@java_os@, using a fallback.')
        java_os = 'linux'
    endif
    libbluray_inc_dirs += include_directories(
        'jni',
        'jni'/ java_os)
else
    libbluray_inc_dirs += include_directories(
        jdk_home / 'include',
        jdk_home / 'include'/ java_os)
endif

# Header checks

check_headers = [
    'dirent.h',
    'dlfcn.h',
    'mntent.h',
    'strings.h',
    'sys/time.h',
    'sys/dl.h',
]

foreach h : check_headers
    if cc.has_header(h, args: test_args)
        cdata.set('HAVE_' + h.underscorify().to_upper(), 1)
    endif
endforeach

if host_machine.system() == 'netbsd'
    if cc.has_function('getvfsstat', prefix: '#include <sys/statvfs.h>', args: test_args)
        cdata.set('HAVE_GETVFSSTAT', 1)
    endif
elif host_machine.system() != 'darwin'
    if cc.has_function('getfsstat', prefix: '#include <sys/types.h>\n#include <sys/mount.h>', args: test_args)
        cdata.set('HAVE_GETFSSTAT', 1)
    endif
endif

# These args are defined in the source files
dladdr_args = host_machine.system() == 'darwin' ? '-D_DARWIN_C_SOURCE' : '-D_GNU_SOURCE'
if cc.has_function('dladdr', prefix: '#include <dlfcn.h>', args: [test_args, dladdr_args])
    cdata.set('HAVE_DLADDR', 1)
endif

if not cc.has_function('getopt', prefix: '#include <unistd.h>', args: test_args)
    getopt_dependency = declare_dependency(
        sources: 'src/compat/getopt.c',
        include_directories: 'src/compat/include')
else
    getopt_dependency = []
endif

# External dependencies and libraries

if host_machine.system() == 'windows'
    libdl_dependency = []
    thread_dependency = []
else
    if cc.has_function('dlopen', prefix: '#include <dlfcn.h>', args: test_args)
        libdl_dependency = []
    else
        libdl_dependency = cc.find_library('dl')
    endif
    thread_dependency = dependency('threads')
    cdata.set('HAVE_PTHREAD_H', 1)
endif

freetype_dependency = dependency('freetype2', required: get_option('freetype'))
if freetype_dependency.found()
    cdata.set('HAVE_FT2', 1)
endif

fontconfig_dependency = []
use_system_fonts = false
if host_machine.system() == 'windows'
    system_font_mode = 'Windows'
    if freetype_dependency.found()
        use_system_fonts = true
    endif
else
    system_font_mode = 'fontconfig'
    if freetype_dependency.found()
        fontconfig_dependency = dependency('fontconfig', required: get_option('fontconfig'))
        if fontconfig_dependency.found()
            use_system_fonts = true
            cdata.set('HAVE_FONTCONFIG', 1)
        endif
    endif
endif

libxml2_dependency = dependency('libxml-2.0', version: '>= 2.6', required: get_option('libxml2'))
if libxml2_dependency.found()
    cdata.set('HAVE_LIBXML2', 1)
endif

# libudfread will be built as a subproject if not found on the system
libudfread_dependency = dependency('libudfread', version: '>= 1.2.0')
cdata.set('HAVE_LIBUDFREAD', 1)

# Compiler flag tests

# Compiler flags that should be set
# But when the compiler does not supports them
# it is not an error and silently tolerated
if cc.get_argument_syntax() != 'msvc'
    optional_arguments += [
        '-Werror=implicit-function-declaration',
        '-Werror-implicit-function-declaration',
        '-Wdisabled-optimization',
        '-Wpointer-arith',
        '-Wredundant-decls',
        '-Wcast-qual',
        '-Wwrite-strings',
        '-Wtype-limits',
        '-Wundef',
        '-Wmissing-prototypes',
        '-Wshadow',
    ]
    if get_option('buildtype') not in ['debug', 'plain']
        optional_arguments += '-fomit-frame-pointer'
    endif
else
    optional_arguments += '/wd4267' # Disable warning C4267: conversion from 'size_t' to 'int'
endif

add_project_arguments(cc.get_supported_arguments(optional_arguments), language: 'c')
add_project_link_arguments(cc.get_supported_link_arguments(optional_link_arguments), language: 'c')
add_project_arguments(test_args, language: 'c')

# Generate config.h
configure_file(output: 'config.h', configuration: cdata)


# Include subdir meson.build files
# The order is important!

subdir('src/libbluray')

subdir('src')

subdir('src/libbluray/bdj')

subdir('src/devtools')

subdir('src/examples')

subdir('src/tools')

subdir('doc')

summary(java_summary + {
    'Font support (freetype2)': freetype_dependency.found(),
    f'Use system fonts (@system_font_mode@)': use_system_fonts,
    'Metadata support (libxml2)': libxml2_dependency.found(),
    'External libudfread': libudfread_dependency.type_name() != 'internal',
})
