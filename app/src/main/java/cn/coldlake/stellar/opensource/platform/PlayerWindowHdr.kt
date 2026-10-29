package cn.coldlake.stellar.opensource.platform

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
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

internal enum class WindowHdrSupport { SUPPORTED, ANDROID_VERSION, DISPLAY_UNSUPPORTED }

internal fun windowHdrSupport(api: Int, hasHdrDisplay: Boolean): WindowHdrSupport = when {
    api < 34 -> WindowHdrSupport.ANDROID_VERSION
    !hasHdrDisplay -> WindowHdrSupport.DISPLAY_UNSUPPORTED
    else -> WindowHdrSupport.SUPPORTED
}

internal fun hasWindowHdrVideo(snapshot: PlaybackSnapshot, route: AppRoute): Boolean =
    route == AppRoute.PLAYER && !snapshot.simulated && !snapshot.remotePlayback && snapshot.media?.kind == MediaKind.VIDEO &&
        snapshot.status != PlaybackStatus.IDLE && snapshot.status != PlaybackStatus.ERROR

/** Window-only compatibility request. MPV's independent Surface and frame colors are untouched. */
internal class PlayerWindowHdr(private val activity: ComponentActivity, private val vm: PlayerViewModel) :
    DefaultLifecycleObserver, DisplayManager.DisplayListener {
    private val preferences = activity.getSharedPreferences("video_display_v1", Context.MODE_PRIVATE)
    private val displays = activity.getSystemService(DisplayManager::class.java)
    var enabled by mutableStateOf(preferences.getBoolean("force_hdr", false))
        private set
    var support by mutableStateOf(readSupport())
        private set
    private var visible = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    private var previousColorMode: Int? = null
    private var closed = false
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "force_hdr" || key == null) {
            enabled = preferences.getBoolean("force_hdr", false)
            refresh()
        }
    }
    private val observation = activity.lifecycleScope.launch {
        combine(vm.snapshot, vm.navigation.routeChanges) { snapshot, route -> hasWindowHdrVideo(snapshot, route) }
            .distinctUntilChanged().collect { refresh() }
    }

    init {
        // A historical settings_layout_preview_v1/exo_hdr value is deliberately not migrated.
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        displays.registerDisplayListener(this, Handler(Looper.getMainLooper()))
        activity.lifecycle.addObserver(this)
    }

    fun updateEnabled(value: Boolean) {
        // Allow turning an existing preference off even after the display becomes unsupported.
        if (value && readSupport() != WindowHdrSupport.SUPPORTED) return
        preferences.edit { putBoolean("force_hdr", value) }
        enabled = value
        refresh()
    }

    private fun readSupport(): WindowHdrSupport {
        val hdrDisplay = Build.VERSION.SDK_INT >= 34 && runCatching {
            activity.display?.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
        }.getOrDefault(false)
        return windowHdrSupport(Build.VERSION.SDK_INT, hdrDisplay)
    }

    fun refresh() {
        if (closed) return
        support = readSupport()
        val request = enabled && visible && support == WindowHdrSupport.SUPPORTED &&
            hasWindowHdrVideo(vm.snapshot.value, vm.navigation.current)
        try {
            if (request) {
                if (previousColorMode == null) previousColorMode = activity.window.colorMode
                if (activity.window.colorMode != ActivityInfo.COLOR_MODE_HDR) {
                    activity.window.colorMode = ActivityInfo.COLOR_MODE_HDR
                }
            } else restore()
        } catch (error: RuntimeException) {
            Log.w("PlayerWindowHdr", "Window HDR request failed", error)
        }
    }

    private fun restore() {
        val previous = previousColorMode ?: return
        if (activity.window.colorMode != previous) activity.window.colorMode = previous
        previousColorMode = null
    }

    override fun onStart(owner: LifecycleOwner) { visible = true; refresh() }
    override fun onStop(owner: LifecycleOwner) { visible = false; refresh() }
    override fun onDestroy(owner: LifecycleOwner) { close() }
    override fun onDisplayChanged(displayId: Int) { refresh() }
    override fun onDisplayAdded(displayId: Int) { refresh() }
    override fun onDisplayRemoved(displayId: Int) { refresh() }

    private fun close() {
        if (closed) return
        closed = true
        observation.cancel()
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        displays.unregisterDisplayListener(this)
        activity.lifecycle.removeObserver(this)
        runCatching { restore() }.onFailure { Log.w("PlayerWindowHdr", "Window HDR restore failed", it) }
    }
}
