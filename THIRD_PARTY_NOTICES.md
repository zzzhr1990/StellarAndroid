# License and source information

The project-owned application code, native adapters, build scripts, documentation and project artwork are released under **GPL-3.0-or-later**. Copyright © 2026 StellarPlayer contributors. The full GPL text is in [LICENSE](LICENSE). Third-party components follow their upstream notices and licenses.

## Why GPL-3.0-or-later

The MPV and DVD components permit GPL version 2 **or later**. This distribution selects GPL version 3, which accommodates the Apache-2.0 dependencies, including OpenSSL. The FFmpeg configuration in `native/build.py` uses `--enable-version3` and produces an LGPL-3.0-or-later build. The combined application is distributed under GPL-3.0-or-later; permissive and weak-copyleft components keep their own notices.

References: [GNU license compatibility](https://www.gnu.org/licenses/license-list.en.html), [Apache/GPL compatibility](https://www.apache.org/licenses/GPL-compatibility), [FFmpeg licensing](https://ffmpeg.org/legal.html).

## Native components

| Component | Version | License | Corresponding source / build input |
| --- | --- | --- | --- |
| MPV | 0.41.0 | GPL-2.0-or-later, selected under GPLv3 | `native/archives`, `native/build.py` |
| FFmpeg | 9.0.2 | LGPL-3.0-or-later for this configuration | `native/archives`, `native/build.py` |
| libplacebo | 7.387.0 | LGPL-2.1-or-later | `native/archives` |
| libass | 0.17.5 | ISC | `native/archives` |
| FreeType | 2.15.0 | FTL or GPL-2.0-or-later | `native/archives`; FTL notices retained |
| HarfBuzz | 15.0.0 | MIT | `native/archives` |
| FriBidi | 1.0.17 | LGPL-2.1-or-later | `native/archives` |
| OpenSSL | 4.1.0 | Apache-2.0 | `native/archives` |
| libsmb2 | 6.2 | LGPL-2.1-or-later | `native/vendor/libsmb2/source.tar` |
| libnfs | 8.0.0 | LGPL-2.1-or-later / BSD | `native/vendor/libnfs/source.tar.gz` |
| libbluray | 1.5.0 | LGPL-2.1-or-later | `native/vendor/libbluray` |
| libudfread | 1.2.0 | LGPL-2.1-or-later | `native/vendor/libudfread` |
| libdvdread | 7.1.1 | GPL-2.0-or-later | `native/vendor/libdvdread` |
| libdvdnav | 7.0.0 | GPL-2.0-or-later | `player-disc/vendor/libdvdnav` |
| Android NDK C++ runtime | 29.0.14206865 | Apache-2.0 with LLVM exception and bundled permissive notices | Android NDK distribution / LLVM sources |

[Source manifest](native/sources.json) records upstream URLs, hashes and pinned libplacebo build submodules (glad, fast_float, Jinja and MarkupSafe). The complete upstream source archives include their license notices. Native build adapters and the DVD reader bridge are provided as source.

## JVM dependencies, artwork and notices

The complete versioned runtime inventory and original notice texts are in [app/src/main/assets/licenses](app/src/main/assets/licenses); the app displays the same inventory in Settings → About → Open-source licenses. The resolved JVM runtime uses Apache-2.0, MIT, BSD and MPL-2.0 components, including AndroidX / Compose, Kotlin, Ktor, Coil, OkHttp, Guava and dav4jvm. The MPL-2.0 files remain under MPL-2.0; their upstream source is available from the links in the catalog. Android platform libraries are system dependencies.

To refresh the inventory after changing dependencies:

```sh
./gradlew -I tools/about-dependencies.init.gradle :app:exportAboutDependencies
python3 tools/generate_about_licenses.py
```

Material Symbols retain Apache-2.0 notices. The converted Jellyfin mark remains CC BY-SA 4.0, with attribution and the full license in the app assets. The existing StellarPlayer logo is in `assets/logo.svg`.

The TMDB logo is a **trademark/attribution asset**, governed by [TMDB's attribution requirements](https://developer.themoviedb.org/docs/faq). Remote posters, descriptions and provider data follow the providers' content terms. Other rating providers are identified by text. API access requires each developer's own credentials and compliance with provider terms. API, database, trademark and codec patent permissions are governed separately from the software license.

## Optional Google Cast integration

Google Cast is not included in the default build or its runtime inventory. The Cast framework's [published POM](https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-cast-framework/23.0.0/play-services-cast-framework-23.0.0.pom) declares the Android Software Development Kit License; use also falls under the [Google Cast SDK Additional Developer Terms](https://developers.google.com/cast/docs/terms). These are separate from the licenses of AndroidX and open-source sample code. Because of unresolved compatibility with the current GPL distribution, this project does not bundle the Cast SDK.

The [self-integration guide](docs/GOOGLE_CAST_INTEGRATION.md) (Chinese) describes changes developers can make in their own branches. It grants no GPL linking exception or additional rights to third-party components. Anyone distributing a combined build must first resolve the applicable licensing requirements; adding notices or bypassing the license inventory checks does not resolve compatibility.

## Redistributing binaries

The repository publishes source. A distributor publishing an APK must provide the complete corresponding source for that exact build, including modifications, build scripts, dependency sources and applicable installation information. Keep copyright notices and license texts; provide recipients the required ability to rebuild/relink modified LGPL components. Preserve recipients' GPL/LGPL rights in your distribution terms.

The native archives and adapters are included here. For JVM dependencies, obtain the exact source artifacts/upstream revisions matching the runtime catalog and preserve them alongside your release source bundle. Include NDK runtime source/notice information where required. Publish your exact source revision and configuration with a binary release, keeping private signing material and personal tokens in private storage. Sign your distribution with your own key.

## 中文说明

项目自有代码采用 GPL-3.0-or-later，上游源码及第三方资源遵循各自许可证。仓库提供构建所需源码、版本、许可及归属说明。TMDB 标志和第三方服务数据遵循单独的署名和使用条款；商业 API 使用需要相应授权。发布 APK 时，发布者须随准确版本提供完整对应源码，并履行上表及各许可证要求。
