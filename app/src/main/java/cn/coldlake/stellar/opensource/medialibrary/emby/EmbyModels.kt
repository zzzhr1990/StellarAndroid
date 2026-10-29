package cn.coldlake.stellar.opensource.medialibrary.emby

import cn.coldlake.stellar.opensource.medialibrary.servers.ServerError
import java.io.IOException
import java.net.URI

internal class EmbyFailure(val reason: ServerError, val httpStatus: Int? = null) : IOException(reason.name)

internal data class EmbyItem(
    val id: String, val name: String, val type: String, val folder: Boolean,
    val audio: Boolean = false, val overview: String = "", val year: Int? = null,
    val durationMs: Long? = null, val resumeMs: Long = 0, val played: Boolean = false,
    val imageTag: String = "", val imageId: String = id,
    val backdropTags: List<String> = emptyList(), val backdropId: String = id,
    val logoTag: String = "", val logoId: String = id,
    val rating: Double? = null, val officialRating: String = "", val endYear: Int? = null,
    val status: String = "", val favorite: Boolean = false, val unplayed: Int = 0,
    val genres: List<String> = emptyList(), val tags: List<String> = emptyList(),
    val studios: List<EmbyNamed> = emptyList(), val people: List<EmbyPerson> = emptyList(),
    val tagline: String = "", val premiere: String = "", val birthPlaces: List<String> = emptyList(),
    val seriesId: String = "", val seriesName: String = "", val seasonId: String = "",
    val index: Int? = null, val seasonIndex: Int? = null, val seasonCount: Int = 0,
    val collectionType: String = "", val links: List<EmbyLink> = emptyList(),
    val chapters: List<EmbyChapter> = emptyList(), val sources: List<EmbySource> = emptyList(),
    val imageAspectRatio: Double? = null, val userStateKnown: Boolean = true,
    val criticRating: Double? = null,
    val lastPlayedDate: String = "",
    val progressPending: Boolean = false,
    val imdbId: String = "",
    val dateCreated: String = "",
) {
    val playable get() = !folder && (audio || type in setOf("Movie", "Episode", "Video", "MusicVideo", "Trailer"))
    val details get() = playable || type in setOf("Series", "Season", "Person", "BoxSet", "MusicAlbum")
}
internal data class EmbyNamed(val id: String, val name: String)
internal data class EmbyPerson(val id: String, val name: String, val role: String, val type: String, val imageTag: String) {
    fun asItem() = EmbyItem(id, name, "Person", false, imageTag = imageTag)
}
internal data class EmbyLink(val name: String, val url: String)
internal data class EmbyChapter(val name: String, val positionMs: Long, val imageTag: String, val index: Int)
internal data class EmbyTrack(val index: Int, val type: String, val title: String, val language: String,
    val codec: String, val external: Boolean, val width: Int, val height: Int, val channels: Int,
    val bitrate: Long, val bitDepth: Int, val frameRate: Double, val pixelFormat: String,
    val videoRange: String = "", val videoRangeType: String = "", val extendedVideoType: String = "",
    val colorTransfer: String = "", val dvProfile: Int = 0)
internal data class EmbySource(val id: String, val name: String, val container: String, val size: Long,
    val bitrate: Long, val tracks: List<EmbyTrack>, val fileName: String = "")

/** Keep only a display basename; signed stream URLs and server paths never enter the cache. */
internal fun embyDisplayFileName(path: String, name: String, container: String): String {
    val localPath = if ("://" in path) runCatching { URI(path).path }.getOrNull().orEmpty() else path
    val basename = localPath.replace('\\', '/').substringAfterLast('/').takeIf { it.isNotBlank() }
    if (basename != null) return basename
    val extension = container.takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) }.orEmpty()
    return if (name.isNotBlank() && extension.isNotBlank() && !name.endsWith(".$extension", ignoreCase = true)) "$name.$extension" else name
}
internal data class EmbyShelf(val title: String = "", val label: String = "", val items: List<EmbyItem>,
    val location: EmbyLocation? = null, val wide: Boolean = false, val loaded: Boolean = true, val error: Boolean = false) {
    val key get() = "${location?.section}:${location?.parent?.id}:$label"
}
internal data class EmbyFilters(val genres: List<String> = emptyList(), val years: List<String> = emptyList(),
    val studios: List<String> = emptyList(), val tags: List<String> = emptyList())
internal data class EmbyPage(val items: List<EmbyItem>, val total: Int, val nextIndex: Int)
internal data class EmbyVersion(val id: String, val name: String, val durationMs: Long?, val streamable: Boolean, val playMethod: String = "DirectStream")
internal data class EmbyPlaybackInfo(val sessionId: String, val versions: List<EmbyVersion>)
internal enum class EmbySection { LIBRARIES, RESUME, LATEST, SEARCH, FOLDER, FAVORITES, GENRES, TAGS, COLLECTIONS, DIRECTORIES, RELATED }
internal data class EmbyLocation(val section: EmbySection = EmbySection.LIBRARIES,
    val parent: EmbyItem? = null, val query: String = "", val title: String = "",
    val sort: String = "DateCreated", val descending: Boolean = true,
    val genre: String = "", val tag: String = "", val studio: String = "", val person: String = "",
    val year: String = "", val played: Boolean? = null, val favoriteOnly: Boolean = false,
    val itemTypes: String = "", val recursive: Boolean = false)

internal fun embyBase(address: String): String = mediaServerBase(address, cn.coldlake.stellar.opensource.medialibrary.servers.ServerKind.EMBY)

internal fun mediaServerBase(address: String, kind: cn.coldlake.stellar.opensource.medialibrary.servers.ServerKind): String {
    val uri = try { URI(address.trim()) } catch (_: Exception) { throw EmbyFailure(ServerError.INPUT) }
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty() ||
        uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
        uri.port != -1 && uri.port !in 1..65535 || uri.path.split('/').any { it in setOf(".", "..") })
        throw EmbyFailure(ServerError.INPUT)
    val base = address.trim().trimEnd('/')
    if (kind == cn.coldlake.stellar.opensource.medialibrary.servers.ServerKind.JELLYFIN) return base
    return if (uri.path.trimEnd('/').endsWith("/emby", ignoreCase = true)) base else "$base/emby"
}

internal fun millisecondsToTicks(value: Long): Long = value.coerceIn(0, Long.MAX_VALUE / 10_000) * 10_000
internal fun ticksToMilliseconds(value: Long): Long = value.coerceAtLeast(0) / 10_000
