package cn.coldlake.stellar.opensource.medialibrary.catalog

import cn.coldlake.stellar.opensource.BuildConfig
import cn.coldlake.stellar.opensource.medialibrary.emby.EmbyClient
import cn.coldlake.stellar.opensource.network.retryAfterMillis
import com.stellar.player.source.ColdLakeHashKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal class MetadataFailure(val reason: String, val retryAt: Long = 0,
    val operation: String = "metadata", val status: Int? = null, val code: String? = null,
    val attempted: Boolean = true) : IOException(reason) {
    val retryable get() = reason in setOf("pending", "rate", "network", "waiting", "retry")
    fun diagnostic() = "$operation:${status ?: 0}:${code.orEmpty()}:$reason"
}
internal interface MetadataTransport {
    suspend fun request(path: String, parameters: Map<String, String>): JSONObject
}
/** No private metadata gateway and no shared API key. Authorization is never logged. */
internal class TmdbMetadataTransport : MetadataTransport, AutoCloseable {
    private val http = HttpClient(OkHttp) {
        followRedirects = false; expectSuccess = false
        engine { config { retryOnConnectionFailure(false); followRedirects(false); followSslRedirects(false) } }
        install(HttpTimeout) { connectTimeoutMillis = 5_000; socketTimeoutMillis = 20_000; requestTimeoutMillis = 25_000 }
    }
    override suspend fun request(path: String, parameters: Map<String, String>): JSONObject {
        val token = BuildConfig.TMDB_READ_ACCESS_TOKEN.trim()
        val apiKey = BuildConfig.TMDB_API_KEY.trim()
        if (token.isEmpty() && apiKey.isEmpty()) throw MetadataFailure("config", attempted = false)
        require(path.matches(Regex("[A-Za-z0-9_/-]+")))
        return http.prepareGet("https://api.themoviedb.org/3/$path") {
            if (token.isNotEmpty()) bearerAuth(token) else parameter("api_key", apiKey)
            accept(ContentType.Application.Json)
            parameters.forEach { (key, value) -> parameter(key, value) }
        }.execute { response ->
            val status = response.status.value
            if (status !in 200..299) {
                val reason = when (status) { 401, 403 -> "config"; 404 -> "missing"; 429 -> "rate"; else -> "network" }
                val now = System.currentTimeMillis()
                val retry = if (status == 429 || status >= 500) now + retryAfterMillis(response.headers["Retry-After"], now, 60_000).coerceIn(1000, 86_400_000) else 0
                throw MetadataFailure(reason, retry, status = status)
            }
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            val channel = response.bodyAsChannel()
            while (true) {
                val n = channel.readAvailable(buffer); if (n < 0) break
                if (out.size() + n > LocalNfo.MAX_BYTES) throw MetadataFailure("limit")
                out.write(buffer, 0, n)
            }
            try { JSONObject(out.toString(Charsets.UTF_8.name())) } catch (_: Exception) { throw MetadataFailure("protocol") }
        }
    }
    override fun close() = http.close()
}
internal data class EpisodeIdentity(val series: String, val seasonId: String, val episodeId: String, val season: Int, val episode: Int)
internal data class MetadataCandidates(val selected: MetadataRecord?, val alternatives: List<MetadataRecord>)

