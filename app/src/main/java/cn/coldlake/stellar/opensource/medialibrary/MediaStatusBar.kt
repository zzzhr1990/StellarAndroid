package cn.coldlake.stellar.opensource.medialibrary

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.stellar.player.ui.LocalPageActive

internal data class MediaStatusBarStyle(val owner: Any, val pageKey: Any, val darkIcons: Boolean)

internal val LocalMediaStatusBar = staticCompositionLocalOf<MutableState<MediaStatusBarStyle?>?> { null }

/** Only the visible immersive entry requests a style. The Activity also matches its
 * navigation key, so an outgoing/retained page cannot recolour another destination. */
@Composable internal fun MediaStatusBar(pageKey: Any, overArtwork: Boolean, bodyColor: Color) {
    val request = LocalMediaStatusBar.current
    val owner = remember { Any() }
    val active = LocalPageActive.current
    // A dark theme's artwork-derived container can still be fairly bright. Compare
    // the two actual foreground contrasts against the resolved opaque background;
    // never sample the moving bitmap or infer its brightness from the app mode.
    val luminance = bodyColor.luminance()
    val darkIcons = !overArtwork && (luminance + .05f) / .05f >= 1.05f / (luminance + .05f)
    DisposableEffect(request, active, pageKey) {
        onDispose { if (request?.value?.owner === owner) request.value = null }
    }
    SideEffect { if (active) request?.value = MediaStatusBarStyle(owner, pageKey, darkIcons) }
}
