package cn.coldlake.stellar.opensource.medialibrary.catalog

import cn.coldlake.stellar.opensource.medialibrary.emby.EmbyClient
import cn.coldlake.stellar.opensource.medialibrary.servers.ServerProfile
import kotlinx.coroutines.*
import org.json.JSONObject

/** Imported identities remain namespaced by server and user. No path guessing or server writes. */
internal class ServerCatalogImporter(private val db: CatalogDatabase) {
    suspend fun run(profile: ServerProfile, library: String, page: suspend (Int) -> JSONObject,
        progress: suspend (Int) -> Unit = {}): String {
        val scope = scope(profile, library)
        val generation = db.importStart(scope, profile.name)
        var complete = false; var start = 0; var expected: Int? = null
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val raw = page(start); val items = raw.getJSONArray("Items")
                val total = raw.getInt("TotalRecordCount")
                if (items.length() > 128 || total < start + items.length() || (expected != null && expected != total)) break
                expected = total
                val records = (0 until items.length()).map { record(profile, items.getJSONObject(it)) }
                if (!db.importPage(scope, generation, records)) break
                start += items.length(); progress(start)
                if (start == total) { complete = true; break }
                if (items.length() == 0) break
            }
        } finally { withContext(NonCancellable) { db.importFinish(scope, generation, complete) } }
        return if (complete) "complete" else "partial"
    }
    companion object {
        fun scope(p: ServerProfile, library: String) = "${p.id}|${p.embyServerId}|${p.embyUserId}|${p.updatedAt}|$library"
        fun record(p: ServerProfile, j: JSONObject): MetadataRecord {
            val id = j.getString("Id"); val kind = j.getString("Type").lowercase(java.util.Locale.ROOT)
            require(kind in setOf("movie", "series", "season", "episode"))
            fun number(key: String) = if (j.isNull(key)) null else j.getInt(key)
            val providers = j.optJSONObject("ProviderIds") ?: JSONObject()
            val ids = providers.keys().asSequence().map { it.lowercase(java.util.Locale.ROOT) to providers.getString(it) }.toMap()
            val season = if (kind == "season") number("IndexNumber") else number("ParentIndexNumber")
            val episodes = if (kind == "episode") number("IndexNumber")?.let(::listOf).orEmpty() else emptyList()
            if (kind == "episode" && (season == null || episodes.isEmpty())) throw MetadataFailure("coordinates")
            val imageTag = j.optJSONObject("ImageTags")?.optString("Primary")?.takeIf(String::isNotBlank)
            fun image(owner: String, type: String, tag: String?) = tag?.takeIf(String::isNotBlank)?.let {
                LibraryArtwork("server-artwork:${p.id}:${p.embyServerId}:${p.embyUserId}:${p.updatedAt}:$owner:$type/$it", it)
            }
            val people = j.optJSONArray("People")
            val details = LibraryDetails(
                poster = image(id, "Primary", imageTag),
                backdrop = image(id, "Backdrop", j.optJSONArray("BackdropImageTags")?.optString(0)),
                logo = image(id, "Logo", j.optJSONObject("ImageTags")?.optString("Logo")),
                ratings = listOfNotNull(
                    j.optDouble("CommunityRating").takeIf { it.isFinite() && it in 0.0..10.0 }?.let { LibraryRating("community", it) },
                    j.optDouble("CriticRating").takeIf { it.isFinite() && it in 0.0..100.0 }?.let { LibraryRating("critic", it, 100.0) }),
                people = people.objects(LibraryDetails.MAX_PEOPLE).mapNotNull { person -> person.clean("Name")?.let { name ->
                    LibraryCredit(person.clean("Id").orEmpty(), name, person.clean("Type").orEmpty(), person.clean("Role"),
                        person.clean("Id")?.let { image(it, "Primary", person.clean("PrimaryImageTag")) })
                } }, peopleTruncated = (people?.length() ?: 0) > LibraryDetails.MAX_PEOPLE,
                genres = j.optJSONArray("Genres").strings(64), tagline = j.optJSONArray("Taglines").strings(1).firstOrNull(),
                premiere = j.clean("PremiereDate"), runtimeMinutes = (j.optLong("RunTimeTicks") / 600_000_000).toInt().takeIf { it > 0 },
                officialRating = j.clean("OfficialRating"), originalTitle = j.clean("OriginalTitle"),
                status = j.clean("Status"), lastAirDate = j.clean("EndDate"),
                episodeCount = if (kind == "season") j.optionalInt("ChildCount") else null)
            return MetadataRecord("server:${p.id}:${p.embyServerId}:${p.embyUserId}:$id", kind, j.getString("Name"),
                number("ProductionYear"), j.optString("Overview").takeIf(String::isNotBlank), j.optString("SeriesName").takeIf(String::isNotBlank),
                season, episodes, ids, imageTag?.let { "server-artwork:${p.id}:${p.embyServerId}:${p.embyUserId}:${p.updatedAt}:$id:$it" },
                "und", j.optString("DateCreated") + ":" + imageTag.orEmpty(),
                seriesIdentity = j.optString("SeriesId").takeIf(String::isNotBlank)?.let { "server:${p.id}:${p.embyServerId}:${p.embyUserId}:$it" },
                playback = if (kind in setOf("movie", "episode")) CatalogServerLocator.create(p, id) else null,
                resumeMs = (j.optJSONObject("UserData")?.optLong("PlaybackPositionTicks") ?: 0) / 10_000,
                durationMs = j.optLong("RunTimeTicks") / 10_000,
                watched = j.optJSONObject("UserData")?.optBoolean("Played") ?: false,
                lastPlayed = j.optJSONObject("UserData")?.optString("LastPlayedDate")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0,
                details = details)
        }
    }
}
