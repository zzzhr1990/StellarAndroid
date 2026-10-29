package com.stellar.player.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.stellar.player.contract.PlaybackSnapshot
import kotlin.math.abs
import kotlin.math.hypot

/** One owner per pointer stream. Android detectors supply platform double-tap timing/slop. */
internal class PlayerTouch(
    context: Context,
    private val identity: InteractionContext,
    private val actions: PlayerInteractionActions,
    private val snapshot: () -> PlaybackSnapshot,
    private val ui: () -> PlayerInteractionState,
    private val width: () -> Int,
    private val height: () -> Int,
    private val seekBar: Boolean = false,
    private val arcSeek: ArcSeekHandle? = null,
    private val windowX: () -> Float = { 0f },
    private val windowY: () -> Float = { 0f },
) {
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var token: Long? = null
    private var kind: GestureKind? = null
    private var downX = 0f
    private var downY = 0f
    private var downPosition = 0L
    private var initialLevel = 0.5f
    private var levelOriginY = 0f
    private var initialScale = 1f
    private var span = 0f
    private var multi = false
    private var moved = false
    private var allowTap = false
    private var disposed = false
    private var downTime = 0L
    private val speedSlop = 32f * context.resources.displayMetrics.density
    private var speedOriginX = 0f
    private var holdSpeed = 2f
    private var arcStream = false
    private var arcTarget: ArcSeekTarget? = null
    private var arcFraction: Double? = null
    private var arcPointer = -1
    private val longPress = Runnable {
        if (!disposed && !moved && !multi && !seekBar && ui().options.longPress) {
            allowTap = false
            kind = GestureKind.SPEED
            token = actions.begin(identity, GestureKind.SPEED)
            speedOriginX = downX
            holdSpeed = ui().temporarySpeed ?: ui().options.holdSpeed
        }
    }
    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (allowTap && !disposed) actions.tap(identity)
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!disposed) actions.doubleTap(identity, (e.x / width().coerceAtLeast(1)).coerceIn(0f, 1f))
            allowTap = false
            handler.removeCallbacks(longPress)
            return true
        }
    }).apply { setIsLongpressEnabled(false) }

    fun event(e: MotionEvent): Boolean {
        if (disposed) return false
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            arcStream = false
            val target = arcSeek?.target
            if (target != null && target.contains(e.x + windowX(), e.y + windowY())) {
                handler.removeCallbacks(longPress); allowTap = false; cancelTaps(); finish(false)
                arcStream = true
                arcTarget = target
                arcFraction = null
                arcPointer = e.getPointerId(0)
                token = if (target.enabled) actions.begin(identity, GestureKind.SEEK) else null
                arcMove(e)
                return true
            }
        }
        if (arcStream) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && e.getPointerId(0) == arcPointer) arcMove(e) else cancelArc()
                MotionEvent.ACTION_UP -> { arcMove(e); finish(true); arcStream = false; arcTarget = null }
                MotionEvent.ACTION_CANCEL -> { cancelArc(); arcStream = false }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> cancelArc()
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                finish(false)
                downX = e.x; downY = e.y; downTime = e.eventTime
                downPosition = snapshot().positionMs
                moved = false; multi = false; allowTap = true; kind = null
                if (seekBar) {
                    kind = GestureKind.SEEK
                    token = actions.begin(identity, GestureKind.SEEK)
                    seek(e.x)
                } else {
                    handler.postDelayed(longPress, 700)
                    taps.onTouchEvent(e)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(longPress); allowTap = false; multi = true
                finish(false)
                cancelTaps()
                if (!seekBar && e.pointerCount == 2) {
                    kind = GestureKind.ZOOM
                    initialScale = ui().scale
                    span = distance(e)
                    token = actions.begin(identity, GestureKind.ZOOM)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (seekBar) { if (!multi) seek(e.x) }
                else if (multi) {
                    if (e.pointerCount == 2 && span > 0f) token?.let { actions.change(it, (initialScale * distance(e) / span).toDouble()) }
                } else {
                    val dx = e.x - downX; val dy = e.y - downY
                    if (kind == null && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true; allowTap = false; handler.removeCallbacks(longPress); cancelTaps()
                        kind = if (abs(dx) >= abs(dy)) GestureKind.SEEK
                            else if (downX < width() / 2f) GestureKind.BRIGHTNESS else GestureKind.VOLUME
                        token = if (kind == GestureKind.SEEK && !ui().options.seekGesture) null
                            else actions.begin(identity, kind!!)
                        // begin refreshes real device levels, including changes from hardware keys.
                        initialLevel = actions.currentLevel(kind!!) ?: if (kind == GestureKind.BRIGHTNESS) ui().brightness else ui().volume
                        levelOriginY = e.y
                    }
                    token?.let {
                        when (kind) {
                            GestureKind.SPEED -> if (ui().options.speedGesture) {
                                val distance = e.x - speedOriginX
                                val adjusted = if (abs(distance) <= speedSlop) 0f
                                    else distance - kotlin.math.sign(distance) * speedSlop
                                actions.change(it, holdSpeed + adjusted.toDouble() / width().coerceAtLeast(1) * 8.0)
                            }
                            GestureKind.SEEK -> actions.change(it, downPosition.toDouble() + dx / width().coerceAtLeast(1) *
                                if (ui().dragByTime) ui().dragSeconds * 1000.0 else (snapshot().durationMs ?: 0).toDouble() / 24.0 * ui().dragPercent / 100.0)
                            GestureKind.BRIGHTNESS, GestureKind.VOLUME -> {
                                val range = if (kind == GestureKind.BRIGHTNESS) 2f / 3f else actions.levelMax(GestureKind.VOLUME)
                                val distance = (levelOriginY - e.y) / minOf(width(), height()).coerceAtLeast(1)
                                actions.change(it, (initialLevel + distance * range).toDouble())
                            }
                            else -> Unit
                        }
                    }
                    if (!moved && kind == null && e.eventTime - downTime >= 700) {
                        handler.removeCallbacks(longPress); longPress.run()
                    }
                    if (!moved && kind == null) taps.onTouchEvent(e)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                finish(true)
                // Do not reinterpret the remaining finger as a new seek or a tap.
                allowTap = false; multi = true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (seekBar && !multi) seek(e.x)
                if (!seekBar && !moved && !multi && kind == null) taps.onTouchEvent(e)
                else cancelTaps()
                finish(true)
            }
            MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(longPress); allowTap = false; cancelTaps(); finish(false) }
        }
        return true
    }
    private fun arcMove(e: MotionEvent) {
        val target = arcTarget ?: return
        if (target != arcSeek?.target) { cancelArc(); return }
        arcFraction = target.fraction(e.x + windowX(), e.y + windowY(), arcFraction)
        token?.let { actions.change(it, arcFraction!! * target.durationMs) }
    }
    fun cancelArc() {
        if (arcStream) { finish(false); arcTarget = null }
    }
    private fun seek(x: Float) { token?.let { actions.change(it, x.toDouble() / width().coerceAtLeast(1) * (snapshot().durationMs ?: 0)) } }
    private fun distance(e: MotionEvent) = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
    private fun finish(commit: Boolean) { token?.let { actions.finish(it, commit) }; token = null }
    private fun cancelTaps() {
        val e = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        taps.onTouchEvent(e); e.recycle()
    }
    fun dispose() { disposed = true; handler.removeCallbacks(longPress); allowTap = false; cancelTaps(); finish(false) }
}
