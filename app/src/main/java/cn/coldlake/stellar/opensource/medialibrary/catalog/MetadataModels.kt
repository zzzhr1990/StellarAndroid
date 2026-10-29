package cn.coldlake.stellar.opensource.medialibrary.catalog

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal data class CachedMetadataQuery(val expires: Long, val etag: String?, val payload: String)
internal fun metadataLanguage(tag: String): String = when {
    tag.startsWith("zh-Hant") || tag == "zh-TW" || tag == "zh-HK" -> "zh-TW"
    tag.startsWith("zh") -> "zh-CN"
    else -> "en-US"
}

internal data class MetadataRecord(
    val identity: String, val kind: String, val title: String, val year: Int? = null,
    val plot: String? = null, val show: String? = null, val season: Int? = null,
    val episodes: List<Int> = emptyList(), val ids: Map<String, String> = emptyMap(),
    val poster: String? = null, val locale: String = "und", val version: String = "1", val fieldSources: String? = null,
    val seriesIdentity: String? = null, val playback: String? = null, val resumeMs: Long = 0,
    val durationMs: Long = 0, val watched: Boolean = false, val lastPlayed: Long = 0,
    val groupIdentity: String? = null,
    val versionName: String? = null,
    val detailsPending: Boolean = false,
    val retryAt: Long = 0,
    val episodeHint: EpisodeIdentity? = null,
    val details: LibraryDetails = LibraryDetails(),
    val seriesDetails: LibraryDetails? = null,
    val matchMethod: String = "path",
    // In-process only: published after identity validation and a successful catalog bind.
    val seriesCacheKey: String? = null,
    // Accepted with the file binding and stored separately in metadata_entity.
    val publicParents: List<MetadataRecord> = emptyList(),
) {
    fun json(): String = JSONObject().put("identity", identity).put("kind", kind).put("title", title)
        .put("year", year).put("plot", plot).put("show", show).put("season", season)
        .put("episodes", JSONArray(episodes)).put("ids", JSONObject(ids)).put("poster", poster)
        .put("locale", locale).put("version", version).put("fieldSources", fieldSources)
        .put("seriesIdentity", seriesIdentity).put("playback", playback).put("resumeMs", resumeMs)
        .put("durationMs", durationMs).put("watched", watched).put("lastPlayed", lastPlayed).put("groupIdentity",groupIdentity).put("versionName",versionName)
        .put("detailsPending", detailsPending).put("retryAt", retryAt)
        .put("details", details.json()).put("seriesDetails", seriesDetails?.json()).put("matchMethod", matchMethod).toString()
    companion object {
        fun parse(raw: String, includeDetails: Boolean = true): MetadataRecord {
            val j = JSONObject(raw)
            fun text(k: String) = if (j.isNull(k)) null else j.optString(k).takeIf { it.isNotBlank() }
            fun number(k: String) = if (j.isNull(k)) null else j.getInt(k)
            val ids = j.optJSONObject("ids") ?: JSONObject()
            val episodes = j.optJSONArray("episodes") ?: JSONArray()
            return MetadataRecord(j.getString("identity"), j.getString("kind"), j.getString("title"),
                number("year"), text("plot"), text("show"), number("season"),
                (0 until episodes.length()).map { episodes.getInt(it) },
                ids.keys().asSequence().associateWith { ids.getString(it) }, text("poster"),
                j.optString("locale", "und"), j.optString("version", "1"), text("fieldSources"),
                text("seriesIdentity"), text("playback"), j.optLong("resumeMs"), j.optLong("durationMs"),
                j.optBoolean("watched"), j.optLong("lastPlayed"), text("groupIdentity"), text("versionName"),
                j.optBoolean("detailsPending"), j.optLong("retryAt"),
                details = if (includeDetails) LibraryDetails.parse(j.optJSONObject("details")) else LibraryDetails(),
                seriesDetails = if (includeDetails) j.optJSONObject("seriesDetails")?.let(LibraryDetails::parse) else null,
                matchMethod = j.optString("matchMethod", "path"))
        }
        fun fromNfo(data: NfoData, identity: String) = MetadataRecord(identity, data.kind,
            data.title.orEmpty(), data.year, data.plot, data.show, data.season,
            data.episode?.let { listOf(it) }.orEmpty(), parseIds(data.providerIds), poster = data.details.poster?.uri, details = data.details)
    }
}

internal fun parseIds(raw: String?): Map<String, String> = raw.orEmpty().split(';').mapNotNull {
    val p = it.split(':', limit = 2); if (p.size == 2 && p.all(String::isNotBlank)) p[0] to p[1] else null
}.toMap()

internal data class MetadataInput(val id: Long, val fileName: String, val input: String,
    val editRevision: Long, val local: MetadataRecord?, val locked: Boolean,
    val seriesIds: Map<String, String> = emptyMap())

