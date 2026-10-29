# 隐私 / Privacy

播放记录、媒体库索引、设置和缓存存放于设备。在线功能按以下方式处理数据。

- 启用在线识别后，影片名称、年份、TMDB / IMDb ID、季集编号和语言发送给 TMDB；海报直接从 TMDB 图片服务加载。
- 配置 Trakt 后，详情页向 `apiz.trakt.tv` 发送媒体标识和可选季集编号以请求评分。
- 连接网盘、NAS、WebDAV 或 Emby / Jellyfin 时，应用向你配置的服务发送认证信息、目录和媒体读取请求。支持的媒体服务器可能接收播放进度更新。
- Google Drive / OneDrive 访问令牌由用户提供，并使用 Android Keystore 加密保存。其他服务器凭据由应用本地凭据存储管理。系统备份关闭。删除连接会移除连接资料；清除应用数据会同时移除已保存的令牌；服务提供方的数据由其政策管理。
- DLNA 发现会向局域网发送 SSDP 查询。投屏时，选中的设备可以通过本机临时 HTTP 服务读取选中的媒体及字幕。
- 用户选择导出截图或片段时，仅写入所选位置；主动分享由 Android 系统分享面板交给用户选择的应用。

API key 随构建嵌入 APK；APK 接收者能够提取它。发布者应使用自己的适用凭据并遵守相关服务条款。第三方服务和 fork 的隐私政策可能不同。

---

Playback history, library indexes, preferences and caches remain on the device. Online features process data as follows.

- Online identification sends titles, years, TMDB / IMDb identifiers, season/episode numbers and language to TMDB. Artwork loads from TMDB's image service.
- If configured, Trakt ratings requests send media identifiers and optional episode coordinates to `apiz.trakt.tv`.
- Cloud, NAS, WebDAV and Emby / Jellyfin connections send authentication and file requests to the services you configure. Supported media servers may receive playback progress updates.
- User-supplied Drive / OneDrive access tokens are encrypted using Android Keystore. Other server credentials use the app's local credential store. System backup is disabled. Removing a connection removes its profile; clearing app data also removes saved tokens; provider-side information follows the provider's policies.
- DLNA discovery sends SSDP queries on the local network. Casting lets the selected renderer read the selected media/subtitle through a temporary local HTTP service.
- Exports go to a location you select. Sharing hands content to an app chosen through Android's share sheet.

Build-time API keys are embedded in the APK and can be extracted. Distributors must supply appropriate credentials and follow provider terms. Third-party services and forks may have different privacy practices.
