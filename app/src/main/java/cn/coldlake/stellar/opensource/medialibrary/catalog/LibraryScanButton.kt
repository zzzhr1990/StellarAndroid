package cn.coldlake.stellar.opensource.medialibrary.catalog

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import cn.coldlake.stellar.opensource.R
import com.stellar.player.ui.LocalPageActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Only the task's running bit reaches this button; counts never invalidate the home header. */
@Composable
internal fun LibraryScanButton(onClick: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember(context) { LocalLibraryRuntime.get(context) }
    val running by remember(runtime) {
        combine(runtime.status, runtime.metadata.progress) { scan, metadata -> scan.running || metadata.running }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)
    val description = stringResource(if (running) R.string.scan_center_active else R.string.scan_center_inactive)
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).testTag("media-library-scan")
        .semantics { stateDescription = description },
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
        LibraryScanIcon(running, Modifier.size(24.dp), stringResource(R.string.scan_center_title))
    }
}

@Composable
internal fun LibraryScanIcon(running: Boolean, modifier: Modifier = Modifier, description: String? = null) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val animate = running && LocalPageActive.current && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val rotation = if (animate) rememberInfiniteTransition(label = "library-scan").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart),
        label = "library-scan-rotation",
    ) else null
    Icon(painterResource(R.drawable.ic_library_scan), description,
        modifier.graphicsLayer { rotationZ = rotation?.value ?: 0f },
        tint = if (running) MaterialTheme.colorScheme.primary else LocalContentColor.current)
}
