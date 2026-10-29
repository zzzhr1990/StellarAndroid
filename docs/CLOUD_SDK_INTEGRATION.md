# 自行集成 MSAL 和 Google Drive

简体中文 · [English](CLOUD_SDK_INTEGRATION.en.md)

**由于许可证兼容性问题，本项目无法将 MSAL 的默认依赖组合及 Google 原生授权 SDK 直接集成到当前 GPL 公开发行版本中。** 具体而言，已核对的 MSAL 8.5.0 会间接引入专有 Google 组件，Google 原生授权组件 `play-services-auth` 本身也适用专有许可；这些组件与当前发行方案的兼容性尚未解决。

因此，默认构建使用用户提供的 access token，不提供上述 SDK 的开箱即用登录和自动刷新功能。需要这些功能的开发者须按本指南，在自己的分支中手动集成代码、注册服务应用并自行编译；仅填写 client ID 不会启用 SDK 登录。自行集成后的对外分发仍须解决相应许可证问题，不能以“用户自行编译”为由免除义务。

Google Drive 的开源 API 客户端采用 Apache-2.0，与 GPLv3 兼容，不属于上述许可障碍；它与负责账户授权的 Google SDK 是不同组件。本指南同时提供该 API 客户端的可选接入步骤。

核对日期：2026-10-29。以下固定版本用于说明接入方式和依赖审查，不代表长期推荐版本，也不代表已在播放器中完成端到端验证。升级时需要重新检查实际解析的依赖。

## 选择接入方式

| 目标 | 接入位置 | 许可证边界 |
| --- | --- | --- |
| OneDrive 登录和自动获取令牌 | MSAL，保留现有 Microsoft Graph HTTP 客户端 | MSAL 本身为 MIT；8.5.0 默认传递依赖包含专有 Google 组件 |
| 使用官方客户端调用 Drive API | `google-api-services-drive` 和开源 Java 客户端 | Apache-2.0 与本项目 GPLv3 兼容，仍需履行声明及源码提供等发布义务 |
| 在 Android 中请求 Google Drive 授权 | Google Identity Services 的 `AuthorizationClient` | 来自 `play-services-auth`，与开源 Drive API 客户端分开审查 |

MSAL 负责 Microsoft 登录和令牌管理，不是 OneDrive 文件 API SDK。现有 [OneDriveClient.kt](../app/src/main/java/cn/coldlake/stellar/opensource/medialibrary/onedrive/OneDriveClient.kt) 已调用 Microsoft Graph，因此只为增加登录功能，不必再引入 Graph SDK。

