# Integrating Google Cast (Chromecast / Chrome Cast)

[简体中文](GOOGLE_CAST_INTEGRATION.md) · English

This guide is for developers who modify and build the player themselves. It explains how to integrate the Google Cast Android Sender SDK in your own branch. **Because of unresolved license compatibility issues, the project cannot directly provide the Google Cast SDK in the current GPL distribution.** The default build supports only the project's own DLNA implementation. It has no Google Cast dependency, receiver configuration or hidden switch that enables the feature.

Reviewed on 2026-10-29. The versions and snippets below illustrate integration; they have not been compiled and tested on devices as a complete Google Cast feature in this project. After configuration, you still need to implement the session and player adapters. Adding a dependency or an App ID alone does not enable casting.

## Licensing boundaries

Project-owned code uses GPL-3.0-or-later, and the player also includes MPV in GPL mode, libdvdread and libdvdnav. The Google Cast framework's [23.0.0 POM](https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-cast-framework/23.0.0/play-services-cast-framework-23.0.0.pom) declares the [Android SDK license](https://developer.android.com/studio/terms.html). Using Cast is also subject to the [Google Cast SDK Additional Developer Terms](https://developers.google.com/cast/docs/terms). The Apache-2.0 licenses of sample code or AndroidX do not describe the license of the Cast SDK itself.

Subject to the SDK's own terms, private modification, building and use have different requirements from redistribution; see [GPLv3 sections 2 and 6](../LICENSE). Before giving the integrated APK to others, publishing it for download or listing it in an app store, resolve the combined build's license compatibility and meet the applicable source, notice and service terms obligations. **Self-integration is not a distribution exemption, and this guide grants no GPL linking exception.** Changing the project's own license notice cannot grant exceptions for third-party GPL components. See the [third-party notices](../THIRD_PARTY_NOTICES.md#redistributing-binaries) for additional distribution requirements.

## 1. Prepare the build environment and receiver

