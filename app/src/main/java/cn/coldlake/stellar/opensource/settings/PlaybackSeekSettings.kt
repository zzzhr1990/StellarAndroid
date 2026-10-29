package cn.coldlake.stellar.opensource.settings

import android.content.Context
import androidx.core.content.edit

/** Real playback preference, separate from presentation-only settings previews. */
internal class PlaybackSeekSettings(context: Context) {
    val preferences = context.applicationContext.getSharedPreferences("playback_seek_v1", Context.MODE_PRIVATE)

    var accurateSeek: Boolean
        get() = preferences.getBoolean("accurate_seek", true)
        set(value) { preferences.edit { putBoolean("accurate_seek", value) } }
}
