package cn.coldlake.stellar.opensource.platform

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.stellar.player.contract.*
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.presentation.AppRoute
import cn.coldlake.stellar.opensource.presentation.PlayerViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.stellar.player.ui.R as PlayerR

/** Host policy only: simulated sessions and audio never acquire system PiP behavior. */
internal fun canUsePictureInPicture(snapshot: PlaybackSnapshot, route: AppRoute, enabled: Boolean): Boolean =
    enabled && route == AppRoute.PLAYER && !snapshot.simulated && snapshot.media?.kind == MediaKind.VIDEO &&
        snapshot.status in setOf(PlaybackStatus.PLAYING, PlaybackStatus.PAUSED)

internal fun pictureInPictureRatio(ratio: Float): Float =
    (if (ratio.isFinite() && ratio > 0) ratio else 16f / 9f).coerceIn(1f / 2.39f, 2.39f)

/** Keeps Android APIs and preferences out of the playback contract and MPV adapter. */
class PlayerPictureInPicture(private val activity: ComponentActivity, private val vm: PlayerViewModel) {
    private val preferences = activity.getSharedPreferences("presentation", Context.MODE_PRIVATE)
    val supported = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    var enabled by mutableStateOf(preferences.getBoolean("pictureInPicture", true))
        private set
    var active by mutableStateOf(activity.isInPictureInPictureMode)
        private set
    var transitioning by mutableStateOf(false)
        private set
    val hideControls get() = active || transitioning
    private var sourceRect = Rect()
    private val actionName = "${activity.packageName}.PIP_PLAYBACK"
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val snapshot = vm.snapshot.value
            if (intent.action != actionName || !active || !eligible() ||
                intent.getLongExtra("session", 0) != snapshot.sessionId) return
            vm.command(if (intent.getBooleanExtra("play", false)) PlaybackCommand.Play(snapshot.sessionId)
                else PlaybackCommand.Pause(snapshot.sessionId))
        }
    }

    init {
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(actionName).apply { addDataScheme("stellar-pip") }, ContextCompat.RECEIVER_NOT_EXPORTED)
        activity.lifecycleScope.launch {
            combine(vm.snapshot, vm.navigation.routeChanges) { snapshot, route ->
                // Position changes must not rebuild PendingIntents or call the system every 100 ms.
                listOf(snapshot.sessionId, snapshot.simulated, snapshot.media?.kind, snapshot.media?.aspectRatio,
                    snapshot.status, snapshot.remotePlayback, route)
            }.distinctUntilChanged().collect { refresh() }
        }
    }

    fun updateEnabled(value: Boolean) {
        preferences.edit { putBoolean("pictureInPicture", value) }
        enabled = value
        refresh()
    }

    private fun eligible() = !vm.isCasting && supported && canUsePictureInPicture(vm.snapshot.value, vm.navigation.current, enabled)
    private var autoEnterSuspended = false
    internal fun suspendAutoEnter(value: Boolean) { autoEnterSuspended = value; refresh() }
    private fun autoEnter() = !autoEnterSuspended && eligible() && vm.snapshot.value.status == PlaybackStatus.PLAYING && !activity.isFinishing

    fun sourceBounds(rect: Rect) {
        if (rect.isEmpty || rect == sourceRect) return
        sourceRect = Rect(rect)
        refresh()
    }

    private fun params(): PictureInPictureParams {
        val snapshot = vm.snapshot.value
        val ratio = pictureInPictureRatio(snapshot.media?.aspectRatio ?: 16f / 9f)
        // Clamp the rational after quantization too: flooring a very tall video's
        // ratio must never put it just below Android's exact 100:239 lower limit.
        val aspect = Rational((ratio * 10000).toInt(), 10000).coerceIn(Rational(100, 239), Rational(239, 100))
        val builder = PictureInPictureParams.Builder().setAspectRatio(aspect)
        if (!sourceRect.isEmpty) builder.setSourceRectHint(sourceRect)
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(autoEnter()).setSeamlessResizeEnabled(true)
        val actions = if (eligible()) {
            val playing = snapshot.status == PlaybackStatus.PLAYING
            val label = activity.getString(if (playing) PlayerR.string.pause else PlayerR.string.play)
            val intent = Intent(actionName).setPackage(activity.packageName)
                .putExtra("session", snapshot.sessionId).putExtra("play", !playing)
            // Different identities for each session/action prevent a stale menu from controlling a new video.
            intent.data = "stellar-pip://playback/${snapshot.sessionId}/$playing".toUri()
            val pending = PendingIntent.getBroadcast(activity, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            listOf(RemoteAction(Icon.createWithResource(activity,
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play), label, label, pending))
        } else emptyList()
        return builder.setActions(actions).build()
    }

    fun refresh() {
        if (supported && !activity.isDestroyed) runCatching { activity.setPictureInPictureParams(params()) }
    }

    fun enter(showFailure: Boolean = true): Boolean {
        if (active) return true
        if (!eligible()) {
            if (showFailure) Toast.makeText(activity,
                if (!enabled) R.string.pip_disabled else R.string.pip_unavailable, Toast.LENGTH_SHORT).show()
            return false
        }
        vm.configurationChanged() // Close dialogs, cancel seek previews and restore a held speed.
        val entered = runCatching { activity.enterPictureInPictureMode(params()) }.getOrDefault(false)
        if (!entered && showFailure) Toast.makeText(activity, R.string.pip_unavailable, Toast.LENGTH_SHORT).show()
        return entered
    }

    fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT < 31 && autoEnter()) enter(showFailure = false)
    }

    fun onTransitionStarted() {
        transitioning = true
        vm.configurationChanged()
    }

    fun onModeChanged(value: Boolean) {
        active = value
        transitioning = false
        vm.configurationChanged()
        refresh()
    }

    fun onResume() { transitioning = false; refresh() }
    fun close() { activity.unregisterReceiver(receiver) }
}
