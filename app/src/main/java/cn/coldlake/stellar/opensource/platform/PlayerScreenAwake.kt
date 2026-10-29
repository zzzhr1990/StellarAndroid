package cn.coldlake.stellar.opensource.platform

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.stellar.player.contract.MediaKind
import com.stellar.player.contract.PlaybackSnapshot
import com.stellar.player.contract.PlaybackStatus
import cn.coldlake.stellar.opensource.presentation.AppRoute
import cn.coldlake.stellar.opensource.presentation.PlayerViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private fun hasActiveVideo(snapshot: PlaybackSnapshot, route: AppRoute): Boolean =
    route == AppRoute.PLAYER && !snapshot.simulated && !snapshot.remotePlayback && snapshot.media?.kind == MediaKind.VIDEO &&
        (snapshot.status == PlaybackStatus.PLAYING || snapshot.status == PlaybackStatus.BUFFERING)

/** Visible real video owns a window flag; no wake lock or device setting is needed. */
internal class PlayerScreenAwake(private val activity: ComponentActivity, private val vm: PlayerViewModel) :
    DefaultLifecycleObserver {
    private var visible = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    private var applied = false
    private val observation = activity.lifecycleScope.launch {
        combine(vm.snapshot, vm.navigation.routeChanges) { snapshot, route -> hasActiveVideo(snapshot, route) }
            .distinctUntilChanged().collect { refresh(it) }
    }

    init { activity.lifecycle.addObserver(this) }

    private fun refresh(videoActive: Boolean) {
        val keepAwake = visible && videoActive
        if (applied == keepAwake) return
        applied = keepAwake
        if (keepAwake) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStart(owner: LifecycleOwner) {
        visible = true
        refresh(hasActiveVideo(vm.snapshot.value, vm.navigation.current))
    }

    override fun onStop(owner: LifecycleOwner) {
        visible = false
        refresh(false)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        observation.cancel()
        visible = false
        refresh(false)
        activity.lifecycle.removeObserver(this)
    }
}
