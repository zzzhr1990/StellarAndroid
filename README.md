<p align="center"><img src="assets/logo.svg" width="112" alt="恒星播放器 Logo"></p>
<h1 align="center">恒星播放器（Android）</h1>
<p align="center">StellarPlayer (Android) · <a href="README.en.md">English</a> · <a href="LICENSE">GPL-3.0-or-later</a></p>

原生 Kotlin / Jetpack Compose Android 媒体播放器。

## 功能

- 使用 MPV、FFmpeg 和 libplacebo 播放本地与网络媒体，通过 OpenGL 渲染视频。
- 字幕、音轨、播放列表、均衡器、画中画、截图与片段导出。
- SMB、NFS、WebDAV、Google Drive、OneDrive，以及用户自己的 Emby / Jellyfin 媒体库。
- 未加密 DVD / Blu-ray 文件及镜像、DVD 和 HDMV 菜单。
- 通过 TMDB 官方 API 直接搜索和获取影片、剧集、季、单集、演职员资料。
- 可选 Trakt 聚合评分，直接访问 `apiz.trakt.tv`；单集评分使用剧集 IMDb ID、季号和集号。
- DLNA 投屏，使用项目自己的 SSDP / SOAP 实现。

## 界面预览

折叠屏展开状态下的竖屏界面，原图分辨率为 **1968 × 2184**。点击图片可查看大图。

### Emby 竖屏播放

[![Emby 竖屏播放：视频、字幕与播放列表](assets/screenshots/emby-portrait-playback.png)](assets/screenshots/emby-portrait-playback.png)

### 视频详情

[![视频详情：影片资料、评分与演职人员](assets/screenshots/video-details.png)](assets/screenshots/video-details.png)

## 编译

构建目标为 `arm64-v8a`，最低 Android 8.0（API 26）。原生依赖从随仓库提供的固定源码构建。

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

   首次构建耗时较长。源码及 SHA-256 见 [native/sources.json](native/sources.json)，输出位于忽略提交的 `native/prefix/arm64-v8a`。原生构建脚本通过 `ANDROID_HOME` 获取 SDK 路径。

4. 构建 APK：

   ```sh
   ./gradlew :app:assembleDebug
   # 输出 app/build/outputs/apk/debug/app-debug.apk
   ./gradlew :app:assembleRelease
   # 输出未签名 release APK，发布者须使用自己的密钥签名
   ```

Debug 使用本机 Android 默认调试签名。Release 签名可通过 `local.properties.example` 中的四个 `ANDROID_KEYSTORE_*` / `ANDROID_KEY_*` 配置或同名环境变量提供；未配置时仍输出未签名 APK。基础版本号位于 `gradle.properties`，可通过 `-PappVersionName=... -PappVersionCode=...` 覆盖。

## 自动发布

向远端 `release` 分支推送后，[Android Release](https://github.com/zzzhr1990/StellarAndroid/actions/workflows/release.yml) 会从固定源码编译原生依赖，再构建并发布到 [GitHub Releases](https://github.com/zzzhr1990/StellarAndroid/releases)：

- 已签名的 ARM64 Release APK（直接安装）和 Release AAB（分发包）。
- Debug APK，使用每次 CI 运行生成的调试签名，不能覆盖 Release，跨运行也可能需要卸载后安装。
- 对应提交的仓库源码包（含随仓库提供的原生依赖源码）、许可证、构建信息及 `SHA256SUMS.txt`。

每次推送产生独立版本：基础版本 `1.0.0`、工作流运行号 `42` 对应版本名 `1.0.0+42`、标签 `v1.0.0-build.42`、`versionCode=1042`（1000 + 运行号）。重跑沿用同一标签，不覆盖已公开版本。修改 `gradle.properties` 的 `appVersionName` 可调整基础版本；保持工作流身份和运行号连续，以保证 APK 升级版本号递增。构建或校验失败时不公开 Release；上传完成后才将草稿发布。

仓库 Actions Secrets 必须配置：`ANDROID_KEYSTORE_BASE64`（密钥库 Base64）、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。同一发布密钥必须保留用于后续升级，不能每次重新生成。工作流仅在临时目录恢复密钥，构建后删除。可选服务凭据也可配置为同名 Secrets，缺省时留空。

```sh
git switch release
git merge main
git push origin release
# 查看运行状态；失败后可在修复配置后重跑
gh run list --branch release
gh run rerun RUN_ID --failed
```

工作流配置保存在 `release` 分支，推送新的提交即可触发。当前只支持 `arm64-v8a`，不生成 ARMv7 / x86 包。

## 自行配置服务

默认配置支持编译和本地媒体播放。在线服务的可选配置写入被 Git 忽略的 `local.properties`，或通过同名环境变量传入（环境变量优先）：

| 配置 | 用途 |
| --- | --- |
| `TMDB_READ_ACCESS_TOKEN` | 你自己的 TMDB API Read Access Token（推荐） |
| `TMDB_API_KEY` | 或使用你自己的 TMDB v3 API key；与上一项二选一 |
| `TRAKT_API_KEY` | 你自己的 Trakt API client ID，用于详情聚合评分 |

填写对应凭据后可启用在线资料和评分。这些值会编入 APK，APK 接收者能够提取它们。API 可用性、配额及授权由服务提供方决定。

Google Drive 和 OneDrive 在应用的连接页面输入**自己的 OAuth access token**，分别需要 Google Drive `drive.readonly` 与 Microsoft Graph `Files.Read` 委托权限。通过相应服务的 OAuth 流程取得访问令牌，过期后在连接页面更新。同一连接重新授权时会核对账户/网盘身份。令牌使用 Android Keystore 在设备上加密保存。

## 许可证与来源

项目自有代码采用 **GPL-3.0-or-later**，详见 [LICENSE](LICENSE)。第三方代码遵循各自许可证，标志与第三方数据按各自条款使用。依赖清单、源码位置和二进制发布义务见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

[项目 Logo](assets/logo.svg) 用于 README 与应用品牌展示。Jellyfin 标志遵循 CC BY-SA 4.0；TMDB 标志及远程海报按相应服务的署名和内容使用条款使用。其他评分源使用文字标识。

This product uses the TMDB API but is not endorsed or certified by TMDB.

TMDB 非商业 API 使用与商业使用的条款不同；申请自己的凭据并遵守 [TMDB 条款](https://developer.themoviedb.org/docs/faq) 和 [Trakt 条款](https://trakt.tv/terms)。[隐私说明](PRIVACY.md)。

---

<p align="center">冷湖科技(武汉)有限公司</p>
