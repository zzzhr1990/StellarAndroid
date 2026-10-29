package cn.coldlake.stellar.opensource.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit

/** Presentation preference, independent of playback gesture policy. */
internal class HandednessSettings(context: Context) {
    val preferences: SharedPreferences = context.getSharedPreferences("interaction_layout", Context.MODE_PRIVATE)
    var rightHanded: Boolean
        get() = preferences.getBoolean("right_handed", true)
        set(value) = preferences.edit { putBoolean("right_handed", value) }
}

@Composable internal fun rememberRightHanded(): Boolean {
    val context = LocalContext.current.applicationContext
    val settings = remember(context) { HandednessSettings(context) }
    var value by remember(settings) { mutableStateOf(settings.rightHanded) }
    DisposableEffect(settings) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> value = settings.rightHanded }
        settings.preferences.registerOnSharedPreferenceChangeListener(listener)
        value = settings.rightHanded
        onDispose { settings.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}
