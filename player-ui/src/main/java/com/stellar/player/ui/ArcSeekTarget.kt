package com.stellar.player.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Window coordinates, frozen for a pointer stream. Physical handedness is independent of RTL. */
internal data class ArcSeekTarget(
    val x: Float, val y: Float, val radius: Float, val halfBand: Float,
    val rightHanded: Boolean, val durationMs: Long, val enabled: Boolean,
) {
    private fun inward(px: Float) = (px - x) * if (rightHanded) -1 else 1
    fun contains(px: Float, py: Float): Boolean {
        val dx = inward(px)
        val dy = py - y
        return if (dx >= 0) abs(hypot(dx, dy) - radius) <= halfBand
        else minOf(hypot(dx, dy - radius), hypot(dx, dy + radius)) <= halfBand
    }

    fun fraction(px: Float, py: Float, previous: Double? = null): Double {
        val dx = inward(px)
        val dy = py - y
        // Keep the last value through the centre; never wrap around the back of the semicircle.
        if (previous != null && hypot(dx, dy) < radius * .35f) return previous
        if (dx < 0 && previous != null) return if (previous < .5) 0.0 else 1.0
        // Right-side mirroring produces -0.0 at the endpoints. atan2(-0.0, negative)
        // is -PI, which would incorrectly clamp the bottom endpoint back to zero.
        val horizontal = if (dx <= 0f) 0.0 else dx.toDouble()
        return (atan2(horizontal, -dy.toDouble()) / PI).coerceIn(0.0, 1.0)
    }
}

/** Shared with the existing screen gesture adapter; the Canvas has no rectangular touch node. */
internal class ArcSeekHandle {
    var target: ArcSeekTarget? = null
        private set
    var cancelDrag: (() -> Unit)? = null
    fun update(value: ArcSeekTarget?) {
        if (target != value) {
            cancelDrag?.invoke()
            target = value
        }
    }
}
