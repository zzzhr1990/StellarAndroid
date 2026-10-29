package cn.coldlake.stellar.opensource.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

internal class LibraryScanSettings(val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("library_scan", Context.MODE_PRIVATE))
    var quickHashEnabled: Boolean
        get() = preferences.getBoolean("quick_hash", true)
        set(value) { preferences.edit { putBoolean("quick_hash", value) } }
    var scanThreads: Int
        get() = preferences.getInt("threads", 1).coerceIn(1, 4)
        set(value) { preferences.edit { putInt("threads", value.coerceIn(1, 4)) } }
}
