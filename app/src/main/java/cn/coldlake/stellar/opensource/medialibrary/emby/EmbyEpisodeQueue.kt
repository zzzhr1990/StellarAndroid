package cn.coldlake.stellar.opensource.medialibrary.emby

import com.stellar.player.contract.MediaDescriptor
import com.stellar.player.contract.MediaKind
import cn.coldlake.stellar.opensource.cache.AppCaches
import cn.coldlake.stellar.opensource.cache.CachedMedia
import cn.coldlake.stellar.opensource.cache.cacheNamespace
import cn.coldlake.stellar.opensource.medialibrary.servers.ServerError
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID

/** Metadata only: each selected episode negotiates its own stream and reporting session. */
internal class EmbyEpisodeQueue(source: EmbyPlayback) {
    private val profile = source.profile
    private val anchor = source.item
    private val verifyTls = source.verifyTlsCertificates
    private val reportError = source.onReportError
    private val prefix = "emby-session:${UUID.randomUUID()}:"
    private val ids = mutableMapOf(anchor.id to source.id)
    private val removed = mutableSetOf<String>()
    private var items = ordered(source.episodeSiblings + anchor)
    val media get() = items.filterNot { id(it) in removed }.map {
        MediaDescriptor(id(it), episodeTitle(it), MediaKind.VIDEO, it.durationMs)
    }

    private fun id(item: EmbyItem) = ids.getOrPut(item.id) { "$prefix${item.id}" }
    private fun ordered(values: List<EmbyItem>) = values.filter {
        it.id == anchor.id || (it.type == "Episode" && it.playable && it.seriesId == anchor.seriesId &&
            when {
                anchor.seasonId.isNotEmpty() -> it.seasonId == anchor.seasonId
                anchor.seasonIndex != null -> it.seasonIndex == anchor.seasonIndex
                else -> true
            })
    }.distinctBy { it.id }.let { matches ->
        if (matches.size <= 2048) matches else matches.filterNot { it.id == anchor.id }.take(2047) + anchor
    }.sortedWith(compareBy<EmbyItem> { it.seasonIndex ?: Int.MAX_VALUE }
        .thenBy { it.index ?: Int.MAX_VALUE }.thenBy { it.id })

    fun remove(mediaId: String) { removed += mediaId }
    fun contains(mediaId: String) = mediaId !in removed && items.any { id(it) == mediaId }

    suspend fun load(publish: () -> Unit) {
        EmbyClient(profile, verifyTls).use { api ->
            val repo = CachedMedia(api, AppCaches.current?.data) { java.util.Locale.getDefault().toLanguageTag() }
            var start = 0
            val collected = mutableListOf<EmbyItem>()
            do {
                val page = repo.episodes(anchor.seriesId, anchor.seasonId, start = start, publish = { delivery ->
                    currentCoroutineContext().ensureActive()
                    // Keep cached/previously displayed entries if a later page fails.
                    items = ordered(items + delivery.value.items + anchor)
                    publish()
                })
                if (page.items.isEmpty()) break
                val previousSize = collected.size
                collected += page.items.filterNot { candidate -> collected.any { it.id == candidate.id } }
                if (collected.size == previousSize) throw EmbyFailure(ServerError.PROTOCOL)
                start += page.items.size
                if (start >= page.total) break
                // Same metadata budget as the existing catalog query bound; never fetch without a limit.
                if (start >= 2048) throw EmbyFailure(ServerError.PROTOCOL)
            } while (true)
            items = ordered(collected + anchor)
            publish()
        }
    }

    suspend fun prepare(mediaId: String): EmbyPlayback {
        val item = items.first { id(it) == mediaId && mediaId !in removed }
        EmbyClient(profile, verifyTls).use { api ->
            val detail = api.detail(item.id)
            val initial = api.playbackInfo(detail)
            val chosen = initial.versions.firstOrNull { it.streamable }?.id ?: throw EmbyFailure(ServerError.PROTOCOL)
            val info = api.playbackInfo(detail, chosen)
            val version = info.versions.firstOrNull { it.id == chosen && it.streamable } ?: throw EmbyFailure(ServerError.PROTOCOL)
            currentCoroutineContext().ensureActive()
            return EmbyPlayback(profile, detail, version, info.sessionId, 0, verifyTls, mediaId, reportError)
        }
    }
}

internal fun episodeTitle(item: EmbyItem): String =
    if (item.type == "Episode" && item.seasonIndex != null && item.index != null)
        "S${item.seasonIndex.toString().padStart(2, '0')}E${item.index.toString().padStart(2, '0')} · ${item.name}"
    else item.name
