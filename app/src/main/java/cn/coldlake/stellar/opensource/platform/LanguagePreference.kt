package cn.coldlake.stellar.opensource.platform

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

enum class AppLanguage(val tag: String) { SYSTEM(""), HANS("zh-Hans"), HANT("zh-Hant"), ENGLISH("en") }

/** Pure matcher: preserve preferred region for formatting; explicit script wins over region. */
object LanguageResolver {
    fun resolve(preferences: List<Locale>): Locale {
        for (locale in preferences) {
            if (locale.language == "en") return locale
            if (locale.language == "zh") {
                val script = when (locale.script) {
                    "Hans", "Hant" -> locale.script
                    "" -> if (locale.country in setOf("TW", "HK", "MO")) "Hant" else "Hans"
                    else -> continue
                }
                return Locale.Builder().setLocale(locale).setScript(script).build()
            }
        }
        return Locale.ENGLISH
    }
}

/** One owner for app-only preferences; no global Locale mutation or device settings writes. */
object LanguagePreference {
    private fun prefs(context: Context) = context.getSharedPreferences("presentation", Context.MODE_PRIVATE)
    fun read(context: Context): AppLanguage = AppLanguage.entries.firstOrNull {
        it.name == prefs(context).getString("language", AppLanguage.SYSTEM.name)
    } ?: AppLanguage.SYSTEM
    fun save(context: Context, language: AppLanguage) {
        prefs(context).edit().putString("language", language.name).apply()
    }
    fun wrap(context: Context): Context {
        val preference = read(context)
        val system = Resources.getSystem().configuration.locales
        val locale = if (preference == AppLanguage.SYSTEM)
            LanguageResolver.resolve((0 until system.size()).map { system[it] })
        else Locale.forLanguageTag(preference.tag)
        val override = Configuration().apply {
            setLocales(LocaleList(locale))
            setLayoutDirection(locale)
        }
        return context.createConfigurationContext(override)
    }
}
