package cn.coldlake.stellar.opensource.settings

import android.content.Context
import androidx.core.content.edit
import com.stellar.player.nativeio.PlaybackDiskCache
import java.io.File

internal class PlaybackCacheSettings(context: Context) {
    val preferences = context.applicationContext.getSharedPreferences("playback_disk_cache_v1", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = preferences.getBoolean("enabled", false)
        set(value) { preferences.edit { putBoolean("enabled", value) } }
    var retentionDays: Int
        get() = preferences.getInt("retention_days", 7).takeIf { it in RETENTION_DAYS } ?: 7
        set(value) { require(value in RETENTION_DAYS); preferences.edit { putInt("retention_days", value) } }
    companion object {
        val RETENTION_DAYS = listOf(1, 7, 30)
        @Volatile private var mpvCache: PlaybackDiskCache? = null
        // Reuse directory/identity/expiry management; each backend owns its byte format.
        fun mpvDiskCache(context: Context): PlaybackDiskCache = mpvCache ?: synchronized(this) {
            mpvCache ?: run {
                val app = context.applicationContext
                val settings = PlaybackCacheSettings(app)
                PlaybackDiskCache(File(app.cacheDir, "playback-mpv-disk-v1")) {
                    PlaybackDiskCache.Options(settings.enabled, settings.retentionDays)
                }.also { mpvCache = it }
            }
        }
        fun usageBytes(context: Context) = mpvDiskCache(context).usageBytes()
        fun clear(context: Context) = mpvDiskCache(context).clear()
    }
}
