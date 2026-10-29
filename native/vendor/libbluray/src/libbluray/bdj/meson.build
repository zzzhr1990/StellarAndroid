# Copyright (C) 2025 Rob Hall
# SPDX-License-Identifier: MIT

java_src_version = '1.5'

ant = find_program('ant', required: get_option('bdj_jar'), disabler: true)

if ant.found() and add_languages('java', native: false, required: get_option('bdj_jar'))
    javac_version = meson.get_compiler('java').version()
    javac_path    = find_program('javac').full_path()
    javac_arg     = '-Xlint:-deprecation'
    if bdj_type == 'j2se'
        if javac_version.version_compare('>= 18.0')
            java_src_version = '1.8'
            java_src_version_asm = '1.8'
            javac_arg='-Xlint:-removal'
        elif javac_version.version_compare('>=12.0')
            java_src_version = '1.7'
            java_src_version_asm = '1.7'
        elif javac_version.version_compare('>=9.0')
            java_src_version = '1.6'
            java_src_version_asm = '1.6'
        else
            java_src_version = '1.4'
            java_src_version_asm = '1.5'
        endif
    endif
    java_summary = { 'Build BD-J JAR': true,
                     '    javac version': javac_version,
                     '    javac path': javac_path,
                     '    Java binary version': java_src_version }
else
    java_summary = { 'Build BD-J JAR': false }
    message('Compiling the JAR requires both Java and Ant, disabling.')
    subdir_done()
endif

awt_src = bdj_type == 'j2me' ? 'java-j2me' : 'java-j2se:java-build-support'

custom_target('bdj-jar',
    command: [ant, '-f', files('build.xml'),
              '-Dbuild=' + meson.project_build_root() / '@PRIVATE_DIR@',
              '-Ddist=' + meson.current_build_dir(),
              '-Dbootclasspath=' + get_option('bdj_bootclasspath'),
              '-Dsrc_awt=:' + awt_src,
              '-Djavac_path=' + javac_path,
              '-Djavac_arg=' + javac_arg,
              f'-Dversion=@bdj_type@-@libbluray_version@',
              f'-Djava_version_asm=@java_src_version_asm@',
              f'-Djava_version_bdj=@java_src_version@'],
    output: [f'libbluray-@bdj_type@-@libbluray_version@.jar',
             f'libbluray-awt-@bdj_type@-@libbluray_version@.jar'],
    build_always_stale: true,
    install: true,
    install_tag: 'runtime',
    install_dir: get_option('datadir') / 'java',
)
