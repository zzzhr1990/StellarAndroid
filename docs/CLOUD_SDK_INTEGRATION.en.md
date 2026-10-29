# Integrating MSAL and Google Drive

[简体中文](CLOUD_SDK_INTEGRATION.md) · English

**Because of unresolved license compatibility issues, this project cannot directly include MSAL's default dependency combination or the Google native authorization SDK in the current GPL distribution.** Specifically, the reviewed MSAL 8.5.0 dependency graph includes proprietary Google components, and the Google native authorization component `play-services-auth` also uses a proprietary license. Compatibility between these components and the current distribution remains unresolved.

The default build therefore uses access tokens supplied by users and does not provide SDK sign-in or automatic token renewal out of the box. Developers who need these features must follow this guide to integrate the code, register their own service applications and compile their own branch. Supplying a client ID alone does not enable SDK sign-in. Distributing the resulting application still requires resolving the applicable licensing issues; asking users to compile it themselves does not waive those obligations.

The open-source Google Drive API client uses Apache-2.0, which is compatible with GPLv3, and is not subject to the licensing obstacle described above. It is separate from the Google SDK responsible for account authorization. This guide also covers optional integration of that API client.

Reviewed on 2026-10-29. The pinned versions below illustrate integration and dependency review; they are neither long-term version recommendations nor evidence of completed end-to-end testing in this player. Recheck the resolved dependencies when upgrading.

## Choose an integration approach

| Goal | Integration point | Licensing boundary |
| --- | --- | --- |
| OneDrive sign-in and automatic token acquisition | MSAL, retaining the existing Microsoft Graph HTTP client | MSAL itself is MIT; the default transitive dependencies of 8.5.0 include proprietary Google components |
| Call the Drive API through the official client | `google-api-services-drive` and the open-source Java clients | Apache-2.0 is compatible with this project's GPLv3 license; distribution obligations such as notices and corresponding source still apply |
| Request Google Drive authorization on Android | Google Identity Services `AuthorizationClient` | Supplied by `play-services-auth`; review it separately from the open-source Drive API client |

MSAL handles Microsoft sign-in and token management; it is not a OneDrive file API SDK. The existing [OneDriveClient.kt](../app/src/main/java/cn/coldlake/stellar/opensource/medialibrary/onedrive/OneDriveClient.kt) already calls Microsoft Graph, so adding sign-in does not require a Graph SDK.

