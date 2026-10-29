package cn.coldlake.stellar.opensource.medialibrary.catalog

import cn.coldlake.stellar.opensource.medialibrary.emby.EmbyItem

/** Shared detail presentation. IDs are always scoped to their source. */
internal data class MediaPresentationData(
    val key: String,
    val kind: String,
    val title: String,
    val overview: String?,
    val year: Int?,
    val metadataDurationMs: Long?,
    val images: Map<String, MediaPresentationImage>,
    val people: List<LibraryCredit>,
    val genres: List<String>,
    val rating: LibraryRating?,
    val versions: List<MediaPresentationVersion> = emptyList(),
    val season: Int? = null,
    val episode: Int? = null,
    val publicEpisodeCount: Int? = null,
    val favorite: Boolean? = null,
)

/** A server image remains a typed locator; no made-up HTTP address or tag. */
internal sealed interface MediaPresentationImage {
    data class Library(val artwork: LibraryArtwork) : MediaPresentationImage
    data class Server(val scope: String, val item: String, val role: String, val tag: String) : MediaPresentationImage
}

/** Match the server detail's portrait/poster and wide/backdrop preference. */
internal fun MediaPresentationData.heroArtwork(portrait: Boolean): MediaPresentationImage? {
    val primary = images["poster"] ?: images["still"]
    return if (portrait) primary ?: images["backdrop"] else images["backdrop"] ?: primary
}

internal data class MediaPresentationVersion(
    val key: String, val available: Boolean, val positionMs: Long,
    val measuredDurationMs: Long?, val watched: Boolean?,
)

internal fun libraryPresentation(card: HomeCard, details: LibraryDetails, versions: List<HomeVersion>): MediaPresentationData =
    MediaPresentationData("catalog:${card.id}", card.card.kind, card.card.title, card.card.plot, card.card.year,
        details.runtimeMinutes?.toLong()?.times(60_000), buildMap {
            (details.poster ?: card.card.poster?.let { LibraryArtwork(it, card.card.posterVersion) })?.let { put(if (card.card.kind == "episode") "still" else "poster", MediaPresentationImage.Library(it)) }
            details.backdrop?.let { put("backdrop", MediaPresentationImage.Library(it)) }
            details.logo?.let { put("logo", MediaPresentationImage.Library(it)) }
        }, details.people, details.genres, details.ratings.firstOrNull(),
        versions.map { MediaPresentationVersion("catalog-version:${it.id}", true, it.position,
            it.duration.takeIf { duration -> duration > 0 }, it.watched) },
        publicEpisodeCount = details.episodeCount)

internal fun EmbyItem.presentationData(scope: String): MediaPresentationData = MediaPresentationData(
    "server:$scope:$id", type.lowercase(), name, overview.takeIf(String::isNotBlank), year, durationMs,
    buildMap {
        if (imageTag.isNotBlank()) put(if (type == "Episode") "still" else "poster", MediaPresentationImage.Server(scope, imageId, "Primary", imageTag))
        backdropTags.firstOrNull()?.let { put("backdrop", MediaPresentationImage.Server(scope, backdropId, "Backdrop", it)) }
        if (logoTag.isNotBlank()) put("logo", MediaPresentationImage.Server(scope, logoId, "Logo", logoTag))
    }, people.map { LibraryCredit("server:$scope:${it.id}", it.name, it.type, it.role) }, genres,
    rating?.let { LibraryRating("server:$scope", it) },
    if (playable) listOf(MediaPresentationVersion("server:$scope:$id", true, resumeMs,
        durationMs?.takeIf { it > 0 }, played.takeIf { userStateKnown })) else emptyList(),
    season = seasonIndex, episode = index, favorite = favorite.takeIf { userStateKnown })

/** Only the requested local window is read. A fresh home never contacts the public API. */
internal fun CatalogDatabase.presentationData(entity: Long, source: String? = null): MediaPresentationData? = home {
    byIds(listOf(entity), source).firstOrNull()?.let { libraryPresentation(it, details(entity, source), versions(entity, source)) }
}
