package cn.coldlake.stellar.opensource.settings

import android.content.Context
import androidx.core.content.edit
import cn.coldlake.stellar.opensource.platform.LanguagePreference

internal enum class SubtitleLanguage { OFF, CURRENT, HANS, HANT }

internal class SubtitleLanguageSettings(private val context: Context) {
    val preferences = context.applicationContext.getSharedPreferences("playback_subtitles_v1", Context.MODE_PRIVATE)

    init {
        if (!preferences.contains("language")) {
            // Preserve old explicit language choices; fresh installs and the old default follow the app language.
            val legacy = context.getSharedPreferences("settings_layout_preview_v1", Context.MODE_PRIVATE)
            val migrated = when (legacy.getInt("subtitle_language", 0)) {
                1 -> SubtitleLanguage.HANS
                2 -> SubtitleLanguage.HANT
                else -> SubtitleLanguage.CURRENT
            }
            preferences.edit { putString("language", migrated.name) }
        }
    }

    var language: SubtitleLanguage
        get() = SubtitleLanguage.entries.firstOrNull { it.name == preferences.getString("language", null) }
            ?: SubtitleLanguage.CURRENT
        set(value) { preferences.edit { putString("language", value.name) } }

    fun targetLanguage(): String? = when (language) {
        SubtitleLanguage.OFF -> null
        SubtitleLanguage.CURRENT -> LanguagePreference.wrap(context).resources.configuration.locales[0].toLanguageTag()
        SubtitleLanguage.HANS -> "zh-Hans"
        SubtitleLanguage.HANT -> "zh-Hant"
    }
}