1. Build the unmodified project using the [README build instructions](../README.en.md#build) to confirm that the Android SDK, JDK and native libraries are available. Make the following changes in your own branch. If your build needs to coexist with the original, change your `applicationId`; this does not require renaming Kotlin packages or the `namespace`.
2. Prepare a physical Android device with working Google Play services and a receiver that supports Google Cast. Connect both to a local network where they can reach each other. Guest Wi-Fi, AP isolation or a VPN may block discovery or prevent the receiver from reading media served by the phone.
3. Choose a receiver type before configuring the Receiver App ID used by the Sender:

| Receiver | Configuration | Intended use |
| --- | --- | --- |
| Default Media Receiver | Use `CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID` | Start by verifying ordinary media playback without a custom receiver UI |
| Styled Media Receiver | Register in your own Cast console and obtain an App ID | Customize receiver styling within the supported capabilities |
| Custom Web Receiver | Register your own receiver and configure its hosted URL | Custom authentication, playback logic or other receiver features |

The official [registration guide](https://developers.google.com/cast/docs/registration) states that the Default Media Receiver does not require registering your own receiver application. Styled / Custom Receivers must be registered in the [Google Cast SDK Developer Console](https://cast.google.com/publish/); unpublished receivers require registered test devices. A Receiver App ID is not an Android package name, Google Cloud OAuth client ID or API key, and it is not a secret. Do not use someone else's receiver App ID.

With Google Cast media loading, the receiver fetches and decodes the stream. The phone's MPV output and decoding capabilities are not transferred to the TV. First verify connectivity using an HTTPS MP4 that you control, requires no sign-in and is accessible to the receiver. Add local files and NAS sources afterward.

## 2. Add SDK dependencies

Add the following to `dependencies` in your branch's [app/build.gradle.kts](../app/build.gradle.kts):

```kotlin
implementation("com.google.android.gms:play-services-cast-framework:23.0.0")
implementation("androidx.appcompat:appcompat:1.8.0")
```

The Cast version is based on [Google Maven metadata](https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-cast-framework/maven-metadata.xml) and the [official release notes](https://developers.google.com/cast/docs/release-notes). The AppCompat version matches the project's existing dependency constraints and supports the Activity and route dialogs described below. Do not use `+` or dynamic versions; inspect the actual dependencies again when upgrading.

The 23.0.0 POM brings in components such as `play-services-cast` and `play-services-base`, and also declares `androidx.mediarouter:mediarouter:1.9.0-beta01`. A top-level SDK version without a beta suffix does not mean all transitive dependencies are stable. Inspect the resolved versions and test the chosen combination rather than blindly excluding or downgrading SDK requirements.

[settings.gradle.kts](../settings.gradle.kts) already provides `google()` and `mavenCentral()` and prohibits modules from declaring their own repositories. Do not add a `repositories` block to the app. This integration also does not require `play-services-cast-tv`, which is for Android TV receivers rather than a required dependency for the phone Sender.

## 3. Configure OptionsProvider and the Manifest

In your branch, create `app/src/main/java/cn/coldlake/stellar/opensource/casting/GoogleCastOptionsProvider.kt`:

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

For your own Styled / Custom Receiver, replace the default constant with your resource value, such as `context.getString(R.string.google_cast_receiver_app_id)`, and add the corresponding string with `translatable="false"` in `res/values`. The current Gradle script does not read Cast settings, so adding `CAST_APP_ID` to `local.properties` alone has no effect.

The example reuses the project's `CastingService` for notifications, lock-screen controls and the local relay service, so it disables the SDK's own notification and `MediaSession`. This requires completing the service and controller adaptation described below. If you choose the SDK's built-in controls instead, adjust the existing service's responsibilities to avoid duplicate notifications and competing media sessions. See [CastMediaOptions.Builder](https://developers.google.com/android/reference/com/google/android/gms/cast/framework/media/CastMediaOptions.Builder) for these settings.

Add the following inside the existing `<application>` in [AndroidManifest.xml](../app/src/main/AndroidManifest.xml):

```xml
<meta-data
    android:name="com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME"
    android:value="cn.coldlake.stellar.opensource.casting.GoogleCastOptionsProvider" />
```

Keep the existing `StellarApplication` and service declarations. The Provider must be accessible to the SDK and have a no-argument constructor; the Kotlin class above meets these requirements. If you change packages or enable code shrinking, check the fully qualified class name in the Manifest and the SDK's consumer rules, then verify that Release can instantiate the Provider. See [OptionsProvider](https://developers.google.com/android/reference/com/google/android/gms/cast/framework/OptionsProvider).

## 4. Add a Cast button to the Compose UI

The project's [MainActivity](../app/src/main/java/cn/coldlake/stellar/opensource/MainActivity.kt) currently extends `ComponentActivity`, and its XML theme uses the platform `Theme.Material.NoActionBar`. The standard `MediaRouteButton` route dialogs need a host with Fragment support. Placing the button inside `AndroidView` alone does not complete the integration.

Use the following approach:

1. Change the base class of `MainActivity` in your branch to `androidx.appcompat.app.AppCompatActivity`, preserving the existing Compose `setContent` and lifecycle logic.
2. Give this Activity an XML theme derived from `Theme.AppCompat.DayNight.NoActionBar`. You can add a dedicated theme based on the existing [themes.xml](../app/src/main/res/values/themes.xml) and set `android:theme` on MainActivity's Manifest declaration. Preserve the player's required window attributes and verify light/dark mode, edge-to-edge layout and picture-in-picture behavior.
3. On the main thread in `onCreate`, call `CastContext.getSharedInstance(applicationContext, executor)`. Here, `executor` is an application-owned background executor used to load the internal Cast module. Do not create a new executor or initialize the SDK on every Compose recomposition. Handle both success and failure of the returned Task, and show an interactive Cast button only after success. See [CastContext](https://developers.google.com/android/reference/com/google/android/gms/cast/framework/CastContext) for main-thread requirements and asynchronous initialization.
4. If initialization fails or Google Play services is unavailable, show a recoverable error state while keeping local playback and DLNA available. UI callbacks must follow the Activity lifecycle, and session management must not retain an Activity long term.

Once the SDK is ready, place the following button in the top-level toolbar of pages showing castable content. `AndroidView` must use the Activity's themed Context, not the application Context:

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

Use the SDK's standard discovery and route selection flow. Do not pass Google Cast devices to DLNA's SSDP / SOAP code. The current [CastingDialog.kt](../app/src/main/java/cn/coldlake/stellar/opensource/casting/CastingDialog.kt) remains a DLNA device list; you can keep that entry point and provide the standard button above for Google Cast. Check button placement and interactions against the [Cast design checklist](https://developers.google.com/cast/docs/design_checklist).

The existing Manifest already declares network and local network permissions. Retain `CastingController.permitted()` and runtime permission handling; the project currently checks `ACCESS_LOCAL_NETWORK` on API 37 and later. The new Cast entry point must also participate in the applicable authorization flow, especially when the phone relays media. Do not rely on the DLNA dialog being the only place that requests permission.

## 5. Integrate with the existing playback controller

The following areas need changes. **`GoogleCastAdapter` and `GOOGLE_CAST` are additions you must implement; the repository does not already contain them.**

| Location | Current implementation | Required integration work |
| --- | --- | --- |
| [CastModels.kt](../app/src/main/java/cn/coldlake/stellar/opensource/casting/CastModels.kt) | `CastProtocol` contains only `DLNA`; `CastAdapter` defines remote commands and a status flow | Add `GOOGLE_CAST` and implement `GoogleCastAdapter : CastAdapter`, retaining `RemoteStatus` |
| [CastingController.kt](../app/src/main/java/cn/coldlake/stellar/opensource/casting/CastingController.kt) | `connect()` selects `dlna` directly; discovery and subtitle capabilities are also tied to DLNA | Select an adapter by protocol, add an SDK session entry point, and review all DLNA branches and cleanup paths |
| [PlayerViewModel.kt](../app/src/main/java/cn/coldlake/stellar/opensource/presentation/PlayerViewModel.kt) | Owns the controller and supplies local, HTTP, NAS and cloud source resolution | Supply application-level Cast session dependencies to the controller while keeping source credentials encapsulated |
| [PlaybackRouter.kt](../app/src/main/java/cn/coldlake/stellar/opensource/presentation/PlaybackRouter.kt) | `attachRemote()` pauses local playback; `detachRemote()` restores local routing | Acquire and release remote playback through the controller to avoid simultaneous playback on the phone and receiver |
| [CastingService.kt](../app/src/main/java/cn/coldlake/stellar/opensource/casting/CastingService.kt) | Notifications, lock-screen and volume controls, and the foreground relay service | Reuse the same remote state and commands; verify disconnection, background behavior and service shutdown |

### Sessions and lifecycle

Register a `SessionManagerListener<CastSession>` through `CastContext.sessionManager`, handling started, resumed, suspended and ended events, plus start/resume failures. Session management should own the listener, with paired registration and removal. Run SDK session and remote client operations on the main thread.

After the user selects a device with the standard button and the session starts successfully, pass the current `CastSession`, receiver identity and pending media to the new adapter. Then enter the controller's source preparation, remote routing and `load()` flow. `load(device, ...)` should verify that the bound session matches the target device. Do not unconditionally reload on every recomposition, reconnection or `onSessionResumed`. On resume, first match the receiver's current media and the local session identity, then synchronize state so that you do not replace another Sender's playback.

Use `RemoteMediaClient` callbacks and progress listeners to update `RemoteStatus`, removing them when the session changes or ends. Preserve the controller's existing `generation`, command mutex and stale callback checks. When the receiver media state is `IDLE`, distinguish completion, stop, cancellation and loading failure. Enable pause, seeking and other capabilities only when the receiver declares support.

### Media loading and controls

You can add the following conversion function in the `casting` package for the new adapter. It handles prepared, receiver-accessible, on-demand audio/video only; live streams, DRM and subtitles are not covered:

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

The adapter obtains the client from the bound `CastSession.remoteMediaClient` and calls `load(media.toGoogleCastLoadRequest(positionMs, play))`. This returns an asynchronous `PendingResult`. Wait for it and check `result.status.isSuccess`; propagate failures, timeouts and cancellation to the controller instead of reporting success when the request is merely issued. The suspend methods in `CastAdapter` require callback bridging in the adapter, including coroutine cancellation and late-result handling.

| `CastAdapter` operation | Google Cast interface |
| --- | --- |
| `play()` / `pause()` | `RemoteMediaClient.play()` / `pause()` |
| `seek(positionMs)` | `RemoteMediaClient.seek(MediaSeekOptions.Builder().setPosition(positionMs).build())` |
| `volume(value)` | `CastSession.setVolume(value.coerceIn(0f, 1f).toDouble())` |
| `stop()` | `RemoteMediaClient.stop()`; use `SessionManager.endCurrentSession(true)` separately when ending the whole session |
| `status` | Update `StateFlow<RemoteStatus>` from media state, progress and session volume callbacks |

The Android load, progress and seek APIs use milliseconds. See [RemoteMediaClient](https://developers.google.com/android/reference/com/google/android/gms/cast/framework/media/RemoteMediaClient) and [CastSession](https://developers.google.com/android/reference/com/google/android/gms/cast/framework/CastSession).

Distinguish stopping media, disconnecting the Sender and ending the receiver session. Stop remote playback before resuming on the phone; a failed stop must not automatically cause playback on both devices. Release listeners and relay resources on network loss, return to the phone, opening another media item and `close()`. Prevent session-ended callbacks from causing recursive cleanup. If the process is killed, the phone's old relay URL becomes invalid; restoring the SDK session alone cannot restore local-file playback.

## 6. Media URLs, relaying and subtitles

| Source | Integration and limitations |
| --- | --- |
| Unauthenticated HTTP(S) on-demand media | Pass a URL accessible to the receiver; check MIME type, certificates, redirects and codec support |
| `content://`, `file://` or phone-local paths | Do not pass these directly to the TV; serve a local network HTTP URL through `LocalCastSource` and `CastMediaServer` |
| SMB / NFS / cloud drives | Reuse `NasCastSource`, `CastByteSource` and existing source resolution; the receiver reads the phone's relay URL |
| HTTP requiring Cookie, Authorization or custom headers | Preserve authentication through a controlled relay, or implement it in your own receiver; the Default Receiver does not inherit the phone's OkHttp request headers |
| HLS / DASH | Manifests, segments, keys and subtitles must all be accessible to the receiver; relaying only the manifest does not solve child-resource access |

[CastMediaServer.kt](../app/src/main/java/cn/coldlake/stellar/opensource/casting/CastMediaServer.kt) already provides random session paths, HEAD, Range, OPTIONS and CORS, so it can serve as the relay foundation. Preserve local network address binding, resource size limits, request cleanup and destruction when the session ends. Do not put localhost addresses, NAS credentials, OAuth tokens or source URLs containing credentials into device lists, saved Compose state or logs. The random local URL itself grants access; use it only on a trusted local network and within the current session.

The current `CastingController.prepare()` restricts MOV / MKV to DLNA and rejects DASH and HLS/DASH that would need relaying. `connect()` also restricts existing Emby sessions to the DLNA path with an HTTP source. Adjust and test these conditions according to actual support when adding the new protocol. Removing the checks does not make every source supported. The existing relay does not transcode: MPV's ability to play a file does not mean the receiver supports its container, audio tracks, HDR or codecs. Disc menus also cannot be sent directly. Use the target device's capabilities and the [official media support table](https://developers.google.com/cast/docs/media) as the reference.

The current [CastSubtitle.kt](../app/src/main/java/cn/coldlake/stellar/opensource/casting/CastSubtitle.kt) outputs UTF-8 SRT, and the relay serves `.srt` files with the DLNA `CaptionInfo.sec` response header. These do not automatically become Google Cast subtitles. Add a separate subtitle conversion path for Cast: for example, generate actual UTF-8 WebVTT and serve a `.vtt` URL as `text/vtt`. Use `MediaTrack.Builder(id, MediaTrack.TYPE_TEXT)` with a unique ID, language, `SUBTYPE_SUBTITLES` and an accessible subtitle URL, build the track and pass it to `MediaInfo.Builder.setMediaTracks()`. After loading, select subtitles with `setActiveMediaTracks()`. Changing only the extension or Content-Type is insufficient.

Both subtitle and media resources must meet the receiver's CORS requirements. ASS effects and bitmap subtitles require a separate conversion or burn-in approach. Preserve the DLNA SRT path, and change DLNA-specific capability checks such as the current `canChangeSubtitle()` to depend on adapter capabilities. See the [official media tracks guide](https://developers.google.com/cast/docs/android_sender/media_tracks).

## 7. Build checks and device verification

After completing the adapters in your branch, inspect the resolved dependencies and build from the repository root:

```sh
./gradlew :app:dependencies --configuration debugRuntimeClasspath
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
./gradlew :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency play-services-cast
./gradlew :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency mediarouter
./gradlew :app:assembleDebug :app:assembleRelease
```

The default project's [license inventory generator](../tools/generate_about_licenses.py) rejects proprietary components such as `com.google.android.gms`. This check is expected to block the script in a branch containing the integration. Do not remove the check, mislabel components as Apache-2.0 or reuse an old inventory to claim distribution compliance. Before distribution, resolve license compatibility, then maintain notices, corresponding source and [privacy information](../PRIVACY.md) that match the packaged contents. Your privacy information must reflect the added SDK, receiver and service communications.

Verify at least these scenarios on your own devices:

- Missing Google Play services or module loading failure, denied local network permission, no devices, cancelled route selection and an incorrect Receiver App ID; local playback and DLNA remain usable after failure.
- Connecting, pausing, resuming, seeking, changing volume, stopping and returning to the phone with an ordinary HTTPS MP4; the UI, notification and lock screen show the same session state.
- Relaying local files, NAS and authenticated media; confirm from another local network device that the URL is accessible and HEAD / Range responses are correct. Old URLs stop working after the session ends.
- Subtitle loading, switching and disabling; unsupported codecs, audio tracks or disc navigation produce clear failures rather than endless buffering.
- Rotation, light/dark themes, backgrounding, screen lock, Wi-Fi changes, receiver power loss, control from other Senders and process recreation; listeners are not registered twice and stale callbacks do not overwrite new sessions.
- Provider initialization works in both Debug and your signed Release build. There are no duplicate media notifications, simultaneous playback on both devices or relay services left running after the session ends.
- Logs, crash reports and release attachments contain no access tokens, source credentials or session media URLs.

A successful build only verifies compilation. It does not replace end-to-end testing on receiver devices.
