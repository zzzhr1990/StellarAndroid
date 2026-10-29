package cn.coldlake.stellar.opensource.medialibrary.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Never resolve a title or use an episode IMDb ID as the identity of a series. */
internal data class RatingsTarget private constructor(
    val kind: String, val imdb: String?, val entity: String?, val season: Int? = null, val episode: Int? = null,
) {
    val key get() = "trakt|ratings|2|$kind|${imdb ?: entity}|$season|$episode"
    companion object {
        private fun validImdb(id: String) = id.matches(Regex("tt[0-9]{7,10}")) && id.drop(2).any { it != '0' }
        fun imdb(kind: String, id: String): RatingsTarget? = if (kind in setOf("movie", "series") && validImdb(id))
            RatingsTarget(kind, id, null) else null
        fun episode(seriesImdb: String, season: Int?, episode: Int?): RatingsTarget? =
            if (validImdb(seriesImdb) && season != null && season >= 0 && episode != null && episode > 0)
                RatingsTarget("episode", seriesImdb, null, season, episode) else null
        fun entity(kind: String, id: String): RatingsTarget? = if (kind in setOf("movie", "series", "season", "episode") &&
            id.startsWith("$kind-") && runCatching { MetadataProvider.owned(id) }.isSuccess) RatingsTarget(kind, null, id) else null
    }
}

/** Cached content first; cold-fill polling is bounded and follows the service's Retry-After. */
internal suspend fun MetadataProvider.loadRatings(target: RatingsTarget, publish: suspend (List<LibraryRating>) -> Unit) {
    withContext(Dispatchers.IO) { cachedRatings(target) }?.let { publish(it) }
    repeat(4) { attempt ->
        try {
            publish(withContext(Dispatchers.IO) { ratings(target) })
            return
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val error = failure as? MetadataFailure ?: return
            val wait = error.retryAt - System.currentTimeMillis()
            if (attempt == 3 || error.reason !in setOf("pending", "waiting") || wait !in 1..10_000) return
            delay(wait)
        }
    }
}
