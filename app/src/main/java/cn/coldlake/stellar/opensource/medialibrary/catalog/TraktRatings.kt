package cn.coldlake.stellar.opensource.medialibrary.catalog

import cn.coldlake.stellar.opensource.BuildConfig
import cn.coldlake.stellar.opensource.network.retryAfterMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Public aggregate ratings only; no user account, watch history, or Trakt OAuth. */
internal object TraktRatings {
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    suspend fun fetch(kind: String, imdb: String, season: Int? = null, episode: Int? = null): List<LibraryRating> = withContext(Dispatchers.IO) {
        require(kind in setOf("movie", "series", "season", "episode") && imdb.matches(Regex("tt[0-9]{7,10}")))
        if (BuildConfig.TRAKT_API_KEY.isBlank()) return@withContext emptyList()
        val route = when (kind) {
            "movie" -> "movies/$imdb"
            "series" -> "shows/$imdb"
            "season" -> { require(season != null && season >= 0); "shows/$imdb/seasons/$season" }
            else -> {
                require(season != null && season >= 0 && episode != null && episode > 0)
                "shows/$imdb/seasons/$season/episodes/$episode"
            }
        }
        val request = Request.Builder().url("https://apiz.trakt.tv/$route/ratings?extended=all")
            .header("trakt-api-key", BuildConfig.TRAKT_API_KEY).header("trakt-api-version", "2")
            .header("User-Agent", "StellarPlayer/1.0 (Android; open-source)")
            .header("Content-Type", "application/json").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val reason = when(response.code) { 429 -> "rate"; 401, 403 -> "config"; 404 -> "missing"; else -> "network" }
                val now = System.currentTimeMillis()
                throw MetadataFailure(reason, if (response.code == 429) now + retryAfterMillis(response.header("Retry-After"), now, 60_000) else 0,
                    operation = "ratings", status = response.code)
            }
            val source = response.body.source()
            if (source.request(64 * 1024 + 1L)) throw MetadataFailure("limit", operation = "ratings")
            parse(JSONObject(source.readUtf8()), kind)
        }
    }
    internal fun parse(raw: JSONObject, kind: String): List<LibraryRating> = buildList {
        fun addRating(source: String, field: String = "rating", max: Double = 10.0, metric: String = "rating") {
            val item = raw.optJSONObject(source) ?: return
            val number = item.opt(field) as? Number ?: return
            val value = number.toDouble()
            if (!value.isFinite() || value !in 0.0..max) return
            val votes = (item.opt("votes") as? Number)?.toLong()?.takeIf { it >= 0 }
            if (votes == 0L) return
            add(LibraryRating(source, value, max, votes, metric))
        }
        addRating("tmdb"); addRating("imdb"); addRating("trakt")
        addRating("metascore", max = 100.0)
        addRating("rotten_tomatoes", max = 100.0, metric = "tomatometer")
        addRating("rotten_tomatoes", field = "user_rating", max = 100.0, metric = "audience")
        addRating("mal")
        if (kind == "movie") addRating("letterboxd", max = 5.0)
    }
}
