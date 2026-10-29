#!/usr/bin/env python3
"""Build the complete, pinned ARM64 native dependency closure from source.
Run inside the documented Python virtual environment on macOS or Linux.
"""
from pathlib import Path
import hashlib, json, os, platform, shutil, subprocess, tarfile
root = Path(__file__).resolve().parent
work = root / '.work'
sources = work / 'sources'
prefix = root / 'prefix' / 'arm64-v8a'
sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'Library/Android/sdk'))))
ndk = sdk / 'ndk/29.0.14206865'
host = 'darwin-x86_64' if platform.system() == 'Darwin' else 'linux-x86_64'
bin = ndk / 'toolchains/llvm/prebuilt' / host / 'bin'
cc = bin / 'aarch64-linux-android26-clang'
cxx = bin / 'aarch64-linux-android26-clang++'
if not cc.is_file(): raise SystemExit('Install NDK 29.0.14206865 and set ANDROID_HOME.')
prefix.mkdir(parents=True, exist_ok=True)
manifest = json.loads((root / 'sources.json').read_text())
for name, spec in manifest.items():
    archive = root / spec['archive']
    if hashlib.sha256(archive.read_bytes()).hexdigest() != spec['sha256']:
        raise SystemExit('Source archive hash mismatch: ' + name)
    dest = sources / spec.get('destination', name)
    marker = dest / '.stellar-source'
    if not marker.exists() or marker.read_text() != spec['sha256']:
        if dest.exists(): shutil.rmtree(dest)
        dest.mkdir(parents=True, exist_ok=True)
        with tarfile.open(archive) as tar:
            top = tar.getmembers()[0].name.split('/')[0] + '/'
            for member in tar.getmembers():
                if member.name.startswith(top):
                    member.name = member.name[len(top):]
                    if member.name: tar.extract(member, dest, filter='data')
        marker.write_text(spec['sha256'])
env = os.environ.copy()
env.update(ANDROID_NDK_ROOT=str(ndk), ANDROID_NDK_HOME=str(ndk),
           PATH=str(bin) + os.pathsep + env['PATH'], PKG_CONFIG_LIBDIR=str(prefix / 'lib/pkgconfig'), PKG_CONFIG_PATH='')
flags = ['-O2', '-fPIC']
ldflags = ['-Wl,-z,max-page-size=16384', '-Wl,--exclude-libs,ALL', '-static-libstdc++']
cross = work / 'android-arm64.ini'
cross.write_text(f'''[binaries]
c = '{cc}'
cpp = '{cxx}'
ar = '{bin}/llvm-ar'
strip = '{bin}/llvm-strip'
pkg-config = '{shutil.which('pkg-config')}'
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8-a'
endian = 'little'
[built-in options]
c_args = {flags!r}
cpp_args = {flags!r}
c_link_args = {ldflags!r}
cpp_link_args = {ldflags!r}
[properties]
pkg_config_libdir = '{prefix}/lib/pkgconfig'
needs_exe_wrapper = true
''')
jobs = str(min(os.cpu_count() or 4, 12))
def run(*args, cwd=None, extra=None):
    print(' '.join(map(str,args)), flush=True)
    subprocess.run(list(map(str,args)), cwd=cwd, env=env | (extra or {}), check=True)
def meson(name, *options, shared=False):
    build = work / ('build-' + name)
    run('meson', 'setup', *(['--reconfigure'] if (build/'build.ninja').exists() else []), build, sources/name,
        '--cross-file', cross, '--prefix', prefix, '--libdir', 'lib', '--buildtype', 'release',
        '--wrap-mode=nofallback', '--default-library='+('shared' if shared else 'static'),
        '-Dauto_features=disabled', *options)
    run('meson','compile','-C',build,'-j',jobs)
    run('meson','install','-C',build)
# Font shaping and subtitle rendering, with Android's system fonts supplied by the app.
meson('freetype','-Dharfbuzz=disabled','-Dzlib=internal')
meson('fribidi','-Ddocs=false','-Dbin=false','-Dtests=false')
meson('harfbuzz','-Dfreetype=enabled','-Dtests=disabled','-Dutilities=disabled',
      '-Dsubset=disabled','-Draster=disabled','-Dvector=disabled','-Dgpu=disabled')
