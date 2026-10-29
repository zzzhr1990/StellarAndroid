package cn.coldlake.stellar.opensource.cache

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal enum class CacheRetention(val days: Int) { MONTH(30), WEEK(7), DAY(1);
    val millis get() = days * 86_400_000L
}
internal data class CachePreferences(val dataEnabled: Boolean = true,
    val dataRetention: CacheRetention = CacheRetention.MONTH,
    val imageRetention: CacheRetention = CacheRetention.MONTH)

/** Typed policy, migrated once. Preview cleanup messages are deliberately not imported. */
internal class CacheSettings(private val context: Context, private val name: String = "media_cache_settings_v1",
    private val previewName: String = "settings_layout_preview_v1") {
    private val mutable = MutableStateFlow(CachePreferences())
    val state = mutable.asStateFlow()
    @Volatile var initialized = false; private set
    private val preferences by lazy { context.getSharedPreferences(name, Context.MODE_PRIVATE) }
    suspend fun initialize() = withContext(Dispatchers.IO) {
        val values = if (preferences.getBoolean("migrated", false)) preferences.all else
            context.getSharedPreferences(previewName, Context.MODE_PRIVATE).all
        fun retention(key: String) = CacheRetention.entries.getOrNull(values[key] as? Int ?: 0) ?: CacheRetention.MONTH
        val value = CachePreferences(values["data_cache"] as? Boolean ?: true, retention("data_expiry"), retention("image_expiry"))
        save(value)
        initialized = true
    }
    suspend fun save(value: CachePreferences) = withContext(Dispatchers.IO) {
        check(preferences.edit().putBoolean("migrated", true).putBoolean("data_cache", value.dataEnabled)
            .putInt("data_expiry", value.dataRetention.ordinal).putInt("image_expiry", value.imageRetention.ordinal).commit())
        mutable.value = value
    }
}

internal enum class CacheWork { IDLE, CALCULATING, CLEARING, DONE, FAILED }
internal data class CacheUsage(val bytes: Long? = null, val work: CacheWork = CacheWork.IDLE)
internal data class CacheMaintenanceState(val data: CacheUsage = CacheUsage(), val images: CacheUsage = CacheUsage(),
    val busy: Boolean = false)
internal data class CacheChange(val sequence: Long = 0, val namespace: String? = null, val disabled: Boolean = false)
