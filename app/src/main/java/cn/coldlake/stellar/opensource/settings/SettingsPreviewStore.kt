package cn.coldlake.stellar.opensource.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.core.content.edit

/** Presentation-only values. Never read by playback, network clients, or platform settings. */
internal class SettingsPreviewStore(context: Context) {
    private val preferences = context.getSharedPreferences("settings_layout_preview_v1", Context.MODE_PRIVATE)
    private var revision by mutableIntStateOf(0)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
    fun start() = preferences.registerOnSharedPreferenceChangeListener(listener)
    fun stop() = preferences.unregisterOnSharedPreferenceChangeListener(listener)
    fun boolean(spec: SettingSpec): Boolean { revision; return preferences.getBoolean(spec.id, spec.enabledByDefault) }
    fun number(spec: SettingSpec): Float { revision; return preferences.getFloat(spec.id, spec.initialValue).coerceIn(spec.range) }
    fun text(spec: SettingSpec): String { revision; return preferences.getString(spec.id, "").orEmpty() }
    fun choice(spec: SettingSpec): Int { revision; return preferences.getInt(spec.id, spec.initialValue.toInt()).coerceIn(spec.options.indices) }
    fun save(key: String, value: Boolean) { preferences.edit { putBoolean(key, value) } }
    fun save(key: String, value: Float) { preferences.edit { putFloat(key, value) } }
    fun save(key: String, value: String) { preferences.edit { putString(key, value) } }
    fun saveChoice(key: String, value: Int) { preferences.edit { putInt(key, value) } }
}

@Composable
internal fun rememberSettingsPreviewStore(context: Context): SettingsPreviewStore {
    val store = remember(context.applicationContext) { SettingsPreviewStore(context.applicationContext) }
    DisposableEffect(store) { store.start(); onDispose { store.stop() } }
    return store
}