License references: [MSAL MIT license](https://github.com/AzureAD/microsoft-authentication-library-for-android/blob/dev/LICENSE), [Drive client Apache-2.0 license](https://github.com/googleapis/google-api-java-client-services/blob/main/LICENSE), and [Apache/GPLv3 compatibility](https://www.apache.org/licenses/GPL-compatibility.html).

### Local use and redistribution

Subject to the SDK's own terms, you can modify, build and use the application privately on your own machine. The GPL distinguishes private modifications that are not conveyed to others from binary distribution; see [GPLv3 sections 2 and 6](https://opensource.org/license/gpl-3.0). Giving an APK to others, publishing it for download or listing it in an app store requires reviewing the distribution conditions. Self-integration is not a licensing exemption.

This guide grants no GPL linking exception for proprietary components. The player also includes MPV in GPL mode, libdvdread and libdvdnav; changing the project's own notices cannot grant additional permissions for third-party code. Before distributing a combined build with proprietary SDKs, resolve compatibility with the current GPL distribution. Including the proprietary license text or removing a check script does not resolve that issue. See the [Android SDK terms](https://developer.android.com/studio/terms.html) and [project third-party notices](../THIRD_PARTY_NOTICES.md).

## Prepare your own application registrations

1. Build the unmodified project using the [README build instructions](../README.en.md#build) to confirm that the SDK, JDK and native libraries are available.
2. Choose your application ID and signing identity. The current application ID is `cn.coldlake.stellar.opensource`, defined in [app/build.gradle.kts](../app/build.gradle.kts). Change your application ID first if you need a separate installation; do not rename Kotlin packages or the namespace just for this purpose.
3. Register your own service applications and record the package name, signing certificate, client ID, redirect configuration and authorization scopes. Debug, locally signed Release and store-resigned builds may use different certificates and need separate registrations. With Google Play App Signing, use the app signing certificate of the installed application, not the upload certificate.
4. A public client's client ID is not a secret. An Android APK cannot keep a client secret confidential. Do not place client secrets, tokens or private signing material in source code, resources, logs or commits.

The build script reads only the settings already implemented for `local.properties`. Adding fields such as `MSAL_CLIENT_ID` does not make them take effect automatically. To generate configuration for different build variants, implement the resource generation and Gradle configuration handling yourself.

[settings.gradle.kts](../settings.gradle.kts) already configures Google Maven, Maven Central and the dedicated repository for `display-mask`. Keep repository definitions in settings; do not copy older tutorials that add a `repositories` block to the app module.

## Integrate MSAL

### Register a Microsoft application

In Microsoft Entra app registrations, choose an account type that matches your intended users. To support both personal OneDrive and organizational accounts, choose the option that supports organizational directories and personal Microsoft accounts. Add the Android platform, enter the actual package name and signature hash, and save the redirect URI and configuration generated by the portal.

Add the Microsoft Graph delegated permission `Files.Read` for the player's current read-only access. Organization policies may require administrator consent, and the account must have OneDrive provisioned. The following configuration uses the public cloud; for other cloud environments, adjust both the authority and Graph endpoint.

Generate the signature hash using the command provided by the portal. MSAL's signature hash format differs from the SHA-1 display format required by Google's console; do not interchange them. See the [Microsoft Android integration tutorial](https://learn.microsoft.com/en-us/entra/identity-platform/tutorial-mobile-app-android-prepare-app) for registration and browser callback setup.

### Add a pinned version and inspect dependencies

Add the following to `dependencies` in your own `app/build.gradle.kts`:

```kotlin
implementation("com.microsoft.identity.client:msal:8.5.0")
```

The published POM for this version declares the following dependencies:

```text
com.microsoft.identity.client:msal:8.5.0
└─ com.microsoft.identity:common:24.7.0
   ├─ androidx.credentials:credentials-play-services-auth:1.2.2
   ├─ com.google.android.gms:play-services-fido:20.1.0
   └─ com.google.android.libraries.identity.googleid:googleid:1.1.0
```

Sources: [MSAL POM](https://repo.maven.apache.org/maven2/com/microsoft/identity/client/msal/8.5.0/msal-8.5.0.pom) and [common POM](https://repo.maven.apache.org/maven2/com/microsoft/identity/common/24.7.0/common-24.7.0.pom). These are the published declarations; project constraints may change the final versions, so use Gradle's resolved dependency graph as the reference.

Choosing browser sign-in does not automatically remove these dependencies. A simple `exclude` is not a verified solution. If you remove dependencies, also inspect code references, the merged Manifest and runtime paths to avoid missing-class crashes or losing conditional access capabilities.

### Configure MSAL and its callback

Create `app/src/main/res/raw/msal_config.json`. Replace the placeholders with values from your portal configuration, and ensure the account type matches the registration:

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

`MULTIPLE` matches the player's ability to save multiple cloud drive connections. This configuration starts with browser authorization and no broker integration. If you need an enterprise broker, configure and verify it separately using Microsoft's documentation. See [MSAL Android configuration](https://learn.microsoft.com/en-us/entra/msal/android/msal-configuration) for the available settings.

Inside `<application>` in [AndroidManifest.xml](../app/src/main/AndroidManifest.xml), add the `com.microsoft.identity.client.BrowserTabActivity` required by the portal, set `android:exported="true"`, and configure an intent filter with `VIEW`, `DEFAULT`, `BROWSABLE` and the exact scheme, host and path. For an `msauth` URI, the host is the actual package name and the path contains the signature hash. Follow the portal and official tutorial for the encoding of the JSON URI and Manifest path. Do not use wildcard callbacks or make the existing `OneDriveAuthorizationActivity` an exported entry point callable by arbitrary apps.

### Connect the existing authorization screen

Update [OneDriveAuthorization.kt](../app/src/main/java/cn/coldlake/stellar/opensource/medialibrary/onedrive/OneDriveAuthorization.kt):

1. Initialize an application-level MSAL instance with `PublicClientApplication.createMultipleAccountPublicClientApplication` and `R.raw.msal_config`. Avoid creating an instance for every recomposition or request.
2. After the user selects Connect, request `https://graph.microsoft.com/Files.Read` through `AcquireTokenParameters`. Handle success, errors and cancellation separately in `AuthenticationCallback`. Use `IAuthenticationResult.accessToken`, not an ID token.
3. On success, retain the existing validation: `OneDriveClient({ suppliedToken }).use { it.account() }` returns `(driveId, displayLabel)`. When reauthorizing an existing connection, verify that the supplied `account_id` matches the returned `driveId` before updating the binding.
4. Save the mapping `driveId → MSAL account.id + authority`. **In this project, `account_id` is the drive ID, not an email address or MSAL account ID.** Do not substitute `account.username` or the first account in the cache.
5. Preserve the Activity result contract: return `RESULT_OK` with Intent extras `account_id=driveId` and `account=displayLabel` on success, or `RESULT_CANCELED` on cancellation. This connects to the existing [OneDriveServerForm.kt](../app/src/main/java/cn/coldlake/stellar/opensource/medialibrary/onedrive/OneDriveServerForm.kt).

See [Microsoft's token acquisition documentation](https://learn.microsoft.com/en-us/entra/msal/android/acquire-tokens) for interactive and silent APIs. MSAL manages refresh credentials in its cache; saving the first access token is not a complete MSAL integration.

### Implement silent token renewal

`OneDriveClient` already accepts `suspend (forceRefresh: Boolean) -> String` and retries with `true` after the first 401 response. The current `OneDriveAuthorization.token(..., force)` only reads a manually supplied token from local storage; it does not renew tokens. Replace it with silent token acquisition for the account selected through the saved mapping.

The following is the core authorization adapter code. `msal`, `account`, `authority` and `forceRefresh` come from the initialized instance, the validated binding and the caller's arguments:

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

Before calling it, confirm that the account is still in the MSAL cache. Map `MsalUiRequiredException` to the project's reauthorization state so that the user can continue in the UI; do not launch a sign-in screen directly from background playback or scanning tasks. Coalesce concurrent refreshes per account and preserve coroutine cancellation. See [Microsoft's account documentation](https://learn.microsoft.com/en-us/entra/msal/android/accounts-overview) for account models and cache rules.

When removing a connection, clear its mapping and old manually supplied token. If several connections share an MSAL account, deleting one connection must not remove cached account data still required by the others.

## Integrate Google Drive

### Register a Google application and permissions

Enable the Google Drive API in your own Google Cloud project, and configure the OAuth consent screen, support email, privacy policy and test users. If using the native Android authorization component, create an Android OAuth client that matches the actual package name and signing certificate SHA-1.

The player needs to browse existing files and read media content. Its current scope is:

```text
https://www.googleapis.com/auth/drive.readonly
```

This is a restricted scope; public use requires the verification applicable under Google's rules. `drive.file` only covers the files authorized for that scope and cannot directly replace it while preserving the current ability to browse the entire drive. Metadata-only permissions cannot read video content. See the [Google Drive permissions guide](https://developers.google.com/workspace/drive/api/guides/api-specific-auth) for scope and verification requirements.

### Add the open-source API client

To replace some manually implemented API requests with the official Java client, add these pinned versions to `dependencies`:

```kotlin
implementation("com.google.apis:google-api-services-drive:v3-rev20261011-2.0.0")
implementation("com.google.api-client:google-api-client:2.9.2")
implementation("com.google.http-client:google-http-client-gson:2.2.0")
```

Check versions in the [Drive Maven metadata](https://repo.maven.apache.org/maven2/com/google/apis/google-api-services-drive/maven-metadata.xml), [API Client metadata](https://repo.maven.apache.org/maven2/com/google/api-client/google-api-client/maven-metadata.xml) and [Gson adapter metadata](https://repo.maven.apache.org/maven2/com/google/http-client/google-http-client-gson/maven-metadata.xml). See the [Google Drive client installation guide](https://developers.google.com/workspace/drive/api/guides/downloads) for setup.

These dependencies provide API access. They do not automatically implement Android sign-in or make the first access token valid indefinitely. Do not add `play-services-auth` or the components used by older `GoogleAccountCredential` sign-in examples merely to obtain the `Drive` class.

The following function queries the current account using an access token already obtained through an authorized flow. It matches the player's existing use of `about.user.emailAddress` to identify accounts:

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

This snippet only demonstrates an account query; it does not replace the project's complete HTTP layer. Other operations still need cancellation, rate limiting, pagination, error mapping and token expiry handling. Preserve the media Range requests, response cleanup and download cancellation in [DriveClient.kt](../app/src/main/java/cn/coldlake/stellar/opensource/medialibrary/drive/DriveClient.kt). Avoid loading an entire video into memory.

### Use Google's official Android authorization component

If choosing the official Android authorization flow, also add the following in your integration branch:

```kotlin
implementation("com.google.android.gms:play-services-auth:22.0.0")
```

This is a proprietary dependency separate from the open-source Drive API client, and the distribution boundaries described above apply. It is not required when integrating only the official API client.

Following the [official Android authorization documentation](https://developer.android.com/identity/authorization), implement this flow in [DriveAuthorization.kt](../app/src/main/java/cn/coldlake/stellar/opensource/medialibrary/drive/DriveAuthorization.kt):

1. Request the Drive scope using `AuthorizationRequest.builder().setRequestedScopes(...)` and start authorization with `Identity.getAuthorizationClient(activity).authorize(request)`. The scope entry is `Scope("https://www.googleapis.com/auth/drive.readonly")`.
2. If the result `hasResolution()`, launch the returned PendingIntent using `ActivityResultContracts.StartIntentSenderForResult`. In the callback, call `getAuthorizationResultFromIntent` and handle cancellation and errors.
3. Check that the scopes actually granted meet the current operation's requirements, then obtain `AuthorizationResult.accessToken` and verify it is not empty. Users may grant only some scopes, and a Google sign-in ID token cannot be used to access Drive.
4. Verify the account with the existing `DriveClient({ accessToken }).use { it.account() }` or the account query function above. When reauthorizing, check that it matches the existing `account` in the Intent before replacing the binding. On success, return `RESULT_OK` and `account=email` for [DriveServerForm.kt](../app/src/main/java/cn/coldlake/stellar/opensource/medialibrary/drive/DriveServerForm.kt).
5. Preserve the account binding when acquiring subsequent tokens. Do not use the device's default account to make requests for a different connection. If renewed user consent is needed, handle it only through the foreground authorization UI.

The current `DriveClient` calls `invalidate(token)` after the first 401 response, then requests `tokenProvider()` again. The SDK adapter should clear the relevant expired token from the cache before attempting acquisition again; the official authorization component provides `clearToken` for this purpose. Clearing only the project's SharedPreferences can leave the SDK returning the same invalid token. Do not retry indefinitely. See [AuthorizationClient](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationClient).

Provide a clear UI action for revoking Google account authorization. Use the official `revokeAccess` API and clear the relevant local bindings. Revocation may affect several connections under the same app and account, so explain that impact in the UI. Do not revoke all authorization as a routine response to a 401 error.

To keep a distribution free of proprietary Google SDKs, retain the current token entry screen or design a separate authorization approach that meets Google's platform rules. Do not copy desktop OAuth tutorials and assume localhost callbacks or arbitrary custom URI schemes work on Android; platform restrictions apply. See [Google's OAuth documentation for native apps](https://developers.google.com/identity/protocols/oauth2/native-app).

## Connect to the existing browsing and playback code

| Entry point | Current contract | Behavior to preserve |
| --- | --- | --- |
| `DriveAuthorization.token` / `invalidate` | Retrieve tokens by email; invalidate and retry once after a 401 | Every token acquisition targets the same account; authorization failures produce a recoverable state |
| `OneDriveAuthorization.token` | Retrieve tokens by the current drive ID, accepting a `force` argument | Map to the correct MSAL account; `force=true` actually requests a refresh |
| Both `AuthorizationActivity` classes | Return the bound identity through an Activity result | Cancellation leaves existing connections intact; reauthorization does not switch their accounts |
| [CloudProvider.kt](../app/src/main/java/cn/coldlake/stellar/opensource/medialibrary/cloud/CloudProvider.kt) | Paginated browsing, incremental synchronization and stable file identifiers | Map SDK metadata requests to the existing models, preserving cursor and synchronization semantics |
| [CloudMediaClient.kt](../app/src/main/java/cn/coldlake/stellar/opensource/medialibrary/cloud/CloudMediaClient.kt) | Read media on demand with cancellation | Preserve Range, HEAD, streaming responses and connection cleanup |

OneDrive's `@microsoft.graph.downloadUrl` is a preauthorized URL. Do not attach a Graph Bearer token to requests to its host. Preserve the distinction between download URLs and Graph API requests in [OneDriveClient.kt](../app/src/main/java/cn/coldlake/stellar/opensource/medialibrary/onedrive/OneDriveClient.kt).

Update your [privacy information](../PRIVACY.md) to reflect the sign-in, token caching and data processing introduced by the SDKs. The existing description of manually supplied tokens no longer describes a version with automatic sign-in.

## Check the build and release contents

After implementing the changes in your branch, run these commands from the repository root:

```sh
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
./gradlew :app:dependencies --configuration debugRuntimeClasspath
./gradlew :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency play-services
./gradlew :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency googleid
./gradlew :app:assembleDebug :app:assembleRelease
```

Inspect the license, original notices and source location for every packaged dependency. Checking only a top-level SDK's MIT or Apache label does not cover the whole dependency graph. Not calling a feature in code does not mean its component has been removed from the APK.

After reviewing dependencies, update the in-app inventory:

```sh
./gradlew -I tools/about-dependencies.init.gradle :app:exportAboutDependencies
python3 tools/generate_about_licenses.py
```

The current [license generator](../tools/generate_about_licenses.py) rejects the proprietary Google components listed above. Do not remove the checks just to make generation succeed and then claim compliance. When adding MSAL, also obtain the additional notices referenced by the script, such as `msal-MIT.txt` and `microsoft-common-MIT.txt`, from the exact upstream versions. If the script fails, inspect the specific cause and relevant files first.

The existing export task covers only Release. If you also distribute Debug or add other flavors, extend inventory generation to cover each runtime classpath. Each release package's inventory must match that package's dependencies.

Redistribution also requires the complete corresponding source, build scripts and applicable notices for the exact version. The current CI uses `git archive`, which includes only committed files and does not automatically collect third-party source from the Gradle cache. Supply the additional sources or a way to obtain them that meets the license requirements, and check that the APK, source bundle, notices and versions correspond. See the [binary redistribution requirements](../THIRD_PARTY_NOTICES.md#redistributing-binaries).

### Account and playback verification

Using your own registered test applications and accounts, verify at least the following:

- Successful first sign-in, denied authorization, cancellation and network interruption; cancellation leaves existing connections intact.
- Separate connections for two accounts; reauthorization with the wrong account is blocked, and the correct bindings survive a process restart.
- Access token expiry and the first 401 response; successful refresh restores access. Revoked authorization prompts the user to reconnect without endless retries or background sign-in dialogs.
- Clear failure states when Google Play services or a browser is unavailable, or an organization policy denies access.
- Multiple directory pages, library scanning, playback, seeking, subtitle reads and request cancellation when playback stops.
- Correct package names, signatures and callbacks for both Debug and Release. If the store re-signs the app, test the store-installed build.
- No access tokens, refresh tokens, preauthorized download URLs or private signing material in logs, crash reports, source code or release attachments.

A successful build only verifies compilation. The actual authorization and playback behavior still needs testing on devices.
