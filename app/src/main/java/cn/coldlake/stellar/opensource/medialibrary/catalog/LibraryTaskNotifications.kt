package cn.coldlake.stellar.opensource.medialibrary.catalog

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** Ask in response to a library task, so its background controls can appear in the drawer. */
@Composable
internal fun rememberLibraryTaskLauncher(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val action = pending
        pending = null
        // Notification denial does not revoke the user's explicit scan/identify request.
        // The in-app controls and Android's foreground-service task manager remain available.
        action?.invoke()
    }
    DisposableEffect(Unit) { onDispose { pending = null } }
    return { action ->
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (pending == null) {
                pending = action
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else action()
    }
}
