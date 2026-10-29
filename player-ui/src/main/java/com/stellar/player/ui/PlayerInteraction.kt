package com.stellar.player.ui

/** UI-only transient state; no engine or localized text. Tokens also isolate cancelled gestures. */
enum class GestureKind { SEEK, BRIGHTNESS, VOLUME, SPEED, ZOOM }
data class InteractionContext(val sessionId: Long, val revision: Long)
data class PlayerInteractionState(
    val revision: Long = 0,
    val controlsVisible: Boolean = true,
    val locked: Boolean = false,
    val panelOpen: Boolean = false,
    val gesture: GestureKind? = null,
    val previewMs: Long? = null,
    val brightness: Float = 0.5f,
    val volume: Float = 0.5f,
    val levelFeedback: GestureKind? = null,
    val scale: Float = 1f,
    val dragByTime: Boolean = false,
    val dragSeconds: Int = 10,
    val dragPercent: Int = 100,
    val options: PlaybackInteractionOptions = PlaybackInteractionOptions(),
    val temporarySpeed: Float? = null,
)
interface PlayerInteractionActions {
    /** Refresh the existing chrome timeout for a local layout action, without changing playback. */
    fun controlsUsed(context: InteractionContext) {}
    fun currentLevel(kind: GestureKind): Float? = null
    fun levelMax(kind: GestureKind): Float = 1f
    fun begin(context: InteractionContext, kind: GestureKind): Long?
    /** Absolute milliseconds, brightness [0,1], volume [0,2], or frame scale. */
    fun change(token: Long, value: Double)
    fun finish(token: Long, commit: Boolean)
    fun tap(context: InteractionContext, double: Boolean = false)
    /** Physical horizontal fraction; accessibility playback actions still call tap(double=true). */
    fun doubleTap(context: InteractionContext, fraction: Float) { tap(context, true) }
    fun resetScale(context: InteractionContext)
}
