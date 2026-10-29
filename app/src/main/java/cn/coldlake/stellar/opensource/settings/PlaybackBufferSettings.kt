package cn.coldlake.stellar.opensource.settings

import android.content.Context
import androidx.core.content.edit
import com.stellar.player.nativeio.PlaybackBufferOptions

internal class PlaybackBufferSettings(context: Context) {
    val preferences = context.applicationContext.getSharedPreferences("playback_buffer_v1", Context.MODE_PRIVATE)
    var options: PlaybackBufferOptions
        get() {
            val min = preferences.getInt("minimum_seconds", 3)
            val max = preferences.getInt("maximum_seconds", 15)
            if (min !in MINIMUM_SECONDS || max !in MAXIMUM_SECONDS || min > max) return PlaybackBufferOptions()
            return PlaybackBufferOptions(min * 1_000L, max * 1_000L, preferences.getBoolean("drop", false))
        }
        set(value) {
            require(value.minMs in MINIMUM_SECONDS.map { it * 1_000L } && value.maxMs in MAXIMUM_SECONDS.map { it * 1_000L })
            preferences.edit {
                putInt("minimum_seconds", (value.minMs / 1_000).toInt())
                putInt("maximum_seconds", (value.maxMs / 1_000).toInt())
                putBoolean("drop", value.drop)
            }
        }
    fun reset() { preferences.edit { clear() } }
    companion object {
        val MINIMUM_SECONDS = listOf(0, 1, 2, 3, 5, 10)
        val MAXIMUM_SECONDS = listOf(5, 10, 15, 30, 60)
    }
}
