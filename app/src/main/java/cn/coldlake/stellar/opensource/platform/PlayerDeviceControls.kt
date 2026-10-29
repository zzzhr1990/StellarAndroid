package cn.coldlake.stellar.opensource.platform

import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import cn.coldlake.stellar.opensource.presentation.AppRoute
import cn.coldlake.stellar.opensource.presentation.PlaybackDeviceControls
import cn.coldlake.stellar.opensource.presentation.PlayerViewModel
import com.stellar.player.ui.GestureKind
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Window brightness never writes global settings or modifies the video Surface. */
internal class PlayerDeviceControls(private val activity: ComponentActivity, private val vm: PlayerViewModel) :
    PlaybackDeviceControls, DefaultLifecycleObserver {
    private val audio = activity.getSystemService(AudioManager::class.java)
    private var started = false
    private var playingPage = false
    private var pip = false
    private var originalBrightness: Float? = null
    private var gestureBrightness = -1f
    private var gestureVolume = 0
    private var gestureGainDb = 0f
    private var gestureSession = 0L
    private var lastVolume = -1
    private val job = activity.lifecycleScope.launch {
        combine(vm.navigation.routeChanges, vm.snapshot) { route, s -> route == AppRoute.PLAYER && !s.simulated && !s.remotePlayback && s.media != null }
            .distinctUntilChanged().collect { playingPage = it; if (!it) restoreWindow() }
    }
    init {
        activity.lifecycle.addObserver(this)
        vm.interactions.attachDeviceControls(this)
    }
    override fun onStart(owner: LifecycleOwner) { started = true }
    override fun onStop(owner: LifecycleOwner) { started = false; vm.interactions.interrupt(); restoreWindow() }
    fun pictureInPicture(active: Boolean) {
        pip = active
        if (active) { vm.interactions.interrupt(); restoreWindow() }
    }
    override fun begin(kind: GestureKind): Float? {
        if (!started || !playingPage || pip) return null
        return when (kind) {
            GestureKind.BRIGHTNESS -> {
                gestureBrightness = activity.window.attributes.screenBrightness
                if (originalBrightness == null) originalBrightness = gestureBrightness
                (gestureBrightness.takeIf { it >= 0f }
                    ?: (Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f))
                    .coerceIn(0f, 1f)
            }
            GestureKind.VOLUME -> {
                if (audio.isVolumeFixed) return null
                gestureVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                lastVolume = gestureVolume
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                gestureSession = vm.snapshot.value.sessionId
                // A hardware/system volume change below maximum leaves the boost range.
                if (gestureVolume < max && vm.snapshot.value.audioGainDb != 0f) vm.setAudioGain(gestureSession, 0f)
                gestureGainDb = vm.snapshot.value.audioGainDb
                gestureVolume.toFloat() / max + if (gestureVolume == max) gestureGainDb / 9f else 0f
            }
            else -> null
        }
    }
    override fun change(kind: GestureKind, value: Float): Float = when (kind) {
        GestureKind.BRIGHTNESS -> {
            val actual = value.coerceIn(0f, 1f)
            setBrightness(actual)
            actual
        }
        GestureKind.VOLUME -> {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val min = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0
            val supportsGain = vm.snapshot.value.capabilities.canSetAudioGain
            val target = (value * max + .0001f).toInt().coerceIn(min, max * if (supportsGain) 2 else 1)
            val streamTarget = target.coerceAtMost(max)
            if (streamTarget != lastVolume) {
                // No second system slider or feedback beep over the original-style player HUD.
                runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, streamTarget, 0) }
                lastVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            val gainDb = if (lastVolume == max) (target - max).coerceAtLeast(0) * 9f / max else 0f
            if (supportsGain && gainDb != vm.snapshot.value.audioGainDb) vm.setAudioGain(gestureSession, gainDb)
            lastVolume.toFloat() / max + if (lastVolume == max) vm.snapshot.value.audioGainDb / 9f else 0f
        }
        else -> value
    }
    override fun finish(kind: GestureKind, commit: Boolean) {
        if (commit) return
        when (kind) {
            GestureKind.BRIGHTNESS -> setBrightness(gestureBrightness)
            GestureKind.VOLUME -> {
                if (gestureSession != vm.snapshot.value.sessionId) return
                if (vm.snapshot.value.capabilities.canSetAudioGain) vm.setAudioGain(gestureSession, gestureGainDb)
                runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, gestureVolume, 0) }
                lastVolume = gestureVolume
            }
            else -> Unit
        }
    }
    private fun setBrightness(value: Float) {
        val attributes = activity.window.attributes
        if (attributes.screenBrightness != value) { attributes.screenBrightness = value; activity.window.attributes = attributes }
    }
    private fun restoreWindow() { originalBrightness?.let(::setBrightness); originalBrightness = null }
    fun close() {
        vm.interactions.attachDeviceControls(null)
        restoreWindow()
        job.cancel()
        activity.lifecycle.removeObserver(this)
    }
}