许可依据：[MSAL MIT 文本](https://github.com/AzureAD/microsoft-authentication-library-for-android/blob/dev/LICENSE)、[Drive 客户端 Apache-2.0 文本](https://github.com/googleapis/google-api-java-client-services/blob/main/LICENSE)、[Apache 与 GPLv3 兼容性](https://www.apache.org/licenses/GPL-compatibility.html)。

### 本地使用和对外分发

在遵守 SDK 自身条款的前提下，可以在自己的机器上修改、构建和自用。GPL 对未对外传递的私人修改与发布二进制有不同要求，参见 [GPLv3 第 2、6 节](https://opensource.org/license/gpl-3.0)。把 APK 发给他人、上传公开下载站或上架商店，都需要重新检查分发条件；“由用户自行集成”不是许可证豁免。

本指南不为专有组件增加 GPL 链接例外。当前播放器还包含 GPL 模式的 MPV、libdvdread 和 libdvdnav，不能仅修改项目自己的声明就替第三方代码授予额外许可。准备分发含专有 SDK 的组合版本时，需要先解决其与当前 GPL 发行方案的兼容性；仅附上专有许可全文或删除检查脚本不能解决这一问题。参见 [Android SDK 条款](https://developer.android.com/studio/terms.html) 和 [项目第三方声明](../THIRD_PARTY_NOTICES.md)。

## 准备独立的应用注册

1. 按 [README 编译说明](../README.md#编译) 先构建原始项目，确认 SDK、JDK 和原生库环境可用。
2. 确定自己的 application ID 和签名。当前 application ID 是 `cn.coldlake.stellar.opensource`，位于 [app/build.gradle.kts](../app/build.gradle.kts)。如需独立安装，先修改自己的 application ID；不要为此盲目重命名 Kotlin 包或 namespace。
3. 创建自己的服务应用注册，记录包名、签名证书、client ID、重定向配置和授权范围。Debug、自己签名的 Release、商店重签名的安装包可能使用不同证书，应分别注册。Google Play App Signing 场景要使用实际安装包的应用签名证书，而非上传证书。
4. 公共客户端的 client ID 不是秘密。Android APK 不能保守 client secret，不要把 client secret、令牌或私有签名材料写入源码、资源、日志或提交记录。

当前 `local.properties` 只由构建脚本读取已实现的配置项。新增 `MSAL_CLIENT_ID` 等字段不会自动生效；如果希望按构建变体生成配置，需要自行补充资源生成和 Gradle 读取逻辑。

仓库已在 [settings.gradle.kts](../settings.gradle.kts) 配置 Google Maven、Maven Central 和 `display-mask` 的专用仓库。保持仓库定义在 settings 中，不要照搬旧教程向 app 模块增加 `repositories` 块。

## 集成 MSAL

### 注册 Microsoft 应用

在 Microsoft Entra 应用注册中选择与你的用户范围匹配的账户类型。需要同时支持个人 OneDrive 和组织账户时，选择支持组织目录及个人 Microsoft 账户的类型。添加 Android 平台，填写实际包名及签名哈希，保存门户生成的重定向 URI 和配置。

为 Microsoft Graph 添加委托权限 `Files.Read`，用于当前播放器的只读访问。组织策略可能要求管理员同意，且账户需要已配置 OneDrive。以下配置采用公共云；其他云环境应同时调整 authority 和 Graph 地址。

签名哈希按门户给出的命令生成。MSAL 使用的签名哈希与 Google 控制台要求的 SHA-1 显示格式不同，不要混用。注册过程及浏览器回调说明见 [Microsoft Android 接入教程](https://learn.microsoft.com/en-us/entra/identity-platform/tutorial-mobile-app-android-prepare-app)。

### 添加固定版本并检查依赖

在自己的 `app/build.gradle.kts` 的 `dependencies` 中添加：

```kotlin
implementation("com.microsoft.identity.client:msal:8.5.0")
```

此版本的发布 POM 声明以下依赖：

```text
com.microsoft.identity.client:msal:8.5.0
└─ com.microsoft.identity:common:24.7.0
   ├─ androidx.credentials:credentials-play-services-auth:1.2.2
   ├─ com.google.android.gms:play-services-fido:20.1.0
   └─ com.google.android.libraries.identity.googleid:googleid:1.1.0
```

来源：[MSAL POM](https://repo.maven.apache.org/maven2/com/microsoft/identity/client/msal/8.5.0/msal-8.5.0.pom)、[common POM](https://repo.maven.apache.org/maven2/com/microsoft/identity/common/24.7.0/common-24.7.0.pom)。这是发布声明，项目约束可能改变最终版本，应以 Gradle 解析结果为准。

选择浏览器登录不会自动移除这些依赖。不要把简单的 `exclude` 当作已验证方案；裁剪时还需要检查代码引用、合并后的 Manifest 及运行路径，避免缺类崩溃或失去条件访问能力。

### 配置 MSAL 和回调

新增 `app/src/main/res/raw/msal_config.json`。将占位值替换为自己门户中的配置，账户类型必须与注册一致：

```json
{
  "client_id": "YOUR_MICROSOFT_CLIENT_ID",
  "redirect_uri": "YOUR_PORTAL_GENERATED_REDIRECT_URI",
  "authorization_user_agent": "BROWSER",
  "broker_redirect_uri_registered": false,
  "account_mode": "MULTIPLE",
  "authorities": [
    {
      "type": "AAD",
      "audience": {
        "type": "AzureADandPersonalMicrosoftAccount"
      },
      "default": true
    }
  ],
  "logging": {
    "pii_enabled": false,
    "log_level": "ERROR"
  }
}
```

`MULTIPLE` 对应播放器可以保存多个云盘连接的行为。此配置先使用浏览器且不启用 broker 集成；有企业 broker 需求时，按 Microsoft 文档另行配置和验证。配置项说明见 [MSAL Android 配置](https://learn.microsoft.com/en-us/entra/msal/android/msal-configuration)。

在 [AndroidManifest.xml](../app/src/main/AndroidManifest.xml) 的 `<application>` 内加入门户要求的 `com.microsoft.identity.client.BrowserTabActivity`，设为 `android:exported="true"`，配置 `VIEW`、`DEFAULT`、`BROWSABLE` intent filter 和精确的 scheme、host、path。采用 `msauth` URI 时，host 是实际包名，path 是签名哈希路径；JSON URI 与 Manifest path 的编码形式按门户和官方教程填写。不要使用通配回调，也不要将现有 `OneDriveAuthorizationActivity` 改为任意应用可调用的导出入口。

### 接入现有授权页面

修改 [OneDriveAuthorization.kt](../app/src/main/java/cn/coldlake/stellar/opensource/medialibrary/onedrive/OneDriveAuthorization.kt)：

1. 用 `PublicClientApplication.createMultipleAccountPublicClientApplication` 和 `R.raw.msal_config` 初始化应用级 MSAL 实例；避免每次页面重组或请求都创建实例。
2. 用户点击连接后，通过 `AcquireTokenParameters` 请求 `https://graph.microsoft.com/Files.Read`。在 `AuthenticationCallback` 中分别处理成功、错误和取消；令牌使用 `IAuthenticationResult.accessToken`，不是 ID token。
3. 成功后沿用现有验证方式：`OneDriveClient({ suppliedToken }).use { it.account() }`，得到 `(driveId, displayLabel)`。重新授权已有连接时，先校验传入的 `account_id` 与返回的 `driveId` 相同，再更新绑定。
4. 保存 `driveId → MSAL account.id + authority` 的映射。**当前项目的 `account_id` 是网盘 ID，不是邮箱，也不是 MSAL 账户 ID。** 不要直接用 `account.username` 或缓存中的第一个账户替代它。
5. 保留 Activity 返回协议：成功返回 `RESULT_OK`，Intent 带 `account_id=driveId`、`account=displayLabel`；取消返回 `RESULT_CANCELED`。这与现有 [OneDriveServerForm.kt](../app/src/main/java/cn/coldlake/stellar/opensource/medialibrary/onedrive/OneDriveServerForm.kt) 对接。

交互与静默获取令牌的 API 见 [Microsoft 令牌获取文档](https://learn.microsoft.com/en-us/entra/msal/android/acquire-tokens)。刷新凭据由 MSAL 缓存管理；不要把首次 access token 保存下来就当成完整的 MSAL 集成。

### 实现静默刷新

`OneDriveClient` 已接受 `suspend (forceRefresh: Boolean) -> String`，遇到首次 401 会用 `true` 重试。当前 `OneDriveAuthorization.token(..., force)` 只是读本地手工令牌，并未实现刷新；需要将其替换为按映射选择账户的静默令牌获取。

下面是授权适配层的核心片段；`msal`、`account`、`authority` 和 `forceRefresh` 分别来自已初始化实例、已验证的绑定和调用参数：

```kotlin
import com.microsoft.identity.client.AcquireTokenSilentParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val parameters = AcquireTokenSilentParameters.Builder()
    .forAccount(account)
    .fromAuthority(authority)
    .withScopes(listOf("https://graph.microsoft.com/Files.Read"))
    .forceRefresh(forceRefresh)
    .build()

val accessToken = withContext(Dispatchers.IO) {
    msal.acquireTokenSilent(parameters).accessToken
}
```

调用前确认账户仍在 MSAL 缓存中；`MsalUiRequiredException` 应转为项目的重新授权状态，由用户在界面中继续，不能从播放或扫描后台任务直接弹出登录页面。按账户合并并发刷新，保留协程取消行为。账户模型和缓存规则见 [Microsoft 账户说明](https://learn.microsoft.com/en-us/entra/msal/android/accounts-overview)。

解除绑定时清理对应映射和旧手工令牌；若多个连接共用一个 MSAL 账户，不要在删除其中一个连接时删除其他连接仍需使用的账户缓存。

## 集成 Google Drive

### 注册 Google 应用和权限

在自己的 Google Cloud 项目中启用 Google Drive API，配置 OAuth 同意屏幕、支持邮箱、隐私政策和测试用户。采用 Android 原生授权组件时，创建匹配实际包名与签名证书 SHA-1 的 Android OAuth 客户端。

当前播放器需要浏览已有文件并读取媒体内容，现有范围是：

```text
https://www.googleapis.com/auth/drive.readonly
```

该范围属于受限范围，公开使用时需要按 Google 的适用规则完成验证。`drive.file` 只覆盖相应授权文件，不能在保持当前全盘浏览体验的同时直接替换；仅请求元数据权限也不能读取视频。范围和验证要求见 [Google Drive 权限说明](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)。

### 添加开源 API 客户端

如需用官方 Java 客户端替代部分手写 API 请求，可在 `dependencies` 中添加以下固定版本：

```kotlin
implementation("com.google.apis:google-api-services-drive:v3-rev20261011-2.0.0")
implementation("com.google.api-client:google-api-client:2.9.2")
implementation("com.google.http-client:google-http-client-gson:2.2.0")
```

版本可在 [Drive Maven 元数据](https://repo.maven.apache.org/maven2/com/google/apis/google-api-services-drive/maven-metadata.xml)、[API Client 元数据](https://repo.maven.apache.org/maven2/com/google/api-client/google-api-client/maven-metadata.xml)、[Gson 适配器元数据](https://repo.maven.apache.org/maven2/com/google/http-client/google-http-client-gson/maven-metadata.xml) 查询。接入方法见 [Google Drive 客户端安装文档](https://developers.google.com/workspace/drive/api/guides/downloads)。

这些依赖提供 API 调用能力，不会自动完成 Android 登录，也不能永久使用首次 access token。不要为获得 `Drive` 类而额外添加 `play-services-auth` 或旧 `GoogleAccountCredential` 登录示例所需的组件。

以下函数用已经合法取得的 access token 查询当前账户，与播放器现有的 `about.user.emailAddress` 识别方式一致：

```kotlin
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun driveAccountEmail(accessToken: String): String =
    withContext(Dispatchers.IO) {
        val service = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance()
        ) { request ->
            request.headers.authorization = "Bearer $accessToken"
            request.connectTimeout = 15_000
            request.readTimeout = 30_000
        }.setApplicationName("Your Player").build()

        service.about().get()
            .setFields("user(emailAddress)")
            .execute()
            .user.emailAddress
            ?.takeIf { it.isNotBlank() }
            ?: error("Google Drive did not return an account email")
    }
```

此片段仅展示账户查询，不代替项目完整 HTTP 层；接入其他操作时需要继续处理取消、限流、分页、错误映射和令牌过期。保留 [DriveClient.kt](../app/src/main/java/cn/coldlake/stellar/opensource/medialibrary/drive/DriveClient.kt) 现有的媒体 Range 请求、响应关闭和下载取消逻辑，避免把视频整体读入内存。

### 使用 Google 官方 Android 授权组件

如选择官方 Android 授权流程，在自己的集成分支中另加：

```kotlin
implementation("com.google.android.gms:play-services-auth:22.0.0")
```

这是独立于开源 Drive API 客户端的专有依赖，适用前述分发边界。仅集成官方 API 客户端时不需要添加这一行。

按照 [Android 官方授权文档](https://developer.android.com/identity/authorization)，在 [DriveAuthorization.kt](../app/src/main/java/cn/coldlake/stellar/opensource/medialibrary/drive/DriveAuthorization.kt) 中实现以下流程：

1. 用 `AuthorizationRequest.builder().setRequestedScopes(...)` 请求 Drive 范围，经 `Identity.getAuthorizationClient(activity).authorize(request)` 发起授权。请求项是 `Scope("https://www.googleapis.com/auth/drive.readonly")`。
2. 若结果 `hasResolution()`，用 `ActivityResultContracts.StartIntentSenderForResult` 启动返回的 PendingIntent；在回调中调用 `getAuthorizationResultFromIntent`，并处理用户取消和错误。
3. 检查实际授予的范围是否满足当前操作，再从 `AuthorizationResult.accessToken` 获取令牌并校验非空；用户可能只授予部分范围，Google 登录的 ID token 也不能用于访问 Drive。
4. 调用现有 `DriveClient({ accessToken }).use { it.account() }`，或上面的账户查询函数，验证账户。重新授权时检查它与 Intent 中已有的 `account` 一致；验证成功后才能替换旧绑定。成功返回 `RESULT_OK` 和 `account=email`，供 [DriveServerForm.kt](../app/src/main/java/cn/coldlake/stellar/opensource/medialibrary/drive/DriveServerForm.kt) 使用。
5. 获取后续令牌时保持账户绑定，避免使用设备默认账户给另一个连接发请求。需要用户再次同意时，只在前台授权入口处理。

当前 `DriveClient` 在首次 401 后调用 `invalidate(token)`，然后再次请求 `tokenProvider()`。SDK 适配层应清理相应的过期令牌缓存，再尝试获取令牌；官方授权组件提供 `clearToken` 用于处理这种情况。不能只清掉项目 SharedPreferences 后继续反复得到 SDK 缓存的同一个失效令牌，也不能循环无限重试。相关接口见 [AuthorizationClient](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationClient)。

为用户提供明确的解除 Google 账户授权入口，按官方接口使用 `revokeAccess` 并清理相关本地绑定。撤销可能影响同一应用和账户下的多个连接，应在界面中说明影响；不要把撤销全部授权当作普通 401 的刷新手段。

如果需要保持不含专有 Google SDK 的发行版，应保留当前令牌入口，或另行设计满足 Google 平台规则的授权方案。不要直接复制桌面版 OAuth 教程，在 Android 上假设 localhost 回调或任意自定义 scheme 都可用；这些方式有平台限制，参见 [Google 原生应用 OAuth 文档](https://developers.google.com/identity/protocols/oauth2/native-app)。

## 与现有浏览和播放代码衔接

| 代码入口 | 当前约定 | 集成时必须保留的行为 |
| --- | --- | --- |
| `DriveAuthorization.token` / `invalidate` | 按邮箱获取令牌，401 后失效并重试一次 | 每次令牌获取与同一账户对应，授权失败返回可恢复状态 |
| `OneDriveAuthorization.token` | 按当前网盘 ID 获取令牌，接收 `force` 参数 | 映射到正确 MSAL 账户；`force=true` 实际请求刷新 |
| 两个 `AuthorizationActivity` | 通过 Activity result 返回绑定身份 | 取消不覆盖已有连接，重新授权不切换已有连接的账户 |
| [CloudProvider.kt](../app/src/main/java/cn/coldlake/stellar/opensource/medialibrary/cloud/CloudProvider.kt) | 分页浏览、增量同步及稳定文件标识 | SDK 元数据请求需要映射到现有模型，保留游标和同步语义 |
| [CloudMediaClient.kt](../app/src/main/java/cn/coldlake/stellar/opensource/medialibrary/cloud/CloudMediaClient.kt) | 按需读取媒体并允许取消 | 保持 Range、HEAD、流式响应和连接释放 |

OneDrive 的 `@microsoft.graph.downloadUrl` 是预授权 URL，不能向其所在主机附加 Graph Bearer token。保留当前 [OneDriveClient.kt](../app/src/main/java/cn/coldlake/stellar/opensource/medialibrary/onedrive/OneDriveClient.kt) 对下载 URL 和 Graph API 请求的区分。

更新自己的 [隐私说明](../PRIVACY.md)，反映引入 SDK 后实际发生的登录、令牌缓存及数据处理。原有“用户手动提供令牌”的说明不再适用于自动登录版本。

## 检查构建和发布内容

在完成自己分支的代码后，从仓库根目录运行：

```sh
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
./gradlew :app:dependencies --configuration debugRuntimeClasspath
./gradlew :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency play-services
./gradlew :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency googleid
./gradlew :app:assembleDebug :app:assembleRelease
```

查看每个实际打包依赖的许可证、原始声明及源码位置。只检查顶层 SDK 的 MIT / Apache 标记不足以覆盖完整依赖链；仅在代码中不调用某项功能也不表示该组件已从 APK 中移除。

完成依赖审查后更新应用内清单：

```sh
./gradlew -I tools/about-dependencies.init.gradle :app:exportAboutDependencies
python3 tools/generate_about_licenses.py
```

当前 [许可证生成器](../tools/generate_about_licenses.py) 会拒绝所列 Google 专有组件；不要为了生成成功而删除检查并宣称合规。引入 MSAL 时还需要从准确版本上游保存脚本所引用的 `msal-MIT.txt`、`microsoft-common-MIT.txt` 等补充声明。脚本失败时应先核对具体原因和对应文件。

现有导出任务只覆盖 Release；若也分发 Debug 或新增其他 flavor，需扩展清单生成流程覆盖各自的 runtime classpath。发布包中的清单应与该包的依赖一致。

对外分发还应提供准确版本的完整对应源码、构建脚本和适用声明。当前 CI 的 `git archive` 只归档已提交文件，不会自动收集 Gradle 缓存里的第三方源码。需要补齐相关源码或满足许可证要求的获取安排，并检查 APK、源码包、说明和版本是否对应。参见 [第三方声明的二进制分发要求](../THIRD_PARTY_NOTICES.md#redistributing-binaries)。

### 账户和播放验收

使用自己注册的测试应用和账户，至少验证以下场景：

- 首次登录成功、拒绝授权、取消及网络中断；取消不会破坏原有连接。
- 两个账户分别建立连接，重新授权时选择错误账户会被阻止，重启进程后仍使用正确绑定。
- access token 过期及首次 401，刷新成功后恢复；撤销授权后提示用户重新连接，不无限重试或后台弹窗。
- Google Play services 不可用、浏览器不可用、组织账户策略拒绝时，显示可理解的失败状态。
- 多页目录、媒体库扫描、播放、拖动进度、字幕读取和停止播放时的请求取消。
- Debug 与 Release 的包名、签名及回调各自正确；如使用商店重签名，验证商店安装包。
- 日志、崩溃报告、源码和发布附件中没有 access token、refresh token、预授权下载 URL 或私有签名材料。

编译成功只能证明构建通过；上述真实授权和播放行为仍需要在设备上验证。
