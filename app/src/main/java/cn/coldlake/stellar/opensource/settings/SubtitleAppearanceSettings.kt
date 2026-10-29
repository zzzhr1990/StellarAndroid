package cn.coldlake.stellar.opensource.settings

import android.content.Context
import androidx.core.content.edit
import com.stellar.player.contract.SubtitleAppearanceOption

internal class SubtitleAppearanceSettings(context: Context) {
    val preferences = context.applicationContext.getSharedPreferences("subtitle_appearance_v1", Context.MODE_PRIVATE)
    operator fun get(option: SubtitleAppearanceOption): String = option.validated(preferences.getString(option.name, null))
    operator fun set(option: SubtitleAppearanceOption, value: String) {
        preferences.edit { putString(option.name, option.validated(value)) }
    }
    fun snapshot(): Map<SubtitleAppearanceOption, String> = SubtitleAppearanceOption.entries.associateWith { get(it) }
    fun reset() { preferences.edit { SubtitleAppearanceOption.entries.forEach { remove(it.name) } } }
}
