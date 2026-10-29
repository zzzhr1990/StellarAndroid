package cn.coldlake.stellar.opensource

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cn.coldlake.stellar.opensource.presentation.PlayerDiagnostics
import com.stellar.player.ui.PlaybackDiagnosticsOverlay
import java.util.Locale

@Composable
internal fun PlayerDiagnosticsHost(controller: PlayerDiagnostics, session: Long) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller, session, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { controller.collect(session) }
    }
    val view = LocalView.current
    val manager = LocalContext.current.getSystemService(DisplayManager::class.java)
    fun displayText(): String? = view.display?.mode?.let {
        String.format(Locale.ROOT, "%d × %d · %.0f Hz", it.physicalWidth, it.physicalHeight, it.refreshRate)
    }
    var screen by remember(view) { mutableStateOf(displayText()) }
    DisposableEffect(view, manager) {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) { screen = displayText() }
            override fun onDisplayRemoved(id: Int) { screen = displayText() }
            override fun onDisplayChanged(id: Int) { screen = displayText() }
        }
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        screen = displayText()
        onDispose { manager.unregisterDisplayListener(listener) }
    }
    PlaybackDiagnosticsOverlay(controller.values, screen, session = session)
}
