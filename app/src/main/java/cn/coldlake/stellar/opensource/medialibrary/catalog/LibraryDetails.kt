package cn.coldlake.stellar.opensource.medialibrary.catalog

import org.json.JSONArray
import org.json.JSONObject

/** Durable metadata only. Image bytes remain in the existing bounded image cache. */
internal data class LibraryArtwork(val uri: String, val version: String? = null) {
    fun json() = JSONObject().put("uri", uri).put("version", version)
}
internal data class LibraryRating(val source: String, val value: Double, val max: Double = 10.0, val votes: Long? = null, val metric: String = "rating") {
    fun json() = JSONObject().put("source", source).put("value", value).put("max", max).put("votes", votes).put("metric", metric)
}
internal data class LibraryCreditPart(val name: String, val department: String? = null, val episodes: Int? = null) {
    fun json() = JSONObject().put("name", name).put("department", department).put("episodes", episodes)
}
internal data class LibraryCredit(val id: String, val name: String, val type: String, val role: String? = null,
    val image: LibraryArtwork? = null, val department: String? = null, val order: Int? = null,
    val roles: List<LibraryCreditPart> = emptyList(), val jobs: List<LibraryCreditPart> = emptyList(),
    val rolesTruncated: Boolean = false, val jobsTruncated: Boolean = false,
    val roleSourceCount: Int? = null, val jobSourceCount: Int? = null) {
    fun json() = JSONObject().put("id", id).put("name", name).put("type", type).put("role", role).put("image", image?.json())
        .put("department", department).put("order", order)
        .put("roles", JSONArray(roles.take(8).map { it.json() })).put("jobs", JSONArray(jobs.take(8).map { it.json() }))
        .put("rolesTruncated", rolesTruncated || roles.size > 8).put("jobsTruncated", jobsTruncated || jobs.size > 8)
        .put("roleSourceCount", roleSourceCount).put("jobSourceCount", jobSourceCount)
}
internal data class LibraryDetails(
    val poster: LibraryArtwork? = null, val backdrop: LibraryArtwork? = null, val logo: LibraryArtwork? = null,
    val ratings: List<LibraryRating> = emptyList(), val people: List<LibraryCredit> = emptyList(),
    val genres: List<String> = emptyList(), val tagline: String? = null, val premiere: String? = null,
    val runtimeMinutes: Int? = null, val officialRating: String? = null,
    val peopleTruncated: Boolean = false, val artworkPending: Boolean = false, val peoplePending: Boolean = false,
    val originalTitle: String? = null, val originalLanguage: String? = null, val status: String? = null,
    val lastAirDate: String? = null, val episodeCount: Int? = null,
    val castSourceCount: Int? = null, val crewSourceCount: Int? = null,
) {
    fun json(): JSONObject = JSONObject().put("schema", 2).put("poster", poster?.json()).put("backdrop", backdrop?.json())
        .put("logo", logo?.json()).put("ratings", JSONArray(ratings.take(8).map { it.json() }))
        .put("people", JSONArray(people.take(MAX_PEOPLE).map { it.json() })).put("genres", JSONArray(genres.take(64)))
        .put("tagline", tagline).put("premiere", premiere).put("runtimeMinutes", runtimeMinutes).put("officialRating", officialRating)
        .put("peopleTruncated", peopleTruncated || people.size > MAX_PEOPLE)
        .put("artworkPending", artworkPending).put("peoplePending", peoplePending)
        .put("originalTitle", originalTitle).put("originalLanguage", originalLanguage).put("status", status)
        .put("lastAirDate", lastAirDate).put("episodeCount", episodeCount)
        .put("castSourceCount", castSourceCount).put("crewSourceCount", crewSourceCount)

    /** Higher-priority source fills fields it actually supplies; no invented empty sections. */
    fun over(fallback: LibraryDetails) = copy(poster = poster ?: fallback.poster, backdrop = backdrop ?: fallback.backdrop,
        logo = logo ?: fallback.logo, ratings = ratings.ifEmpty { fallback.ratings }, people = people.ifEmpty { fallback.people },
        genres = genres.ifEmpty { fallback.genres }, tagline = tagline ?: fallback.tagline, premiere = premiere ?: fallback.premiere,
        runtimeMinutes = runtimeMinutes ?: fallback.runtimeMinutes, officialRating = officialRating ?: fallback.officialRating,
        peopleTruncated = if (people.isEmpty()) fallback.peopleTruncated else peopleTruncated,
        originalTitle = originalTitle ?: fallback.originalTitle, originalLanguage = originalLanguage ?: fallback.originalLanguage,
        status = status ?: fallback.status, lastAirDate = lastAirDate ?: fallback.lastAirDate,
        episodeCount = episodeCount ?: fallback.episodeCount,
        castSourceCount = if (people.isEmpty()) fallback.castSourceCount else castSourceCount,
        crewSourceCount = if (people.isEmpty()) fallback.crewSourceCount else crewSourceCount)

    /** Only a failed auxiliary group retains its last successful snapshot. An empty success clears it. */
    fun retainFailed(previous: LibraryDetails) = copy(
        poster = if (artworkPending) poster ?: previous.poster else poster,
        backdrop = if (artworkPending) backdrop ?: previous.backdrop else backdrop,
        logo = if (artworkPending) logo ?: previous.logo else logo,
        people = if (peoplePending) previous.people else people,
        peopleTruncated = if (peoplePending) previous.peopleTruncated else peopleTruncated,
        castSourceCount = if (peoplePending) previous.castSourceCount else castSourceCount,
        crewSourceCount = if (peoplePending) previous.crewSourceCount else crewSourceCount)

    companion object {
        const val MAX_PEOPLE = 200
        fun parse(raw: JSONObject?): LibraryDetails {
            val j = raw ?: return LibraryDetails()
            fun artwork(key: String) = artwork(j.optJSONObject(key))
            val credits = j.optJSONArray("people")
            fun parts(p: JSONObject, key: String) = p.optJSONArray(key).objects(8).mapNotNull { part ->
                part.clean("name")?.let { LibraryCreditPart(it, part.clean("department"), part.optionalInt("episodes")) }
            }
            return LibraryDetails(artwork("poster"), artwork("backdrop"), artwork("logo"),
                j.optJSONArray("ratings").objects(8).mapNotNull { r ->
                    val value = r.optDouble("value"); val max = r.optDouble("max", 10.0)
                    if (!value.isFinite() || !max.isFinite() || max <= 0 || value !in 0.0..max) null
                    else LibraryRating(r.clean("source").orEmpty(), value, max, r.optLong("votes", -1).takeIf { it >= 0 }, r.clean("metric") ?: "rating")
                }, credits.objects(MAX_PEOPLE).mapNotNull { p -> p.clean("name")?.let {
                    LibraryCredit(p.clean("id").orEmpty(), it, p.clean("type").orEmpty(), p.clean("role", 1024), artwork(p.optJSONObject("image")),
                        p.clean("department"), p.optionalInt("order"), parts(p, "roles"), parts(p, "jobs"),
                        p.optBoolean("rolesTruncated"), p.optBoolean("jobsTruncated"),
                        p.optionalInt("roleSourceCount"), p.optionalInt("jobSourceCount"))
                } }.distinctBy { listOf(it.id, it.name, it.type, it.role) },
                j.optJSONArray("genres").strings(64), j.clean("tagline", 2048), j.clean("premiere"),
                j.optInt("runtimeMinutes").takeIf { it > 0 }, j.clean("officialRating"),
                j.optBoolean("peopleTruncated") || (credits?.length() ?: 0) > MAX_PEOPLE,
                j.optBoolean("artworkPending"), j.optBoolean("peoplePending"),
                j.clean("originalTitle"), j.clean("originalLanguage"), j.clean("status"), j.clean("lastAirDate"),
                j.optionalInt("episodeCount"), j.optionalInt("castSourceCount"), j.optionalInt("crewSourceCount"))
        }
        fun artwork(j: JSONObject?): LibraryArtwork? {
            if (j == null || j.isNull("uri")) return null
            return j.optString("uri").trim().takeIf(String::isNotBlank)?.let { LibraryArtwork(it, j.clean("version")) }
        }
    }
}
internal fun JSONObject.clean(key: String, max: Int = 512): String? = if (isNull(key)) null else optString(key).trim().take(max).takeIf(String::isNotBlank)
internal fun JSONObject.optionalInt(key: String): Int? = if (isNull(key)) null else optInt(key, -1).takeIf { it >= 0 }
internal fun JSONArray?.objects(max: Int): List<JSONObject> = if (this == null) emptyList() else (0 until minOf(length(), max)).mapNotNull { optJSONObject(it) }
internal fun JSONArray?.strings(max: Int): List<String> = if (this == null) emptyList() else (0 until minOf(length(), max)).mapNotNull { if (isNull(it)) null else optString(it).trim().take(512).takeIf(String::isNotBlank) }.distinct()
