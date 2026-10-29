# 自行集成 Google Cast（Chromecast / Chrome Cast）

本指南面向自行修改和编译播放器的开发者，说明如何在自己的分支中接入 Google Cast Android Sender SDK。**由于许可证兼容性问题，项目无法在当前 GPL 公开发行版本中直接提供 Google Cast SDK。** 默认构建仅支持项目自行实现的 DLNA 投屏，没有 Google Cast 依赖、接收端配置或可直接开启的隐藏开关。

核对日期：2026-10-29。下面的版本和代码片段用于说明集成方式，尚未作为完整 Google Cast 功能在本项目中编译和真机验证。完成配置后仍须实现会话和播放器适配，不能只添加依赖或填写 App ID 就开始投屏。

## 许可证边界

项目自有代码采用 GPL-3.0-or-later，播放器还包含 GPL 模式的 MPV、libdvdread 和 libdvdnav。Google Cast framework 的 [23.0.0 POM](https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-cast-framework/23.0.0/play-services-cast-framework-23.0.0.pom) 声明的是 [Android SDK 许可](https://developer.android.com/studio/terms.html)，使用 Cast 还受 [Google Cast SDK 附加开发者条款](https://developers.google.com/cast/docs/terms)约束。不能把示例代码或 AndroidX 的 Apache-2.0 许可当作 Cast SDK 本身的许可。

在遵守 SDK 自身条款的前提下，自行修改、构建和自用与对外分发有不同要求，参见 [GPLv3 第 2、6 节](../LICENSE)。把集成后的 APK 发给他人、公开下载或上架商店前，需要先解决组合版本的许可兼容性，并履行相应源码、声明及服务条款义务。**自行集成不是分发豁免，本指南也不授予 GPL 链接例外。** 只修改项目自身的许可说明不能替第三方 GPL 组件授予例外。更多发布要求见[第三方声明](../THIRD_PARTY_NOTICES.md#redistributing-binaries)。

## 1. 准备构建环境和接收端

1. 按 [README 编译说明](../README.md#编译)先构建原始项目，确认 Android SDK、JDK 和原生库环境可用。在自己的分支中进行以下修改；需要与原版并存时，修改自己的 `applicationId`，无需因此重命名 Kotlin 包或 `namespace`。
2. 准备有可用 Google Play services 的 Android 真机和支持 Google Cast 的接收设备。两者连接到可互访的局域网；访客 Wi-Fi、AP 隔离或 VPN 可能阻止发现设备或接收端读取手机上的媒体。
3. 先选择接收端类型，再配置 Sender 使用的 Receiver App ID：

| 接收端 | 配置方式 | 适用范围 |
| --- | --- | --- |
| Default Media Receiver | 使用 `CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID` | 先验证普通媒体播放，不自定义接收端界面 |
| Styled Media Receiver | 在自己的 Cast 控制台注册并取得 App ID | 按官方能力定制接收端样式 |
| Custom Web Receiver | 注册自己的接收端并配置托管 URL | 自定义鉴权、播放逻辑或其他接收端功能 |

官方[注册说明](https://developers.google.com/cast/docs/registration)明确说明 Default Media Receiver 不需要注册自己的接收端应用。Styled / Custom Receiver 则需要在 [Google Cast SDK Developer Console](https://cast.google.com/publish/) 注册；未发布的接收端需要注册测试设备。Receiver App ID 不是 Android 包名、Google Cloud OAuth client ID 或 API key，也不是秘密。不要使用他人的接收端 App ID。

Google Cast 的媒体加载由接收设备取流和解码，不是把手机 MPV 的画面或解码能力转移到电视。先用自己可控制、无需登录、接收端可访问的 HTTPS MP4 验证连接，再接入本地文件和 NAS。

## 2. 添加 SDK 依赖

在自己分支的 [app/build.gradle.kts](../app/build.gradle.kts) 的 `dependencies` 中加入：

```kotlin
implementation("com.google.android.gms:play-services-cast-framework:23.0.0")
implementation("androidx.appcompat:appcompat:1.8.0")
```

Cast 版本依据 [Google Maven 元数据](https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-cast-framework/maven-metadata.xml)和[官方发布说明](https://developers.google.com/cast/docs/release-notes)。AppCompat 与本项目现有依赖约束一致，用于下文的 Activity 和路由对话框。不要使用 `+` 或动态版本；升级时重新检查实际依赖。

23.0.0 的 POM 会引入 `play-services-cast`、`play-services-base` 等组件，也声明了 `androidx.mediarouter:mediarouter:1.9.0-beta01`。顶层 SDK 版本没有 beta 后缀，不代表全部传递依赖都是稳定版；应核对最终解析结果并验证所选组合，不要盲目排除或降级 SDK 所需组件。

[settings.gradle.kts](../settings.gradle.kts) 已提供 `google()` 和 `mavenCentral()`，且禁止模块自行声明仓库，无需向 app 添加 `repositories` 块。此路径也无需引入 `play-services-cast-tv`：后者用于 Android TV 接收端，不是手机 Sender 的必需依赖。

## 3. 配置 OptionsProvider 和 Manifest

在自己的分支中新增 `app/src/main/java/cn/coldlake/stellar/opensource/casting/GoogleCastOptionsProvider.kt`：

```kotlin
package cn.coldlake.stellar.opensource.casting

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

class GoogleCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(
                CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
            )
            .setCastMediaOptions(
                CastMediaOptions.Builder()
                    .setNotificationOptions(null)
                    .setMediaSessionEnabled(false)
                    .build()
            )
            .build()

    override fun getAdditionalSessionProviders(
        context: Context
    ): List<SessionProvider>? = null
}
```

需要自己的 Styled / Custom Receiver 时，将默认常量替换成自己的资源值，例如 `context.getString(R.string.google_cast_receiver_app_id)`，并在 `res/values` 中新增对应的 `translatable="false"` 字符串。当前 Gradle 脚本没有读取 Cast 配置项，单独向 `local.properties` 写 `CAST_APP_ID` 不会生效。

示例选择复用项目的 `CastingService` 作为通知、锁屏控制和本地转发服务，因此关闭 SDK 自带的通知与 `MediaSession`。这要求完成后文的服务和控制器适配；若改用 SDK 自带控制，则需调整现有服务职责，避免重复通知和两个会话争夺控制权。配置含义见 [CastMediaOptions.Builder](https://developers.google.com/android/reference/com/google/android/gms/cast/framework/media/CastMediaOptions.Builder)。

在 [AndroidManifest.xml](../app/src/main/AndroidManifest.xml) 的现有 `<application>` 内加入：

```xml
<meta-data
    android:name="com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME"
    android:value="cn.coldlake.stellar.opensource.casting.GoogleCastOptionsProvider" />
```

保留现有 `StellarApplication` 和服务声明。Provider 必须是 SDK 可访问的类并具有无参构造；上面的 Kotlin 类满足这一点。若改变包名或启用代码压缩，核对 Manifest 中的全限定类名和 SDK 的 consumer rules，验证 Release 能正确创建 Provider。参见 [OptionsProvider](https://developers.google.com/android/reference/com/google/android/gms/cast/framework/OptionsProvider)。

## 4. 在 Compose 界面接入 Cast 按钮

本项目的 [MainActivity](../app/src/main/java/cn/coldlake/stellar/opensource/MainActivity.kt) 目前继承 `ComponentActivity`，XML 主题使用平台 `Theme.Material.NoActionBar`。标准 `MediaRouteButton` 的路由对话框需要支持 Fragment 的宿主，不能把按钮放进 `AndroidView` 就认为接入完成。

采用以下接法：

1. 将自己分支中的 `MainActivity` 基类改为 `androidx.appcompat.app.AppCompatActivity`，保留原有 Compose `setContent` 和生命周期逻辑。
2. 为该 Activity 提供继承 `Theme.AppCompat.DayNight.NoActionBar` 的 XML 主题，可基于现有 [themes.xml](../app/src/main/res/values/themes.xml) 新增专用主题，并在 MainActivity 的 Manifest 声明上设置 `android:theme`。保留播放器需要的窗口属性，检查明暗模式、边到边布局和画中画行为。
3. 在 `onCreate` 的主线程调用 `CastContext.getSharedInstance(applicationContext, executor)`，其中 `executor` 是应用持有的后台执行器，用来加载 Cast 内部模块；不要在每次 Compose 重组时新建执行器或初始化 SDK。处理返回 Task 的成功和失败，成功后才展示可交互的 Cast 按钮。主线程要求和异步初始化方式见 [CastContext](https://developers.google.com/android/reference/com/google/android/gms/cast/framework/CastContext)。
4. 初始化失败或 Google Play services 不可用时，显示可恢复的错误状态，保留本地播放和 DLNA。UI 回调遵循 Activity 生命周期，会话管理层不能长期持有 Activity。

SDK 就绪后，可以在可投屏内容页的顶层工具栏放置以下按钮；`AndroidView` 使用 Activity 的主题 Context，不能换成 application Context：

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory

@Composable
fun GoogleCastButton(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.size(48.dp),
        factory = { context ->
            MediaRouteButton(context).also { button ->
                CastButtonFactory.setUpMediaRouteButton(context, button)
            }
        }
    )
}
```

使用 SDK 的标准发现与选路流程，不要把 Google Cast 设备交给 DLNA 的 SSDP / SOAP 代码。当前 [CastingDialog.kt](../app/src/main/java/cn/coldlake/stellar/opensource/casting/CastingDialog.kt) 仍是 DLNA 设备列表；可保留该入口，并为 Google Cast 提供上述标准按钮。按钮位置与交互按 [Cast 设计清单](https://developers.google.com/cast/docs/design_checklist)核对。

现有 Manifest 已声明网络与局域网权限。保留 `CastingController.permitted()` 及运行时权限处理；项目目前在 API 37 及以上检查 `ACCESS_LOCAL_NETWORK`。新的 Cast 入口也要接入适用的授权流程，特别是手机提供媒体转发时，不能只依赖 DLNA 对话框才请求权限。

## 5. 接入现有播放控制器

以下是需要修改的位置，**`GoogleCastAdapter` 和 `GOOGLE_CAST` 都是待新增内容，仓库中没有现成实现**：

| 位置 | 当前实现 | 集成时的修改 |
| --- | --- | --- |
| [CastModels.kt](../app/src/main/java/cn/coldlake/stellar/opensource/casting/CastModels.kt) | `CastProtocol` 仅有 `DLNA`；`CastAdapter` 定义远程命令和状态流 | 新增 `GOOGLE_CAST`，实现 `GoogleCastAdapter : CastAdapter`，沿用 `RemoteStatus` |
| [CastingController.kt](../app/src/main/java/cn/coldlake/stellar/opensource/casting/CastingController.kt) | `connect()` 中直接选择 `dlna`；发现和字幕能力也绑定 DLNA | 按协议选择适配器；新增 SDK 会话接入入口，检查所有 DLNA 条件分支和清理路径 |
| [PlayerViewModel.kt](../app/src/main/java/cn/coldlake/stellar/opensource/presentation/PlayerViewModel.kt) | 持有控制器，提供本地、HTTP、NAS 和云盘源解析 | 向控制器提供应用级 Cast 会话依赖，保留源凭据封装 |
| [PlaybackRouter.kt](../app/src/main/java/cn/coldlake/stellar/opensource/presentation/PlaybackRouter.kt) | `attachRemote()` 暂停本地播放，`detachRemote()` 恢复本地路由 | 通过控制器接管和释放，避免手机与接收端同时播放 |
| [CastingService.kt](../app/src/main/java/cn/coldlake/stellar/opensource/casting/CastingService.kt) | 通知、锁屏、音量控制及转发前台服务 | 复用同一远程状态和命令，并验证断开、后台及停止服务行为 |

### 会话与生命周期

通过 `CastContext.sessionManager` 注册 `SessionManagerListener<CastSession>`，处理 started、resumed、suspended、ended 以及启动/恢复失败。监听器由会话管理层持有，创建和释放成对执行；SDK 会话及远程客户端操作在主线程执行。

用户通过标准按钮选择设备后，在会话启动成功时，将当前 `CastSession`、接收设备标识与待投媒体交给新适配器，再进入控制器的源准备、远程路由和 `load()` 流程。`load(device, ...)` 应校验绑定会话与目标设备一致。不要在每次重组、重连或 `onSessionResumed` 中无条件重新加载：恢复时先核对接收端当前媒体和本地会话身份，再同步状态，避免覆盖其他 Sender 的播放。

`RemoteMediaClient` 的 callback 和 progress listener 更新 `RemoteStatus`，会话更换或结束时取消注册。保留控制器现有的 `generation`、命令互斥和过期回调检查。接收端媒体状态为 `IDLE` 时要区分播放结束、停止、取消和加载失败；只有接收端声明支持时才开放暂停、进度拖动等能力。

### 媒体加载和控制

在 `casting` 包内可添加以下转换函数，供新适配器调用。示例仅处理已经准备好的、可由接收端读取的普通点播音视频，不含直播、DRM 或字幕：

```kotlin
package cn.coldlake.stellar.opensource.casting

import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata

internal fun CastMedia.toGoogleCastLoadRequest(
    positionMs: Long,
    play: Boolean
): MediaLoadRequestData {
    val metadata = MediaMetadata(
        if (mime.startsWith("audio/")) MediaMetadata.MEDIA_TYPE_MUSIC_TRACK
        else MediaMetadata.MEDIA_TYPE_MOVIE
    ).apply { putString(MediaMetadata.KEY_TITLE, title) }
    val info = MediaInfo.Builder(url)
        .setContentType(mime)
        .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
        .setMetadata(metadata)
        .apply { durationMs?.takeIf { it > 0 }?.let { setStreamDuration(it) } }
        .build()
    return MediaLoadRequestData.Builder()
        .setMediaInfo(info)
        .setCurrentTime(positionMs.coerceAtLeast(0))
        .setAutoplay(play)
        .build()
}
```

适配器从绑定的 `CastSession.remoteMediaClient` 取得客户端，调用 `load(media.toGoogleCastLoadRequest(positionMs, play))`。返回的是异步 `PendingResult`，应等待结果并检查 `result.status.isSuccess`；把失败、超时和取消映射回控制器，不能在发出请求时就报告成功。`CastAdapter` 的 suspend 接口需要在适配层桥接回调，处理协程取消和晚到结果。

| `CastAdapter` 操作 | Google Cast 接口 |
| --- | --- |
| `play()` / `pause()` | `RemoteMediaClient.play()` / `pause()` |
| `seek(positionMs)` | `RemoteMediaClient.seek(MediaSeekOptions.Builder().setPosition(positionMs).build())` |
| `volume(value)` | `CastSession.setVolume(value.coerceIn(0f, 1f).toDouble())` |
| `stop()` | `RemoteMediaClient.stop()`；退出整个会话时另用 `SessionManager.endCurrentSession(true)` |
| `status` | 结合媒体状态、进度和会话音量回调更新 `StateFlow<RemoteStatus>` |

加载、进度和 seek 的 Android API 时间单位均为毫秒。参考 [RemoteMediaClient](https://developers.google.com/android/reference/com/google/android/gms/cast/framework/media/RemoteMediaClient) 和 [CastSession](https://developers.google.com/android/reference/com/google/android/gms/cast/framework/CastSession)。

适配时区分“停止媒体”“断开 Sender”和“结束接收端会话”。停止后再恢复手机播放；停止失败时不要自动造成双端播放。网络丢失、返回手机、打开另一媒体和 `close()` 都要释放监听器与转发资源，且要防止结束回调再次触发无限清理循环。进程被杀后原有手机转发 URL 会失效，不能只恢复 SDK 会话就认为本地文件还能继续播放。

## 6. 媒体 URL、转发与字幕

| 来源 | 接入方式与限制 |
| --- | --- |
| 无鉴权 HTTP(S) 点播 | 可向接收端传入其能访问的 URL；检查 MIME、证书、重定向及编解码支持 |
| `content://`、`file://`、手机本地路径 | 不能直接交给电视；通过 `LocalCastSource` 和 `CastMediaServer` 提供局域网 HTTP URL |
| SMB / NFS / 云盘 | 复用 `NasCastSource`、`CastByteSource` 和已有源解析；接收端读取手机转发地址 |
| 带 Cookie、Authorization 或定制请求头的 HTTP | 通过受控转发保持鉴权，或在自己的接收端实现鉴权；Default Receiver 不会继承手机的 OkHttp 请求头 |
| HLS / DASH | 清单、分片、密钥和字幕都必须对接收端可达；仅转发清单不能解决子资源访问问题 |

[CastMediaServer.kt](../app/src/main/java/cn/coldlake/stellar/opensource/casting/CastMediaServer.kt) 已实现会话随机路径、HEAD、Range、OPTIONS 和 CORS，可作为转发基础。保留局域网地址绑定、资源大小限制、请求关闭和会话结束时销毁；不要把 localhost、NAS 凭据、OAuth token 或带凭据的源 URL 放入设备列表、Compose 保存状态或日志。本地随机 URL 本身具有访问能力，只在可信局域网和当前会话内使用。

当前 `CastingController.prepare()` 对 MOV / MKV 有 DLNA 限定，拒绝 DASH 和需要转发的 HLS/DASH，`connect()` 也将现有 Emby 会话限定为有 HTTP 源的 DLNA 路径。新增协议后应按实际支持范围调整并测试，不能简单去掉判断就宣称所有来源可用。现有转发器不转码；MPV 能播放不代表接收端支持同一容器、音轨、HDR 或编码。光盘菜单也不能直接发送。格式能力以目标设备和[官方媒体支持表](https://developers.google.com/cast/docs/media)为准。

当前 [CastSubtitle.kt](../app/src/main/java/cn/coldlake/stellar/opensource/casting/CastSubtitle.kt) 输出 UTF-8 SRT，转发器提供 `.srt` 和 DLNA 的 `CaptionInfo.sec` 响应头；它们不会自动变成 Google Cast 字幕。为 Cast 增加独立的字幕资源转换路径，例如真正生成 UTF-8 WebVTT、以 `text/vtt` 提供 `.vtt` URL，再通过 `MediaTrack.Builder(id, MediaTrack.TYPE_TEXT)` 设置唯一 ID、语言、`SUBTYPE_SUBTITLES` 和可访问的字幕 URL，构建轨道并传入 `MediaInfo.Builder.setMediaTracks()`；加载后用 `setActiveMediaTracks()` 选择字幕。不能只改扩展名或 Content-Type。

字幕与媒体资源都需要满足接收端 CORS 要求；ASS 特效和位图字幕需要另外设计转换或烧录方案。保留 DLNA 的 SRT 路径，并将当前 `canChangeSubtitle()` 等只绑定 DLNA 的能力判断改为按适配器能力决定。详见[官方媒体轨道指南](https://developers.google.com/cast/docs/android_sender/media_tracks)。

## 7. 构建检查和真机验证

完成自己分支的适配后，从仓库根目录检查实际依赖并构建：

```sh
./gradlew :app:dependencies --configuration debugRuntimeClasspath
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
./gradlew :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency play-services-cast
./gradlew :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency mediarouter
./gradlew :app:assembleDebug :app:assembleRelease
```

默认项目的[许可证清单生成器](../tools/generate_about_licenses.py)会拒绝 `com.google.android.gms` 等专有组件。集成后的分支执行该脚本时预期会被此检查阻止；不要通过删除检查、伪标为 Apache-2.0 或沿用旧清单宣称发行版合规。准备分发前，先解决许可兼容性，再按实际打包内容维护声明、对应源码及[隐私说明](../PRIVACY.md)。新增的 SDK、接收端和服务通信需要反映在自己的隐私说明中。

至少在自己的设备上验证以下场景：

- Google Play services 缺失或模块加载失败、拒绝局域网权限、无设备、取消选路、错误 Receiver App ID；失败后本地播放和 DLNA 仍可用。
- 普通 HTTPS MP4 的连接、暂停、继续、拖动、音量、停止和返回手机；UI、通知和锁屏显示同一会话状态。
- 本地文件、NAS 和带鉴权媒体的转发；从另一台局域网设备确认 URL 可达、HEAD / Range 返回正确；停止后旧 URL 失效。
- 字幕加载、切换和关闭；不支持的编码、音轨或光盘导航给出明确失败，不出现无限缓冲。
- 旋转、明暗主题、切后台、锁屏、Wi-Fi 切换、接收端断电、其他 Sender 控制和进程重建；监听器不重复注册，旧回调不覆盖新会话。
- Debug 与自己签名的 Release 均能初始化 Provider；没有重复媒体通知、双端播放或会话结束后仍存活的转发服务。
- 日志、崩溃报告和发布附件不包含访问令牌、源凭据或会话媒体 URL。

编译通过只能证明构建成功，不能替代接收设备上的端到端测试。
