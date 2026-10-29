<p align="center"><img src="assets/logo.svg" width="112" alt="StellarPlayer logo"></p>
<h1 align="center">StellarPlayer (Android)</h1>
<p align="center">恒星播放器（Android） · <a href="README.md">简体中文</a> · <a href="LICENSE">GPL-3.0-or-later</a></p>

A native Kotlin / Jetpack Compose media player. This independent open-source edition uses package `cn.coldlake.stellar.opensource` and can coexist with other StellarPlayer editions.

## Features

- Local and network playback with MPV, official FFmpeg and the upstream libplacebo dependency required by MPV.
- Subtitles, tracks, playlists, equalizer, picture-in-picture, screenshots and clip export.
- SMB, NFS, WebDAV, Google Drive, OneDrive and your own Emby / Jellyfin libraries.
- Unencrypted DVD / Blu-ray files and images, DVD and HDMV menus. No BD-J, AACS, BD+ or CSS decryption components.
- Movie, show, season, episode and cast information directly from the official TMDB API; no Stellar metadata server.
- Optional aggregate ratings directly from `apiz.trakt.tv`. Episode ratings use the show's IMDb ID plus season and episode numbers.
- DLNA casting through this project's SSDP / SOAP implementation.

This edition contains no Stellar Pro, account/membership gates, MDK, AV3A, custom super-resolution, Dolby enhancements or Google Cast SDK. Upstream libplacebo is retained for MPV; its Dolby Vision reshaping and Vulkan backend are disabled.

## Build

Only `arm64-v8a` is built, targeting Android 8.0 / API 26 and later. Native dependencies are built from pinned source archives bundled in this repository.

Requirements: macOS or Linux, JDK 21, Python 3.12+, C/C++ build tools, CMake, pkg-config, Perl, Make and the Android SDK. On Windows, use a Linux / WSL native build environment. Gradle Wrapper, AGP, Kotlin and native source versions are pinned.

```sh
sdkmanager 'platforms;android-37.1' 'build-tools;37.0.0' 'ndk;29.0.14206865' 'cmake;4.1.2'
git clone https://github.com/zzzhr1990/StellarAndroid.git
cd StellarAndroid
cp local.properties.example local.properties
# Edit sdk.dir in local.properties to point to your SDK.
export ANDROID_HOME=/absolute/path/to/Android/sdk
export JAVA_HOME=/absolute/path/to/jdk-21

# macOS example; install equivalent development tools on Linux.
brew install cmake pkg-config python perl
python3 -m venv native/.work/venv
. native/.work/venv/bin/activate
pip install -r native/requirements.txt
python native/build.py
./gradlew :app:assembleDebug
```

The first native build takes time. Source versions and SHA-256 checksums are in [native/sources.json](native/sources.json). Build outputs go to ignored `native/prefix/arm64-v8a`. The native script uses `ANDROID_HOME`, not `local.properties`.

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. `./gradlew :app:assembleRelease` produces an unsigned release APK; sign it with your own key. Debug uses Android's local default debug keystore. No signing keys, API keys or access tokens are included in this repository. Never publish a build containing personal credentials.

## Configure your services

No third-party credentials are needed to compile or play local media. Optional values belong in ignored `local.properties` or environment variables of the same name (environment takes priority):

| Setting | Purpose |
| --- | --- |
| `TMDB_READ_ACCESS_TOKEN` | Your TMDB API Read Access Token (preferred) |
| `TMDB_API_KEY` | Alternatively, your TMDB v3 API key; only one TMDB credential is required |
| `TRAKT_API_KEY` | Your Trakt API client ID for detail ratings |

These values are compiled into your APK and are not secrets from its users. Without them, the corresponding online metadata/ratings are unavailable; existing local information and playback remain usable. Provider availability, quotas and authorization rules apply.

Enter your own OAuth access token in the Google Drive or OneDrive connection screen. Use Google Drive `drive.readonly` or Microsoft Graph delegated `Files.Read`, respectively. The project supplies no shared OAuth registration and does not manage refresh tokens. Replace expired tokens manually. Reauthorization verifies the same account/drive identity. Tokens are encrypted locally using Android Keystore; keep them out of source control, issue reports and public builds.

## License and attribution

Project-owned code is **GPL-3.0-or-later**; see [LICENSE](LICENSE). This accommodates the retained GPL playback/disc components and Apache-2.0 dependencies. Third-party components retain their own licenses; trademarks and remotely retrieved data have separate terms. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the inventory, source locations and binary distribution obligations.

The README links the [existing project logo](assets/logo.svg). The Jellyfin mark retains CC BY-SA 4.0 attribution. The TMDB mark is used under service attribution terms; it and remote posters are not relicensed under the project GPL. Other rating providers use text labels.

This product uses the TMDB API but is not endorsed or certified by TMDB.

TMDB's commercial and non-commercial API terms differ. Obtain your own credentials and follow the [TMDB terms](https://developer.themoviedb.org/docs/faq) and [Trakt terms](https://trakt.tv/terms). See [privacy information](PRIVACY.md).
