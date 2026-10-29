<p align="center"><img src="assets/logo.svg" width="112" alt="恒星播放器 Logo"></p>
<h1 align="center">恒星播放器（Android）</h1>
<p align="center">StellarPlayer (Android) · <a href="README.en.md">English</a> · <a href="LICENSE">GPL-3.0-or-later</a></p>

原生 Kotlin / Jetpack Compose Android 媒体播放器。此仓库是独立的开源版本，包名为 `cn.coldlake.stellar.opensource`，可以与其他恒星播放器版本同时安装。

## 功能

- 使用 MPV、官方 FFmpeg 和 MPV 必需的上游 libplacebo 播放本地与网络媒体。
- 字幕、音轨、播放列表、均衡器、画中画、截图与片段导出。
- SMB、NFS、WebDAV、Google Drive、OneDrive，以及用户自己的 Emby / Jellyfin 媒体库。
- 未加密 DVD / Blu-ray 文件及镜像、DVD 和 HDMV 菜单；不包含 BD-J、AACS、BD+ 或 CSS 解密组件。
- 通过 TMDB 官方 API 直接搜索和获取影片、剧集、季、单集、演职员资料；无需恒星元数据服务器。
- 可选 Trakt 聚合评分，直接访问 `apiz.trakt.tv`；单集评分使用剧集 IMDb ID、季号和集号。
- DLNA 投屏，使用项目自己的 SSDP / SOAP 实现。

开源版没有恒星 Pro、账户/会员限制、AV3A、自定义超分辨率或 Google Cast SDK。libplacebo 仅作为 MPV 依赖保留，使用 OpenGL 后端。

## 编译

当前仅构建 `arm64-v8a`，最低 Android 8.0（API 26）。原生依赖从随仓库提供的固定源码构建，不附带来源不明的预编译播放库。

环境：macOS 或 Linux、JDK 21、Python 3.12+、C/C++ 构建工具、CMake、pkg-config、Perl、Make，以及 Android SDK。Windows 用户请在 Linux / WSL 环境准备原生构建环境。Gradle Wrapper、AGP、Kotlin 和原生源码版本均在仓库中固定。

1. 安装 SDK 组件（也可使用 Android Studio SDK Manager）：

   ```sh
   sdkmanager 'platforms;android-37.1' 'build-tools;37.0.0' 'ndk;29.0.14206865' 'cmake;4.1.2'
   ```

2. 克隆项目并配置环境：

   ```sh
   git clone https://github.com/zzzhr1990/StellarAndroid.git
   cd StellarAndroid
   cp local.properties.example local.properties
   # 修改 local.properties 的 sdk.dir 为你的 SDK 绝对路径
   export ANDROID_HOME=/absolute/path/to/Android/sdk
   export JAVA_HOME=/absolute/path/to/jdk-21
   ```

3. 准备原生构建工具并构建依赖：

   ```sh
   # macOS 示例；Linux 安装等效的系统开发工具
   brew install cmake pkg-config python perl
   python3 -m venv native/.work/venv
   . native/.work/venv/bin/activate
   pip install -r native/requirements.txt
   python native/build.py
   ```

   首次构建耗时较长。源码及 SHA-256 见 [native/sources.json](native/sources.json)，输出位于忽略提交的 `native/prefix/arm64-v8a`。SDK 路径也必须传入 `ANDROID_HOME`；构建脚本不会读取 `local.properties`。

4. 构建 APK：

   ```sh
   ./gradlew :app:assembleDebug
   # 输出 app/build/outputs/apk/debug/app-debug.apk
   ./gradlew :app:assembleRelease
   # 输出未签名 release APK，发布者须使用自己的密钥签名
   ```

Debug 使用本机 Android 默认调试签名。仓库不包含签名密钥、第三方 API 密钥或访问令牌。不要把含个人凭据的构建产物上传为公共 Release。

## 自行配置服务

无需任何第三方密钥即可编译并播放本地媒体。可选配置写入被 Git 忽略的 `local.properties`，或通过同名环境变量传入（环境变量优先）：

| 配置 | 用途 |
| --- | --- |
| `TMDB_READ_ACCESS_TOKEN` | 你自己的 TMDB API Read Access Token（推荐） |
| `TMDB_API_KEY` | 或使用你自己的 TMDB v3 API key；与上一项二选一 |
| `TRAKT_API_KEY` | 你自己的 Trakt API client ID，用于详情聚合评分 |

这些值会编入你的 APK，不能视为应用内秘密。未填写时，相应在线资料/评分不可用，已有本地资料和播放仍可使用。API 可用性、配额及授权由服务提供方决定。

Google Drive 和 OneDrive 在应用的连接页面输入**自己的 OAuth access token**，分别需要 Google Drive `drive.readonly` 与 Microsoft Graph `Files.Read` 委托权限。项目不代管 OAuth 客户端或刷新令牌；访问令牌过期后需重新填写。同一连接重新授权时会核对账户/网盘身份。令牌使用 Android Keystore 在设备上加密保存；不要将其写入源码、问题报告或公共构建。

## 许可证与来源

项目自有代码采用 **GPL-3.0-or-later**，详见 [LICENSE](LICENSE)。该选择兼容保留的 GPL 播放/原盘组件及 Apache-2.0 依赖。第三方代码保留各自许可证，标志与第三方数据按各自条款使用。依赖清单、源码位置和二进制发布义务见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

[现有项目 Logo](assets/logo.svg) 用于 README 与应用品牌展示。Jellyfin 标志保留 CC BY-SA 4.0 归属；TMDB 标志用于服务署名，不能将它或远程海报当作本项目 GPL 资源。其他评分源使用文字标识。

This product uses the TMDB API but is not endorsed or certified by TMDB.

TMDB 非商业 API 使用与商业使用的条款不同；申请自己的凭据并遵守 [TMDB 条款](https://developer.themoviedb.org/docs/faq) 和 [Trakt 条款](https://trakt.tv/terms)。[隐私说明](PRIVACY.md)。
