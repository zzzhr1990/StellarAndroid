package com.stellar.player.contract

import java.util.Locale

/** Metadata first; title fallback accepts explicit language markers, never arbitrary title text.
 * No subtitle-content detection, file scanning or network requests. Ties retain stream order. */
object SubtitleLanguageSelector {
    private data class Language(val base: String, val script: String = "")
    private val aliases = buildMap {
        Locale.getISOLanguages().forEach { code ->
            put(code, code)
            runCatching { Locale.forLanguageTag(code).isO3Language }.getOrNull()?.let { put(it, code) }
        }
        putAll(mapOf("chi" to "zh", "fre" to "fr", "ger" to "de", "dut" to "nl",
            "cze" to "cs", "gre" to "el", "rum" to "ro", "slo" to "sk", "wel" to "cy",
            "baq" to "eu", "per" to "fa", "ice" to "is", "mac" to "mk", "may" to "ms",
            "alb" to "sq", "arm" to "hy", "bur" to "my", "geo" to "ka", "tib" to "bo"))
    }
    private val markers = listOf(
        Regex("(?i)(?<![a-z0-9])(?:zh[-_](?:hans(?:[-_][a-z]{2})?|cn|sg)|chs)(?![a-z0-9])|简体|簡體|简中|簡中|简英|簡英|(?i)simplified[ _-]+chinese") to Language("zh", "Hans"),
        Regex("(?i)(?<![a-z0-9])(?:zh[-_](?:hant(?:[-_][a-z]{2})?|tw|hk|mo)|cht)(?![a-z0-9])|繁体|繁體|繁中|繁英|(?i)traditional[ _-]+chinese") to Language("zh", "Hant"),
        Regex("(?i)(?<![a-z0-9])(?:en(?:[-_](?:us|gb))?|eng|english)(?![a-z0-9])|英文|英语|英語|中英|简英|簡英|繁英") to Language("en"),
        Regex("(?i)(?<![a-z0-9])(?:zh|zho|chi|chinese)(?![a-z0-9])|中文|汉语|漢語|中英") to Language("zh"),
    )

    private fun parse(value: String?): Language? {
        val tag = value?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-') ?: return null
        when (tag) {
            "chs" -> return Language("zh", "Hans")
            "cht" -> return Language("zh", "Hant")
            "english" -> return Language("en")
            "chinese" -> return Language("zh")
        }
        val parts = tag.split('-')
        val base = aliases[parts[0]] ?: return null
        val script = if (base != "zh") "" else when {
            "hans" in parts -> "Hans"
            "hant" in parts -> "Hant"
            parts.any { it in setOf("tw", "hk", "mo") } -> "Hant"
            parts.any { it in setOf("cn", "sg") } -> "Hans"
            else -> ""
        }
        return Language(base, script)
    }

    private fun titleLanguages(title: String?): Set<Language> {
        // Tags are untrusted; bound work independently of codec/container metadata size.
        val text = title.orEmpty().take(2048)
        val matches = markers.filter { it.first.containsMatchIn(text) }.map { it.second }.toSet()
        return if (matches.any { it.base == "zh" && it.script.isNotEmpty() })
            matches.filterNot { it.base == "zh" && it.script.isEmpty() }.toSet() else matches
    }

    private fun match(language: Language, target: Language): Int = when {
        language.base != target.base -> 0
        target.script.isEmpty() -> 300
        language.script == target.script -> 300
        language.script.isEmpty() -> 100
        else -> 0
    }

    fun select(tracks: List<TrackDescriptor>, preferredLanguage: String?): String? {
        val target = parse(preferredLanguage) ?: return null
        var selected: String? = null
        var best = 0
        for (track in tracks) {
            if (track.kind != TrackKind.SUBTITLE) continue
            val rawLanguage = track.language
            val metadata = parse(rawLanguage)
            val title = titleLanguages(track.title)
            val score = if (metadata != null) {
                // Generic Chinese metadata can be refined by an unambiguous script in the title.
                val scripts = title.filter { it.base == "zh" && it.script.isNotEmpty() }
                val refined = if (metadata.base == "zh" && metadata.script.isEmpty() && scripts.size == 1)
                    scripts.single() else metadata
                match(refined, target).let { if (it > 0) it + 20 else 0 }
            } else if (rawLanguage.isNullOrBlank() || rawLanguage.trim().lowercase(Locale.ROOT) in
                setOf("und", "unknown", "undetermined", "mul", "zxx")) {
                // Contradictory script markers do not justify guessing a Chinese variant.
                if (title.count { it.base == "zh" && it.script.isNotEmpty() } > 1 && target.base == "zh") 0
                else title.maxOfOrNull { match(it, target) } ?: 0
            } else 0 // Unrecognized nonempty metadata is not evidence for a different language.
            if (score > best) { best = score; selected = track.id }
        }
        return selected
    }
}