internal data class NameQuery(val title: String, val year: Int?, val season: Int?, val episodes: List<Int>,
    val explicitId: String? = null, val order: String = "aired",
    val ids: Map<String, String> = emptyMap(), val seriesIds: Map<String, String> = emptyMap()) {
    val kind get() = if (season == null) "movie" else "series"
    val normalized get() = normalizeTitle(title)
    fun key(locale: String) = listOf("tmdb", "2", kind, normalized, year ?: "", locale, explicitId ?: "").joinToString("|")
}
private val normalizedTitlePattern = Regex("[^\\p{L}\\p{N}]+")
private val seasonDirectoryPattern = Regex("(?i)(?:(?:season|s)[ ._-]*\\d+|specials|第[0-9一二三四五六七八九十百零〇两兩]+季|特别篇|特別篇)")
private val explicitIdPattern = Regex("(?i)[\\[{]tmdb[-= ](\\d+)[\\]}]")
private val episodePattern = Regex("(?i)(?:^|[ ._-])S(\\d{1,3})E(\\d{1,3})(?:[- ]?E?(\\d{1,3}))?")
private val releaseYearPattern = Regex("(?:^|[ ._(\\[])(19\\d{2}|20\\d{2})(?=$|[ ._)\\]])")
private val releaseSuffixPattern = Regex("(?i)(?:[ ._-])(?:2160p|1080p|720p|bluray|web-dl|webrip|hdtv|x264|x265|hevc)(?:[ ._-].*)?$")
private val nameSeparatorPattern = Regex("[._]+")
private val unsupportedOrderPattern = Regex("(?i)(?:[\\[{](dvd|absolute)[\\]}]|(?:dvd|absolute)[ ._-]+order)")

internal fun normalizeTitle(value: String) = value.lowercase(Locale.ROOT).replace(normalizedTitlePattern, " ").trim()

/** Conservative token removal; numeric titles survive. Unknown/absolute episode numbering stays manual. */
internal object MediaName {
    fun seasonDirectory(name: String): Boolean = name.trim().matches(seasonDirectoryPattern)
    private fun structuralDirectory(name: String): Boolean = seasonDirectory(name) || normalizeTitle(name) in
        setOf("tv", "tv shows", "tvshows", "shows", "series", "movies", "videos", "media", "电视剧", "電視劇", "剧集", "劇集")
    /** Two raw directory levels at most; skipping a category never expands that window. */
    fun directoryQuery(parent: String?, grandparent: String?): NameQuery? {
        val name = if (parent != null && seasonDirectory(parent)) grandparent else parent
        if (name.isNullOrBlank() || structuralDirectory(name)) return null
        return parse("$name.mkv").takeIf { it.title.isNotBlank() && !structuralDirectory(it.title) }
    }
    fun query(input: MetadataInput, directory: NameQuery?): NameQuery {
        val parsed = parse(input.fileName)
        val local = input.local
        val episodic = local?.kind == "episode" || local == null && parsed.season != null
        val title = if (episodic) local?.show?.takeIf(String::isNotBlank)
            ?: parsed.title.ifBlank { directory?.title.orEmpty() } else local?.title ?: parsed.title
        // An episode's air year is not the series premiere year.
        val year = if (episodic) parsed.year ?: directory?.year?.takeIf { normalizeTitle(directory.title) == normalizeTitle(title) }
            else local?.year ?: parsed.year
        return parsed.copy(title = title, year = year, season = if (local != null) local.season else parsed.season,
            episodes = if (local != null) local.episodes else parsed.episodes,
            explicitId = if (episodic) input.seriesIds["tmdb"] ?: parsed.explicitId else local?.ids?.get("tmdb") ?: parsed.explicitId,
            ids = local?.ids.orEmpty(), seriesIds = input.seriesIds)
    }
    fun parse(name: String): NameQuery {
        val stem = name.substringBeforeLast('.', name)
        val explicit = explicitIdPattern.find(stem)
        val value = stem.replace(explicit?.value ?: "\u0000", "")
        val episode = episodePattern.find(value)
        val season = episode?.groupValues?.get(1)?.toIntOrNull()
        val first = episode?.groupValues?.get(2)?.toIntOrNull()
        val last = episode?.groupValues?.get(3)?.toIntOrNull() ?: first
        val episodes = if (first != null && last != null && last in first..first + 20) (first..last).toList() else emptyList()
        val head = if (episode != null) value.substring(0, episode.range.first) else value
        val yearMatch = releaseYearPattern.findAll(head).lastOrNull()
        // A sole number (1917, 2001) is a title, never a release year.
        val year = yearMatch?.takeIf { head.substring(0, it.range.first).any(Char::isLetterOrDigit) }
        val title = (if (year != null) head.substring(0, year.range.first) else head)
            .replace(releaseSuffixPattern, "")
            .replace(nameSeparatorPattern, " ").trim(' ', '-', '(', ')', '[', ']')
        val order = unsupportedOrderPattern.find(stem)?.let { "unsupported" } ?: "aired"
        return NameQuery(title, year?.groupValues?.get(1)?.toIntOrNull(), season, episodes, explicit?.groupValues?.get(1), order)
    }
    fun choose(query: NameQuery, candidates: List<MetadataRecord>): MetadataRecord? {
        if (query.normalized.isEmpty()) return null
        val scored = candidates.map { candidate ->
            val title = normalizeTitle(candidate.title)
            val score = if (candidate.kind != query.kind) 0.0
                else if (query.year != null && candidate.year != query.year) 0.0
                else if (title == query.normalized) 1.0 else 0.0
            candidate to score
        }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        return best.first.takeIf { best.second >= .88 && best.second - (scored.getOrNull(1)?.second ?: 0.0) >= .08 }
    }
}
