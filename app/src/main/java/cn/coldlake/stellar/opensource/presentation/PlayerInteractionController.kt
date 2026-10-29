package cn.coldlake.stellar.opensource.presentation

import com.stellar.player.contract.*
import com.stellar.player.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToLong

/** Activity-owned platform adapter, attached only for the lifetime of its host. */
interface PlaybackDeviceControls {
    fun begin(kind: GestureKind): Float?
    fun change(kind: GestureKind, value: Float): Float
    fun finish(kind: GestureKind, commit: Boolean)
}

/** Main-thread coordinator with an injected monotonic clock for deterministic policy tests. */
class PlayerInteractionController(
    private val snapshot: () -> PlaybackSnapshot,
    private val dispatch: (PlaybackCommand) -> CommandResult,
    private val now: () -> Long,
    private val requestTick: (Long) -> Unit = {},
) : PlayerInteractionActions {
    private val mutable = MutableStateFlow(PlayerInteractionState())
    val state = mutable.asStateFlow()
    private data class Active(val token: Long, val session: Long, val kind: GestureKind,
        val speed: Float, val brightness: Float, val volume: Float, val scale: Float, val device: Boolean, val maxLevel: Float)
    private var active: Active? = null
    private var sequence = 0L
    private var hideAt = now() + 4_000
    private var enabled = true
    private var lastStatus = snapshot().status
    private var deviceControls: PlaybackDeviceControls? = null
    private var levelHideAt = 0L
    private var fullscreen = false
    private var doubleTapSeek = false
    private var doubleTapPlay = true
    private var hideWhenPaused = true
    fun configureInteraction(options: PlaybackInteractionOptions) {
        if (state.value.options == options) return
        interrupt()
        mutable.value = state.value.copy(options = options)
        doubleTapPlay = options.doubleTapPlay
        doubleTapSeek = options.doubleTapSeek
    }
    fun fullscreen(value: Boolean) { if (fullscreen != value) { interrupt(); fullscreen = value } }
    fun configureControls(tools: PlayerToolsState) {
        doubleTapSeek = tools.doubleTapSeek
        doubleTapPlay = tools.doubleTapPlay
        hideWhenPaused = tools.hideWhenPaused
        mutable.value = state.value.copy(options = state.value.options.copy(doubleTapPlay = tools.doubleTapPlay,
            doubleTapSeek = tools.doubleTapSeek, forwardSeconds = tools.seekSeconds, rewindSeconds = tools.rewindSeconds))
    }
    var simulatedBrightness = .5f
        private set
    var simulatedVolume = .5f
        private set
    fun attachDeviceControls(controls: PlaybackDeviceControls?) {
        interrupt()
        deviceControls = controls
    }
    val committedSpeed: Float get() = active?.takeIf { it.kind == GestureKind.SPEED }?.speed ?: snapshot().speed
    override fun currentLevel(kind: GestureKind): Float = if (kind == GestureKind.BRIGHTNESS) state.value.brightness else state.value.volume
    override fun levelMax(kind: GestureKind): Float = active?.takeIf { it.kind == kind }?.maxLevel ?: 1f

    private fun valid(context: InteractionContext) = enabled && context.sessionId == snapshot().sessionId &&
        context.revision == state.value.revision && snapshot().media != null && !state.value.panelOpen

    fun configureSeek(byTime: Boolean, seconds: Int, percent: Int) {
        mutable.value = state.value.copy(dragByTime = byTime, dragSeconds = seconds, dragPercent = percent)
    }
    fun show() { mutable.value = state.value.copy(controlsVisible = true); hideAt = now() + 4_000 }
    override fun controlsUsed(context: InteractionContext) { if (valid(context)) show() }
    fun tick() {
        val s = snapshot()
        if (state.value.levelFeedback != null && active == null && now() >= levelHideAt) {
            mutable.value = state.value.copy(levelFeedback = null)
        }
        if (s.status != lastStatus) {
            if (s.status != PlaybackStatus.PLAYING) interrupt()
            show()
            lastStatus = s.status
        }
        if (enabled && state.value.controlsVisible && !state.value.panelOpen && active == null &&
            s.media?.kind == MediaKind.VIDEO && (s.status == PlaybackStatus.PLAYING ||
                (fullscreen && hideWhenPaused && s.status == PlaybackStatus.PAUSED)) && now() >= hideAt) {
            mutable.value = state.value.copy(controlsVisible = false)
        }
    }

    override fun begin(context: InteractionContext, kind: GestureKind): Long? {
        if (!valid(context) || state.value.locked || active != null) return null
        val s = snapshot()
        val options = state.value.options
        if ((kind == GestureKind.BRIGHTNESS && !options.brightnessGesture) ||
            (kind == GestureKind.VOLUME && !options.volumeGesture) ||
            (kind == GestureKind.SPEED && !options.longPress)) return null
        val level = kind == GestureKind.BRIGHTNESS || kind == GestureKind.VOLUME
        if (level) {
            if (s.simulated) {
                if (!s.capabilities.canAdjustDevice) return null
            } else {
                val actual = deviceControls?.begin(kind) ?: return null
                mutable.value = if (kind == GestureKind.BRIGHTNESS) state.value.copy(brightness = actual)
                    else state.value.copy(volume = actual)
            }
        }
        if (kind == GestureKind.SEEK && !s.canSeek) return null
        if (kind == GestureKind.ZOOM && s.media?.kind != MediaKind.VIDEO) return null
        if (kind == GestureKind.SPEED && (s.media?.kind != MediaKind.VIDEO ||
            (s.status != PlaybackStatus.PLAYING && !(fullscreen && s.status == PlaybackStatus.PAUSED)) ||
            !s.capabilities.canSetSpeed)) return null
        val u = state.value
        val maxLevel = if (kind == GestureKind.VOLUME && u.volume >= 1f &&
            (s.simulated || s.capabilities.canSetAudioGain)) 2f else 1f
        val a = Active(++sequence, s.sessionId, kind, s.speed, u.brightness, u.volume, u.scale, !s.simulated && level, maxLevel)
        val holdSpeed = options.holdSpeed.coerceIn(s.capabilities.minSpeed, s.capabilities.maxSpeed)
        if (kind == GestureKind.SPEED && dispatch(PlaybackCommand.SetSpeed(s.sessionId, holdSpeed)) !is CommandResult.Accepted) return null
        active = a
        if (!level) show()
        mutable.value = state.value.copy(gesture = kind, levelFeedback = null,
            temporarySpeed = holdSpeed.takeIf { kind == GestureKind.SPEED },
            previewMs = if (kind == GestureKind.SEEK) s.positionMs else null)
        return a.token
    }

    override fun change(token: Long, value: Double) {
        val a = active?.takeIf { it.token == token && it.session == snapshot().sessionId } ?: return
        if (!value.isFinite()) return
        val u = state.value
        mutable.value = when (a.kind) {
            GestureKind.SEEK -> if (snapshot().canSeek) u.copy(previewMs = value.coerceIn(0.0, snapshot().durationMs!!.toDouble()).roundToLong()) else u
            GestureKind.BRIGHTNESS, GestureKind.VOLUME -> {
                val requested = value.coerceIn(0.0, a.maxLevel.toDouble()).toFloat()
                val actual = if (snapshot().simulated) requested else deviceControls?.change(a.kind, requested) ?: return
                levelHideAt = now() + 1_000
                if (a.kind == GestureKind.BRIGHTNESS) u.copy(brightness = actual, levelFeedback = a.kind)
                else u.copy(volume = actual, levelFeedback = a.kind)
            }
            GestureKind.ZOOM -> u.copy(scale = value.coerceIn(0.5, 2.0).toFloat())
            GestureKind.SPEED -> {
                if (!u.options.speedGesture) return
                val speed = (value * 10).roundToLong().div(10f)
                    .coerceIn(snapshot().capabilities.minSpeed, snapshot().capabilities.maxSpeed)
                if (speed == u.temporarySpeed || dispatch(PlaybackCommand.SetSpeed(a.session, speed)) !is CommandResult.Accepted) u
                else u.copy(temporarySpeed = speed)
            }
        }
    }

    override fun finish(token: Long, commit: Boolean) {
        val a = active?.takeIf { it.token == token } ?: return
        active = null // Clear before dispatch: repeated UP/CANCEL cannot commit twice.
        val level = a.kind == GestureKind.BRIGHTNESS || a.kind == GestureKind.VOLUME
        if (level && commit) levelHideAt = now() + 1_000
        if (a.device) deviceControls?.finish(a.kind, commit)
        if (a.session == snapshot().sessionId) {
            if (a.kind == GestureKind.SPEED) dispatch(PlaybackCommand.SetSpeed(a.session, a.speed))
            if (commit && a.kind == GestureKind.SEEK) state.value.previewMs?.let { dispatch(PlaybackCommand.Seek(a.session, it)) }
        }
        mutable.value = state.value.copy(gesture = null, previewMs = null, temporarySpeed = null,
            levelFeedback = if (commit && level) state.value.levelFeedback else null,
            brightness = if (!commit) a.brightness else state.value.brightness,
            volume = if (!commit) a.volume else state.value.volume,
            scale = if (!commit) a.scale else state.value.scale)
        if (level && !a.device && commit) {
            simulatedBrightness = state.value.brightness
            simulatedVolume = state.value.volume
        }
        if (level && commit) requestTick(1_000)
        if (level) hideAt = now() + 4_000 else show()
    }

    override fun tap(context: InteractionContext, double: Boolean) {
        if (!valid(context) || active != null) return
        if (double) {
            if (state.value.locked) return
            val s = snapshot()
            dispatch(if (s.status == PlaybackStatus.PLAYING) PlaybackCommand.Pause(s.sessionId) else PlaybackCommand.Play(s.sessionId))
            show()
        } else if (snapshot().media?.kind == MediaKind.AUDIO) show()
        else {
            val visible = !state.value.controlsVisible
            show()
            mutable.value = state.value.copy(controlsVisible = visible)
        }
    }

    override fun resetScale(context: InteractionContext) {
        if (valid(context) && !state.value.locked) { interrupt(); mutable.value = state.value.copy(scale = 1f) }
    }
    override fun doubleTap(context: InteractionContext, fraction: Float) {
        if (!valid(context) || active != null || state.value.locked || !fraction.isFinite()) return
        val s = snapshot()
        val seek = doubleTapSeek && s.canSeek
        val direction = if (!seek) 0 else if (doubleTapPlay) {
            when { fraction < 1f / 3 -> -1; fraction > 2f / 3 -> 1; else -> 0 }
        } else if (fraction < .5f) -1 else 1
        if (direction != 0) {
            dispatch(PlaybackCommand.Seek(s.sessionId,
                (s.positionMs + direction * (if (direction > 0) state.value.options.forwardSeconds
                    else state.value.options.rewindSeconds) * 1000L).coerceIn(0, s.durationMs!!)))
            show()
        } else if (doubleTapPlay) tap(context, true)
    }
    fun toggleLock() { interrupt(); mutable.value = state.value.copy(locked = !state.value.locked) }
    fun panel(open: Boolean) { interrupt(); mutable.value = state.value.copy(panelOpen = open) }
    fun interrupt(unlock: Boolean = false) {
        active?.let { finish(it.token, false) }
        mutable.value = state.value.copy(revision = state.value.revision + 1, levelFeedback = null, locked = if (unlock) false else state.value.locked)
        show()
    }
    fun setEnabled(value: Boolean) { interrupt(); enabled = value }
    fun mediaChanged() {
        interrupt(true)
        mutable.value = PlayerInteractionState(revision = state.value.revision, brightness = simulatedBrightness,
            volume = simulatedVolume, options = state.value.options)
        lastStatus = snapshot().status
    }
    fun restorePresentation(scale: Float, brightness: Float, volume: Float) {
        simulatedBrightness = brightness.coerceIn(0f, 1f)
        simulatedVolume = volume.coerceIn(0f, 2f)
        mutable.value = state.value.copy(scale = scale.coerceIn(0.5f, 2f), brightness = brightness.coerceIn(0f, 1f), volume = volume.coerceIn(0f, 2f))
    }
}