/** Bounded persistent caches and two request slots are shared by scanning and detail browsing. */
internal class MetadataProvider(private val db: CatalogDatabase, private val transport: MetadataTransport,
    private val clock: () -> Long = System::currentTimeMillis) {
    val supportsFileFingerprint = false
    private val locks = Array(32) { Mutex() }
    private val slots = Semaphore(2)
    private fun key(path: String, params: Map<String,String>) = "tmdb|v1|$path|${params.toSortedMap()}"
    private fun cached(path: String, params: Map<String,String>): JSONObject? = db.cachedQuery(key(path, params))?.let {
        runCatching { JSONObject(it.payload) }.getOrNull()
    }
    private suspend fun query(path: String, params: Map<String,String> = emptyMap(), ttl: Long = DAY): JSONObject {
        val key = key(path, params)
        return locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            db.cachedQuery(key)?.takeIf { it.expires > clock() }?.let { return@withLock JSONObject(it.payload) }
            val until = db.providerRetry(RATE_KEY)
            if (until > clock()) throw MetadataFailure("rate", until, attempted = false)
            val value = try { slots.withPermit { EmbyClient.dataSlots.withPermit {
                val retry = db.providerRetry(RATE_KEY)
                if (retry > clock()) throw MetadataFailure("rate", retry, attempted = false)
                try { transport.request(path, params) }
                catch (failure: MetadataFailure) {
                    if (failure.reason == "rate") db.providerRetry(RATE_KEY, failure.retryAt)
                    throw failure
                }
            } } } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: MetadataFailure) { throw failure }
              catch (_: IOException) { throw MetadataFailure("network", clock() + 60_000) }
            db.cacheQuery(key, value.toString(), clock() + ttl)
            value
        }
    }
    suspend fun search(q: NameQuery, locale: String, scope: String = ""): MetadataCandidates {
        if (q.order != "aired") throw MetadataFailure("order")
        val id = q.explicitId ?: if (q.season == null) q.ids["tmdb"] else q.seriesIds["tmdb"]
        if (id != null) {
            if (!id.matches(Regex("[1-9][0-9]*"))) throw MetadataFailure("identity")
            return MetadataCandidates(details("${q.kind}-$id", locale, q.kind), emptyList())
        }
        val imdb = if (q.season == null) q.ids["imdb"] else q.seriesIds["imdb"]
        if (imdb != null) {
            if (!imdb.matches(Regex("tt[0-9]+"))) throw MetadataFailure("identity")
            val raw = query("find/$imdb", mapOf("external_source" to "imdb_id", "language" to locale))
            val items = raw.optJSONArray(if (q.kind == "movie") "movie_results" else "tv_results").objects(5)
                .map { record(it, q.kind, locale) }
            return MetadataCandidates(items.singleOrNull(), items)
        }
        if (q.title.isBlank()) return MetadataCandidates(null, emptyList())
        val params = buildMap {
            put("query", q.title); put("language", locale); put("include_adult", "false")
            q.year?.let { put(if (q.kind == "movie") "year" else "first_air_date_year", it.toString()) }
        }
        val list = query("search/${if (q.kind == "movie") "movie" else "tv"}", params, 7 * DAY)
            .optJSONArray("results").objects(20).map { record(it, q.kind, locale) }
        return MetadataCandidates(MediaName.choose(q, list), list.take(5))
    }
    suspend fun searchFile(q: NameQuery, path: String, locale: String, scope: String,
        fingerprint: ColdLakeHashKey? = null, ignoreFingerprint: Boolean = false) = search(q, locale, scope)
    private fun endpoint(id: String): String {
        val p = owned(id).split('-')
        return when (p[0]) {
            "movie" -> "movie/${p[1]}"
            "series" -> "tv/${p[1]}"
            "season" -> "tv/${p[1]}/season/${p[2]}"
            "episode" -> "tv/${p[1]}/season/${p[2]}/episode/${p[3]}"
            "person" -> "person/${p[1]}"
            else -> throw MetadataFailure("kind")
        }
    }
    private fun detailParams(locale: String) = mapOf("language" to locale, "append_to_response" to "credits,external_ids,images", "include_image_language" to "${locale.substringBefore('-')},en,null")
    suspend fun details(id: String, locale: String, expectedKind: String? = null, expected: EpisodeIdentity? = null): MetadataRecord {
        val p = owned(id).split('-'); val kind = p[0]
        if (expectedKind != null && kind != expectedKind) throw MetadataFailure("kind")
        val raw = query(endpoint(id), detailParams(locale))
        if (kind in setOf("movie", "series") && raw.optLong("id").toString() != p[1]) throw MetadataFailure("identity")
        if (kind == "season" && raw.optInt("season_number", -1) != p[2].toInt() ||
            kind == "episode" && (raw.optInt("season_number", -1) != p[2].toInt() || raw.optInt("episode_number", -1) != p[3].toInt())) throw MetadataFailure("coordinates")
        if (expected != null && id != expected.episodeId) throw MetadataFailure("coordinates")
        return record(raw, kind, locale, id)
    }
    fun cachedDetails(id: String, locale: String, kind: String): MetadataRecord? = runCatching {
        cached(endpoint(id), detailParams(locale))?.let { record(it, kind, locale, id) }
    }.getOrNull()
    suspend fun resolve(q: NameQuery, candidate: MetadataRecord, locale: String,
        primary: suspend (MetadataRecord) -> Unit = {}): MetadataRecord {
        if (q.order != "aired") throw MetadataFailure("order")
        val id = candidate.ids["catalog"] ?: throw MetadataFailure("identity")
        if (q.season == null) return details(id, locale, "movie").also {
            q.ids["imdb"]?.let { expected -> verifyImdb(it, expected) }; primary(it)
        }
        if (candidate.kind != "series" || q.episodes.isEmpty() || q.episodes.size > 21) throw MetadataFailure("coordinates")
        val series = details(id, locale, "series")
        q.seriesIds["imdb"]?.let { verifyImdb(series, it) }
        val seriesNumber = id.substringAfter('-')
        val season = details("season-$seriesNumber-${q.season}", locale, "season")
        val episodes = q.episodes.map { details("episode-$seriesNumber-${q.season}-$it", locale, "episode") }
        q.ids["imdb"]?.let { expected -> episodes.forEach { verifyImdb(it, expected) } }
        val result = episodes.first().copy(identity = episodes.joinToString("+") { it.identity },
            title = episodes.joinToString(" / ") { it.title }, plot = episodes.mapNotNull { it.plot }.joinToString("\n").takeIf(String::isNotBlank),
            show = series.title, episodes = q.episodes, poster = series.poster ?: season.poster ?: episodes.first().poster,
            seriesDetails = series.details, publicParents = listOf(series, season))
        primary(result)
        return result
    }
    suspend fun cards(ids: List<String>, locale: String): JSONObject {
        require(ids.size in 1..50)
        val items = JSONArray()
        for (id in ids.distinct()) {
            val item = JSONObject().put("object_id", owned(id))
            try { item.put("state", "ready").put("card", JSONObject(details(id, locale).json())) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: MetadataFailure) { if (failure.reason != "missing") throw failure; item.put("state", "not_found") }
            items.put(item)
        }
        return JSONObject().put("items", items)
    }
    private fun childrenResult(id: String, kind: String, locale: String, cursor: String?, raw: JSONObject): JSONObject {
        val array = raw.optJSONArray(if (kind == "series") "seasons" else "episodes") ?: JSONArray()
        val offset = cursor?.toIntOrNull() ?: 0
        if (offset < 0 || offset > array.length()) throw MetadataFailure("request")
        val p = owned(id).split('-'); val items = JSONArray()
        for (n in offset until minOf(offset + 20, array.length())) {
            val child = array.getJSONObject(n)
            val childId = if (kind == "series") "season-${p[1]}-${child.coordinate("season_number")}" else "episode-${p[1]}-${p[2]}-${child.coordinate("episode_number")}"
            items.put(JSONObject(record(child, if (kind == "series") "season" else "episode", locale, childId).json()))
        }
        return JSONObject().put("owner_id", id).put("state", "ready").put("items", items)
            .put("has_more", offset + 20 < array.length()).put("next_cursor", (offset + 20).toString())
    }
    suspend fun children(id: String, kind: String, locale: String, cursor: String? = null): JSONObject {
        require(kind in setOf("series", "season") && id.startsWith("$kind-"))
        return childrenResult(id, kind, locale, cursor, query(endpoint(id), mapOf("language" to locale)))
    }
    fun cachedChildren(id: String, kind: String, locale: String, cursor: String?): LibraryChildren? = runCatching {
        cached(endpoint(id), mapOf("language" to locale))?.let { LibraryChildren.parse(childrenResult(id, kind, locale, cursor, it), id, kind) }
    }.getOrNull()
    private fun personRecord(id: String, raw: JSONObject): LibraryPersonData {
        if (id != "person-${raw.optLong("id")}") throw MetadataFailure("identity")
        return LibraryPersonData(id, raw.getString("name"), raw.clean("biography", 32768), raw.clean("birthday"), raw.clean("deathday"), raw.clean("place_of_birth"))
    }
    suspend fun person(id: String, locale: String): LibraryPersonData {
        require(id.startsWith("person-")); return personRecord(id, query(endpoint(id), mapOf("language" to locale)))
    }
    fun cachedPerson(id: String, locale: String): LibraryPersonData? = runCatching {
        cached(endpoint(id), mapOf("language" to locale))?.let { personRecord(id, it) }
    }.getOrNull()
    private fun filmographyRecord(raw: JSONObject, locale: String): LibraryFilmography {
        val items = (raw.optJSONArray("cast").objects(1000) + raw.optJSONArray("crew").objects(1000))
            .filter { it.optString("media_type") in setOf("movie", "tv") }
            .distinctBy { it.optString("media_type") to it.optLong("id") }
            .sortedByDescending { it.optDouble("popularity", 0.0) }
        return LibraryFilmography(items.take(40).map { record(it, if (it.optString("media_type") == "tv") "series" else "movie", locale) }, items.size > 40)
    }
    suspend fun filmography(id: String, locale: String): LibraryFilmography {
        require(id.startsWith("person-")); return filmographyRecord(query("${endpoint(id)}/combined_credits", mapOf("language" to locale)), locale)
    }
    fun cachedFilmography(id: String, locale: String): LibraryFilmography? = runCatching {
        cached("${endpoint(id)}/combined_credits", mapOf("language" to locale))?.let { filmographyRecord(it, locale) }
    }.getOrNull()
    fun invalidateSeries(id: String) = db.invalidateMetadataIdentity(owned(id))
    fun acceptSeries(record: MetadataRecord) = Unit
    suspend fun artwork(id: String): String = throw MetadataFailure("missing")
    suspend fun verifyImdb(record: MetadataRecord, expected: String) {
        if (record.ids["imdb"] != expected) throw MetadataFailure("id-conflict")
    }
    private suspend fun ratingsRecord(target: RatingsTarget): MetadataRecord? {
        target.entity?.let { return details(it, "en-US", target.kind) }
        val imdb = target.imdb ?: return null
        if (target.kind == "episode") return null
        val found = query("find/$imdb", mapOf("external_source" to "imdb_id"))
            .optJSONArray(if (target.kind == "movie") "movie_results" else "tv_results").objects(2)
        return found.singleOrNull()?.let { record(it, target.kind, "en-US") }
    }
    suspend fun cachedRatings(target: RatingsTarget): List<LibraryRating>? = db.cachedQuery(target.key)?.let {
        runCatching { LibraryDetails.parse(JSONObject(it.payload)).ratings }.getOrNull()
    }
    suspend fun ratings(target: RatingsTarget): List<LibraryRating> {
        db.cachedQuery(target.key)?.takeIf { it.expires > clock() }?.let { return LibraryDetails.parse(JSONObject(it.payload)).ratings }
        val record = if (target.entity != null || BuildConfig.TRAKT_API_KEY.isBlank()) ratingsRecord(target) else null
        val coordinates = target.entity?.split('-')
        val series = if (target.kind in setOf("episode", "season") && coordinates != null)
            details("series-${coordinates[1]}", "en-US", "series") else null
        // Trakt episode routes use the show's IMDb ID, never the episode's IMDb ID.
        val imdb = target.imdb ?: series?.ids?.get("imdb") ?: record?.ids?.get("imdb")
        val season = target.season ?: coordinates?.getOrNull(2)?.toInt()
        val episode = target.episode ?: coordinates?.getOrNull(3)?.toInt()
        val list = if (imdb != null && BuildConfig.TRAKT_API_KEY.isNotBlank()) {
            val rate = db.providerRetry("trakt|rate")
            if (rate > clock()) throw MetadataFailure("rate", rate, attempted = false)
            try { slots.withPermit { TraktRatings.fetch(target.kind, imdb, season, episode) } }
            catch (failure: MetadataFailure) {
                if (failure.reason == "rate") db.providerRetry("trakt|rate", failure.retryAt)
                throw failure
            }
        } else record?.details?.ratings.orEmpty()
        db.cacheQuery(target.key, LibraryDetails(ratings = list).json().toString(), clock() + DAY)
        return list
    }
    companion object {
        const val DAY = 86_400_000L
        const val RATE_KEY = "tmdb|rate|1"
        fun owned(id: String): String {
            if (!id.matches(Regex("(?:movie|series|person)-[1-9][0-9]*|season-[1-9][0-9]*-[0-9]+|episode-[1-9][0-9]*-[0-9]+-[0-9]+"))) throw MetadataFailure("identity")
            return id
        }
        private fun image(path: String?, width: Int): LibraryArtwork? = path?.takeIf { it.matches(Regex("/[A-Za-z0-9._-]+")) }
            ?.let { LibraryArtwork("https://image.tmdb.org/t/p/w$width$it", it) }
        private fun record(raw: JSONObject, kind: String, locale: String, identity: String? = null): MetadataRecord {
            val id = owned(identity ?: "$kind-${raw.getLong("id")}"); val parts = id.split('-')
            val season = parts.getOrNull(2)?.toInt(); val episode = parts.getOrNull(3)?.toInt()
            val date = raw.clean("release_date") ?: raw.clean("first_air_date") ?: raw.clean("air_date")
            val poster = image(raw.clean(if (kind == "episode") "still_path" else "poster_path"), if (kind == "episode") 300 else 500)
            val credits = raw.optJSONObject("credits")
            val cast = credits?.optJSONArray("cast").objects(12); val crew = credits?.optJSONArray("crew").objects(12)
            val people = (cast.map { it to "cast" } + crew.map { it to "crew" }).take(12).mapNotNull { (person, type) ->
                if (person.optLong("id") <= 0) null else LibraryCredit("person-${person.getLong("id")}", person.optString("name"), type,
                    person.clean(if (type == "cast") "character" else "job"), image(person.clean("profile_path"), 185),
                    person.clean("department"), person.optionalInt("order"))
            }
            val votes = raw.optLong("vote_count"); val value = raw.optDouble("vote_average")
            val rating = value.takeIf { it.isFinite() && it > 0 && it <= 10 && votes > 0 }?.let { LibraryRating("tmdb", it, votes = votes) }
            val imdb = raw.clean("imdb_id") ?: raw.optJSONObject("external_ids")?.clean("imdb_id")
            val logo = raw.optJSONObject("images")?.optJSONArray("logos").objects(20).firstOrNull()?.clean("file_path")
            val details = LibraryDetails(poster = poster, backdrop = image(raw.clean("backdrop_path"), 1280), logo = image(logo, 500),
                ratings = listOfNotNull(rating), people = people, genres = raw.optJSONArray("genres").objects(64).mapNotNull { it.clean("name") },
                tagline = raw.clean("tagline", 2048), premiere = date,
                runtimeMinutes = raw.optionalInt("runtime") ?: raw.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 },
                originalTitle = raw.clean("original_title") ?: raw.clean("original_name"), originalLanguage = raw.clean("original_language"),
                status = raw.clean("status"), lastAirDate = raw.clean("last_air_date"), episodeCount = raw.optionalInt("number_of_episodes") ?: raw.optionalInt("episode_count"),
                castSourceCount = credits?.optJSONArray("cast")?.length(), crewSourceCount = credits?.optJSONArray("crew")?.length(),
                peopleTruncated = (credits?.optJSONArray("cast")?.length() ?: 0) + (credits?.optJSONArray("crew")?.length() ?: 0) > people.size)
            return MetadataRecord("catalog:$kind:$id", kind, raw.clean("title") ?: raw.clean("name") ?: throw MetadataFailure("protocol"),
                date?.take(4)?.toIntOrNull(), raw.clean("overview", 32768), season = season, episodes = listOfNotNull(episode),
                ids = buildMap { put("catalog", id); put("tmdb", raw.getLong("id").toString()); imdb?.let { put("imdb", it) }
                    if (season != null) put("catalog-series", "series-${parts[1]}")
                    if (episode != null) put("catalog-season", "season-${parts[1]}-${parts[2]}") },
                poster = poster?.uri, locale = locale, version = "tmdb-v1", seriesIdentity = if (season != null) "catalog:series:series-${parts[1]}" else null,
                details = details)
        }
    }
}
internal fun JSONObject.coordinate(key: String): Int {
    val value = get(key) as? Number ?: throw MetadataFailure("coordinates")
    val number = value.toLong()
    if (number !in 0..Int.MAX_VALUE || value.toDouble() != number.toDouble()) throw MetadataFailure("coordinates")
    return number.toInt()
}
