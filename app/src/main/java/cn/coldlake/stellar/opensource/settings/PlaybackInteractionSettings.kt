package cn.coldlake.stellar.opensource.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.stellar.player.ui.PlaybackInteractionOptions
import kotlin.math.roundToInt

/** Persists interaction preferences consumed by the playback controls. */
internal class PlaybackInteractionSettings(val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("playback_controls", Context.MODE_PRIVATE))

    private fun key(id: String) = when (id) {
        "double_pause" -> "doubleTapPlay"
        "double_seek" -> "doubleTapSeek"
        else -> id
    }
    fun boolean(spec: SettingSpec) = preferences.getBoolean(key(spec.id), spec.enabledByDefault)
    fun number(spec: SettingSpec): Float = preferences.getFloat(spec.id, spec.initialValue)
        .takeIf { it.isFinite() }?.coerceIn(spec.range) ?: spec.initialValue
    fun save(id: String, value: Boolean) = preferences.edit { putBoolean(key(id), value) }
    fun save(id: String, value: Float) {
        val spec = settingsCatalogue.getValue(SettingsPage.INTERACTION).first { it.id == id }
        if (!value.isFinite()) return
        val step = if (id == "hold_speed") 10f else 1f
        preferences.edit { putFloat(id, (value.coerceIn(spec.range) * step).roundToInt() / step) }
    }
    fun read(): PlaybackInteractionOptions {
        val p = preferences
        fun flag(id: String, default: Boolean = true) = p.getBoolean(id, default)
        fun number(id: String, default: Float, range: ClosedFloatingPointRange<Float>) =
            p.getFloat(id, default).takeIf { it.isFinite() }?.coerceIn(range) ?: default
        return PlaybackInteractionOptions(
            doubleTapPlay = flag("doubleTapPlay"), doubleTapSeek = flag("doubleTapSeek", false),
            showPlayButton = flag("show_pause", false), showSeekButtons = flag("show_seek", false),
            forwardSeconds = number("forward_seconds", 10f, 1f..60f).roundToInt(),
            rewindSeconds = number("rewind_seconds", 10f, 1f..60f).roundToInt(),
            longPress = flag("long_press"), speedGesture = flag("speed_gesture"),
            holdSpeed = number("hold_speed", 2f, .5f..8f), seekGesture = flag("seek_gesture"),
            brightnessGesture = flag("brightness_gesture"), volumeGesture = flag("volume_gesture"),
            showTime = flag("status_time"), showNetworkSpeed = flag("status_speed"),
            showBattery = flag("status_battery"), ignoreSafeArea = flag("ignore_safe", false),
        )
    }
}
