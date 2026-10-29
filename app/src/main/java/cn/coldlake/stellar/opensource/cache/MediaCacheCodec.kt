package cn.coldlake.stellar.opensource.cache

import cn.coldlake.stellar.opensource.medialibrary.emby.*
import org.json.JSONArray
import org.json.JSONObject

internal data class MediaPayload(val items: List<EmbyItem> = emptyList(), val total: Int = 0, val next: Int = 0,
    val filters: EmbyFilters? = null) {
    fun page() = EmbyPage(items, total, next)
    fun empty() = items.isEmpty() && filters?.let { it.genres.isEmpty() && it.years.isEmpty() && it.studios.isEmpty() && it.tags.isEmpty() } != false
    fun staticOnly() = copy(items = items.map { it.copy(resumeMs = 0, played = false, favorite = false, unplayed = 0, userStateKnown = false) })
    companion object { fun from(page: EmbyPage) = MediaPayload(page.items, page.total, page.nextIndex) }
}
internal data class UserStamp(val favorite: Boolean, val played: Boolean, val resume: Long, val unplayed: Int,
    val time: CacheTime, val revision: Long) {
    fun apply(item: EmbyItem, clock: CacheClock) = if (time.age(clock) <= 120_000)
        item.copy(favorite = favorite, played = played, resumeMs = resume, unplayed = unplayed, userStateKnown = true)
        else item.copy(favorite = false, played = false, resumeMs = 0, unplayed = 0, userStateKnown = false)
    fun encode() = JSONObject().put("favorite", favorite).put("played", played).put("resume", resume).put("unplayed", unplayed).toString()
}

/** Explicit display-field whitelist. Stream paths, authentication and play sessions cannot enter this codec. */
internal object MediaCacheCodec {
    fun item(i: EmbyItem): String = itemObject(i).toString()
    private fun itemObject(i: EmbyItem): JSONObject = JSONObject().apply {
        put("Id",i.id); put("Name",i.name); put("Type",i.type); put("IsFolder",i.folder)
        put("MediaType",if(i.audio) "Audio" else "Video"); put("Overview",i.overview); put("ProductionYear",i.year)
        put("RunTimeTicks",i.durationMs?.let(::millisecondsToTicks)); put("PrimaryImageAspectRatio",i.imageAspectRatio)
        put("ImageTags", JSONObject().put("Primary",i.imageTag).put("Logo",i.logoTag)); put("BackdropImageTags",JSONArray(i.backdropTags))
        put("cacheImageId",i.imageId); put("cacheBackdropId",i.backdropId); put("cacheLogoId",i.logoId)
        put("CommunityRating",i.rating); put("OfficialRating",i.officialRating); put("EndDate",i.endYear?.toString()); put("Status",i.status)
        put("CriticRating",i.criticRating)
        put("DateCreated",i.dateCreated)
        put("ProviderIds",JSONObject().put("Imdb",i.imdbId))
        put("Genres",JSONArray(i.genres)); put("Tags",JSONArray(i.tags)); put("Taglines",JSONArray(listOf(i.tagline)))
        put("PremiereDate",i.premiere); put("ProductionLocations",JSONArray(i.birthPlaces)); put("SeriesId",i.seriesId)
        put("SeriesName",i.seriesName); put("SeasonId",i.seasonId); put("IndexNumber",i.index); put("ParentIndexNumber",i.seasonIndex)
        put("ChildCount",i.seasonCount); put("CollectionType",i.collectionType)
        put("Studios",JSONArray(i.studios.map { JSONObject().put("Id",it.id).put("Name",it.name) }))
        put("People",JSONArray(i.people.map { JSONObject().put("Id",it.id).put("Name",it.name).put("Role",it.role).put("Type",it.type).put("PrimaryImageTag",it.imageTag) }))
        put("ExternalUrls",JSONArray(i.links.map { JSONObject().put("Name",it.name).put("Url",it.url) }))
        put("Chapters",JSONArray(i.chapters.map { JSONObject().put("Name",it.name).put("StartPositionTicks",millisecondsToTicks(it.positionMs)).put("ImageTag",it.imageTag) }))
        put("MediaSources",JSONArray(i.sources.map { source -> JSONObject().put("Id",source.id).put("Name",source.name)
            .put("DisplayFileName",source.fileName)
            .put("Container",source.container).put("Size",source.size).put("Bitrate",source.bitrate).put("MediaStreams",JSONArray(source.tracks.map {
                JSONObject().put("Index",it.index).put("Type",it.type).put("DisplayTitle",it.title).put("Language",it.language)
                    .put("Codec",it.codec).put("IsExternal",it.external).put("Width",it.width).put("Height",it.height)
                    .put("Channels",it.channels).put("BitRate",it.bitrate).put("BitDepth",it.bitDepth).put("RealFrameRate",it.frameRate).put("PixelFormat",it.pixelFormat)
                    .put("VideoRange",it.videoRange).put("VideoRangeType",it.videoRangeType).put("ExtendedVideoType",it.extendedVideoType)
                    .put("ColorTransfer",it.colorTransfer).put("DvProfile",it.dvProfile)
            })) }))
    }
    fun decodeItem(value: String): EmbyItem = decodeItem(JSONObject(value))
    private fun decodeItem(json: JSONObject): EmbyItem {
        return EmbyClient.item(json).copy(imageId=json.getString("cacheImageId"), backdropId=json.getString("cacheBackdropId"),
            logoId=json.getString("cacheLogoId"), userStateKnown=false)
    }
    fun response(value: MediaPayload): String = JSONObject().put("items",JSONArray(value.items.map(::itemObject)))
        .put("total",value.total).put("next",value.next).apply { value.filters?.let {
            put("filters",JSONObject().put("genres",JSONArray(it.genres)).put("years",JSONArray(it.years))
                .put("studios",JSONArray(it.studios)).put("tags",JSONArray(it.tags)))
        } }.toString()
    fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
    fun response(value: String): MediaPayload {
        val json = JSONObject(value)
        val items = json.getJSONArray("items")
        val filters = json.optJSONObject("filters")?.let { EmbyFilters(strings(it.getJSONArray("genres")),strings(it.getJSONArray("years")),strings(it.getJSONArray("studios")),strings(it.getJSONArray("tags"))) }
        return MediaPayload((0 until items.length()).map { decodeItem(items.getJSONObject(it)) },json.getInt("total"),json.getInt("next"),filters)
    }
}
