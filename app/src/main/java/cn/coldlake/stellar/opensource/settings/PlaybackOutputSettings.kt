package cn.coldlake.stellar.opensource.settings

import android.content.Context
import androidx.core.content.edit

/** Applied once per playback session, shared by both native backends. */
internal class PlaybackOutputSettings(context: Context) {
    val preferences = context.applicationContext.getSharedPreferences("playback_output_v1", Context.MODE_PRIVATE)
    var forceSdr: Boolean
        get() = preferences.getBoolean("force_sdr", false)
        set(value) { preferences.edit { putBoolean("force_sdr", value) } }
}
