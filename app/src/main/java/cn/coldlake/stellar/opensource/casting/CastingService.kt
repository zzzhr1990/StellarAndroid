package cn.coldlake.stellar.opensource.casting

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import com.stellar.player.contract.PlaybackCommand
import com.stellar.player.contract.PlaybackSnapshot
import com.stellar.player.contract.PlaybackStatus
import cn.coldlake.stellar.opensource.MainActivity
import cn.coldlake.stellar.opensource.R
import com.stellar.player.ui.R as PlayerR
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** One remote session backs screen, system transport controls and foreground relay. */
class CastingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var owner: CastingController? = null
    private var ownerToken: Long? = null
    private val activeOwner get() = owner?.takeIf { it.activeToken == ownerToken }
    private var updates: Job? = null
    private lateinit var session: MediaSession
    private var volume: VolumeProvider? = null
    private var volumeAdjustable = false
    private var metadata: List<Any?>? = null
    private var notificationKey: List<Any?>? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        running = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.casting_title), NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "StellarCasting").apply {
            setSessionActivity(openIntent())
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { activeOwner?.let { it.dispatch(PlaybackCommand.Play(it.snapshots.value.sessionId)) } }
                override fun onPause() { activeOwner?.let { it.dispatch(PlaybackCommand.Pause(it.snapshots.value.sessionId)) } }
                override fun onSeekTo(pos: Long) { activeOwner?.seekTo(pos) }
                override fun onFastForward() { activeOwner?.seekBy(15000) }
                override fun onRewind() { activeOwner?.seekBy(-15000) }
                override fun onStop() { activeOwner?.cancelSession() }
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    when (action) { REWIND -> onRewind(); FORWARD -> onFastForward(); STOP -> onStop() }
                }
            })
        }
        owner = controller
        ownerToken = owner?.activeToken
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every foreground start must be promoted, including a request cancelled before delivery.
        if (intent?.getBooleanExtra(START_REQUEST, false) == true) pendingStarts = (pendingStarts - 1).coerceAtLeast(0)
        startForeground(ID, notification(controller?.snapshots?.value ?: PlaybackSnapshot(), controller?.state?.value ?: CastState()))
        val current = controller
        if (current == null) { finishIfIdle(); return START_NOT_STICKY }
        if (owner !== current || ownerToken != current.activeToken || updates == null) {
            updates?.cancel(); owner = current; ownerToken = current.activeToken
            session.isActive = true
            updates = scope.launch {
                combine(current.snapshots, current.state) { snapshot, state -> snapshot to state }.collect { (snapshot, state) ->
                    if (activeOwner == null || current.activeToken == null) return@collect
                    updateSession(snapshot, state)
                    val key = listOf(snapshot.media?.title, snapshot.status, snapshot.canSeek, state.device, state.connecting,
                        state.subtitleBusy, state.canPause, current.activeToken)
                    if (notificationKey != key) {
                        notificationKey = key
                        postNotification(snapshot, state)
                    }
                }
            }
        }
        // Pending actions from an ended session cannot control its replacement.
        if (intent?.getLongExtra(TOKEN, -1) == current.activeToken) when (intent?.action) {
            STOP -> current.cancelSession()
            TOGGLE -> current.togglePlayback()
            REWIND -> current.seekBy(-15000)
            FORWARD -> current.seekBy(15000)
        }
        return START_NOT_STICKY
    }
    private fun updateSession(snapshot: PlaybackSnapshot, state: CastState) {
        val enabled = !state.connecting && !state.subtitleBusy
        val actions = PlaybackState.ACTION_STOP or (if (enabled) PlaybackState.ACTION_PLAY else 0L) or
            (if (enabled && state.canPause) PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE else 0L) or
            (if (enabled && snapshot.canSeek) PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD else 0L)
        val status = when {
            !enabled || snapshot.status == PlaybackStatus.BUFFERING -> PlaybackState.STATE_BUFFERING
            snapshot.status == PlaybackStatus.PLAYING -> PlaybackState.STATE_PLAYING
            snapshot.status == PlaybackStatus.ENDED -> PlaybackState.STATE_STOPPED
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(PlaybackState.Builder().setActions(actions)
            .setState(status, snapshot.positionMs, if (status == PlaybackState.STATE_PLAYING) 1f else 0f).apply {
                // Android 13+ derives its media buttons from PlaybackState, not Notification.actions.
                if (enabled && snapshot.canSeek) {
                    addCustomAction(REWIND, getString(R.string.cast_rewind), PlayerR.drawable.hills_fast_rewind)
                    addCustomAction(FORWARD, getString(R.string.cast_forward), PlayerR.drawable.hills_fast_forward)
                }
                addCustomAction(STOP, getString(R.string.casting_stop), PlayerR.drawable.hills_close)
            }.build())
        val title = snapshot.media?.title ?: getString(R.string.casting_title)
        val key = listOf(title, snapshot.durationMs, state.device?.name)
        if (metadata != key) {
            metadata = key
            session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.device?.name.orEmpty())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, snapshot.durationMs ?: 0).build())
        }
        val adjustable = state.volume != null
        if (volume == null || adjustable != volumeAdjustable) {
            volumeAdjustable = adjustable
            volume = object : VolumeProvider(if (adjustable) VOLUME_CONTROL_ABSOLUTE else VOLUME_CONTROL_FIXED, 100,
                ((state.volume ?: 0f) * 100).toInt()) {
                override fun onSetVolumeTo(value: Int) { scope.launch { activeOwner?.volume(value.coerceIn(0, 100) / 100f) } }
                override fun onAdjustVolume(direction: Int) {
                    scope.launch { activeOwner?.let { it.volume(((it.state.value.volume ?: 0f) + direction.coerceIn(-1, 1) * .05f).coerceIn(0f, 1f)) } }
                }
            }.also(session::setPlaybackToRemote)
        }
        volume?.setCurrentVolume(((state.volume ?: 0f) * 100).toInt().coerceIn(0, 100))
    }
    // MediaStyle notifications with an attached MediaSession are exempt from POST_NOTIFICATIONS.
    // https://developer.android.com/develop/ui/compose/notifications/notification-permission#media-sessions
    @android.annotation.SuppressLint("NotificationPermission")
    private fun postNotification(snapshot: PlaybackSnapshot, state: CastState) {
        getSystemService(NotificationManager::class.java).notify(ID, notification(snapshot, state))
    }
    private fun openIntent(): PendingIntent = PendingIntent.getActivity(this, 714,
        Intent(this, MainActivity::class.java).setAction(OPEN).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun action(action: String, icon: Int, label: Int): Notification.Action {
        val intent = Intent(this, CastingService::class.java).setAction(action).putExtra(TOKEN, owner?.activeToken ?: -1)
        return Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), getString(label), PendingIntent.getService(this, action.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
    }
    private fun notification(snapshot: PlaybackSnapshot, state: CastState): Notification {
        val actions = mutableListOf<Notification.Action>()
        val enabled = !state.connecting && !state.subtitleBusy
        if (enabled && snapshot.canSeek) actions += action(REWIND, PlayerR.drawable.hills_fast_rewind, R.string.cast_rewind)
        val playing = snapshot.status == PlaybackStatus.PLAYING
        val playIndex = actions.size
        if (enabled && (!playing || state.canPause)) actions += action(TOGGLE,
            if (playing) PlayerR.drawable.hills_pause else PlayerR.drawable.hills_play_arrow,
            if (playing) R.string.cast_pause else R.string.cast_play)
        val compact = mutableListOf<Int>()
        if (actions.size > playIndex) compact += playIndex
        if (enabled && snapshot.canSeek) actions += action(FORWARD, PlayerR.drawable.hills_fast_forward, R.string.cast_forward)
        compact += actions.size
        actions += action(STOP, PlayerR.drawable.hills_close, R.string.casting_stop)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stellar_launcher_monochrome)
            .setContentTitle(snapshot.media?.title ?: getString(R.string.casting_title))
            .setContentText(state.device?.let { getString(R.string.casting_on_device, it.name) } ?: getString(R.string.cast_connecting))
            .setContentIntent(openIntent()).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(*compact.toIntArray()))
            .apply { actions.forEach(::addAction) }.build()
    }
    override fun onTaskRemoved(rootIntent: Intent?) { controller?.cancelSession(); finishIfIdle() }
    private fun finishIfIdle() {
        if (controller == null && pendingStarts == 0) stopSelf()
    }
    override fun onDestroy() {
        if (running === this) running = null
        updates?.cancel(); scope.cancel(); session.release()
        if (controller === owner) activeOwner?.cancelSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(ID)
        owner = null
        super.onDestroy()
    }
    companion object {
        private const val ID = 714
        private const val CHANNEL = "casting"
        internal const val OPEN = "cn.coldlake.stellar.opensource.OPEN_CASTING"
        internal const val STOP = "cn.coldlake.stellar.opensource.STOP_CASTING"
        internal const val TOGGLE = "cn.coldlake.stellar.opensource.TOGGLE_CASTING"
        private const val REWIND = "cn.coldlake.stellar.opensource.REWIND_CASTING"
        private const val FORWARD = "cn.coldlake.stellar.opensource.FORWARD_CASTING"
        internal const val TOKEN = "castingSession"
        private const val START_REQUEST = "foregroundStart"
        // Accessed on the main thread, together with the controller and Service callbacks.
        private var controller: CastingController? = null
        private var running: CastingService? = null
        private var pendingStarts = 0
        internal val stopCasting: (() -> Unit)? get() = controller?.let { it::cancelSession }
        internal fun start(context: Context, owner: CastingController) {
            controller = owner
            pendingStarts++
            try {
                context.startForegroundService(Intent(context, CastingService::class.java).putExtra(START_REQUEST, true))
            } catch (e: Exception) {
                pendingStarts--
                controller = null
                running?.finishIfIdle()
                throw e
            }
        }
        internal fun finish(owner: CastingController) {
            if (controller !== owner) return
            controller = null
            // A queued start owns the obligation to promote first, then stop itself.
            running?.finishIfIdle()
        }
    }
}