meson('libass','-Drequire-system-font-provider=false','-Dasm=enabled')
# TLS is built from the official Apache-2.0 OpenSSL source archive.
build = work/'build-openssl';build.mkdir(exist_ok=True)
if not (build/'Makefile').exists():
    run(sources/'openssl/Configure','android-arm64','-D__ANDROID_API__=26','no-shared','no-tests',
        '--prefix='+str(prefix),'--libdir=lib', cwd=build)
run('make','-j'+jobs,cwd=build);run('make','install_sw',cwd=build)
# Official FFmpeg; no external codec SDKs, GPL codecs, or nonfree flags.
build=work/'build-ffmpeg';build.mkdir(exist_ok=True)
if not (build/'ffbuild/config.mak').exists():
    run(sources/'ffmpeg/configure','--prefix='+str(prefix),'--target-os=android','--arch=aarch64',
        '--enable-cross-compile','--cc='+str(cc),'--cxx='+str(cxx),
        '--ar='+str(bin/'llvm-ar'),'--ranlib='+str(bin/'llvm-ranlib'),'--strip='+str(bin/'llvm-strip'),
        '--enable-shared','--disable-static','--disable-programs','--disable-doc','--disable-debug',
        '--disable-autodetect','--enable-pic','--enable-jni','--enable-mediacodec','--enable-openssl',
        '--enable-version3','--enable-zlib','--disable-vulkan','--disable-avdevice',
        '--pkg-config='+str(shutil.which('pkg-config')),'--pkg-config-flags=--static',
        '--extra-cflags=-O2 -fPIC -I'+str(prefix/'include'),
        '--extra-ldflags=-L'+str(prefix/'lib')+' -Wl,-z,max-page-size=16384 -Wl,--exclude-libs,ALL',cwd=build)
run('make','-j'+jobs,cwd=build);run('make','install',cwd=build)
# Unmodified upstream libplacebo, with Dolby Vision reshaping and Vulkan disabled.
meson('libplacebo','-Dopengl=enabled','-Dgl-proc-addr=enabled','-Dvulkan=disabled',
      '-Ddovi=disabled','-Dlibdovi=disabled','-Ddemos=false','-Dtests=false','-Dbench=false')
meson('mpv','-Dlibmpv=true','-Dcplayer=false','-Dgpl=true','-Dandroid-media-ndk=enabled',
      '-Degl-android=enabled','-Dgl=enabled','-Daaudio=enabled','-Dopensles=enabled','-Diconv=disabled',shared=True)
# Vendor NAS libraries include their complete source archive and build definitions.
for name in ['libsmb2','libnfs']:
    vendor=root/'vendor'/name;src=work/('source-'+name)
    archive_path = vendor/('source.tar' if name == 'libsmb2' else 'source.tar.gz')
    expected = json.loads((root/'vendor/sources.json').read_text())[name]['sha256']
    if hashlib.sha256(archive_path.read_bytes()).hexdigest() != expected:
        raise SystemExit('Source archive hash mismatch: '+name)
    if not (src/'CMakeLists.txt').exists():
        src.mkdir(exist_ok=True)
        archive = vendor / ('source.tar' if name == 'libsmb2' else 'source.tar.gz')
        with tarfile.open(archive) as tar:
            top = '' if name == 'libsmb2' else tar.getmembers()[0].name.split('/')[0]+'/'
            for m in tar.getmembers():
                if m.name.startswith(top):
                    m.name=m.name[len(top):]
                    if m.name:tar.extract(m,src,filter='data')
    build=work/('build-'+name)
    run('cmake','-S',src,'-B',build,'-G','Ninja','-DCMAKE_TOOLCHAIN_FILE='+str(ndk/'build/cmake/android.toolchain.cmake'),
        '-DANDROID_ABI=arm64-v8a','-DANDROID_PLATFORM=android-26','-DCMAKE_INSTALL_PREFIX='+str(prefix),
        '-DCMAKE_BUILD_TYPE=Release','-DBUILD_SHARED_LIBS=ON','-DENABLE_EXAMPLES=OFF','-DENABLE_TESTS=OFF','-DENABLE_LIBKRB5=OFF','-DENABLE_GSSAPI=OFF',
        '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384')
    run('cmake','--build',build,'-j',jobs);run('cmake','--install',build)
# CMake IMPORTED targets package these canonical libraries via AGP.
for library in (prefix/'lib').glob('*.so'):
    run(bin/'llvm-strip','--strip-unneeded',library)
print('Native build complete: '+str(prefix),flush=True)
