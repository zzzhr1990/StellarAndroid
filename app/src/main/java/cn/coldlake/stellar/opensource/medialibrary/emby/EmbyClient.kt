package cn.coldlake.stellar.opensource.medialibrary.emby

import android.content.Context
import cn.coldlake.stellar.opensource.network.MediaSecuritySettings
import cn.coldlake.stellar.opensource.network.MAX_MEDIA_API_RESPONSE_BYTES
import cn.coldlake.stellar.opensource.network.MAX_IMAGE_BYTES
import cn.coldlake.stellar.opensource.network.ignoreMediaCertificateErrors
import cn.coldlake.stellar.opensource.medialibrary.servers.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import cn.coldlake.stellar.opensource.network.HttpRateLimited
import cn.coldlake.stellar.opensource.network.checkRateLimit
import cn.coldlake.stellar.opensource.network.rateLimitedRead

/** All authenticated requests stay at the configured endpoint. No URLs/tokens in diagnostics. */
internal class EmbyClient(val profile: ServerProfile, val verifyTlsCertificates: Boolean = true) : Closeable {
    private val protocol = MediaServerProtocol(profile)
    private val base = protocol.base
    private val http = HttpClient(OkHttp) {
        followRedirects = false
        expectSuccess = false
        engine { config {
            followRedirects(false); followSslRedirects(false); retryOnConnectionFailure(false)
            if (!verifyTlsCertificates) ignoreMediaCertificateErrors()
        } }
        install(HttpTimeout) { connectTimeoutMillis = 5_000; socketTimeoutMillis = 15_000; requestTimeoutMillis = 20_000 }
    }
    private fun url(vararg parts: String) = URLBuilder(base).apply {
        appendPathSegments(*parts, encodeSlash = true)
    }.buildString()
    private fun HttpRequestBuilder.identify(authenticated: Boolean = true) {
        if (authenticated) {
            if (profile.embyToken.isEmpty() || profile.embyUserId.isEmpty() || profile.embyServerId.isEmpty())
                throw EmbyFailure(ServerError.AUTHENTICATION)
            if (!protocol.jellyfin) header("X-Emby-Token", profile.embyToken)
        }
        val (name, value) = protocol.authorization(authenticated)
        header(name, value)
    }
    private suspend fun <T> guarded(action: suspend () -> T): T = try { action() }
    catch (e: HttpRateLimited) { throw e }
    catch (e: EmbyFailure) { throw e }
    catch (e: TimeoutCancellationException) { throw EmbyFailure(ServerError.TIMEOUT) }
    catch (e: CancellationException) { throw e }
    catch (_: SSLException) { throw EmbyFailure(ServerError.TLS) }
    catch (_: HttpRequestTimeoutException) { throw EmbyFailure(ServerError.TIMEOUT) }
    catch (_: SocketTimeoutException) { throw EmbyFailure(ServerError.TIMEOUT) }
    catch (_: org.json.JSONException) { throw EmbyFailure(ServerError.PROTOCOL) }
    catch (_: Exception) { throw EmbyFailure(ServerError.NETWORK) }

    private fun check(response: HttpResponse) {
        checkRateLimit(response.status.value, response.headers["Retry-After"])
        if (response.status.value in 200..299) return
        throw EmbyFailure(when (response.status.value) {
            401 -> ServerError.AUTHENTICATION; 403 -> ServerError.PERMISSION; 404 -> ServerError.PATH
            408, 504 -> ServerError.TIMEOUT; else -> ServerError.PROTOCOL
        }, response.status.value)
    }
    private suspend fun bytes(response: HttpResponse, limit: Int): ByteArray {
        check(response)
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        val channel = response.bodyAsChannel()
        while (true) {
            val count = channel.readAvailable(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw EmbyFailure(ServerError.LIMIT)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private suspend fun json(parts: List<String>, query: Map<String, String> = emptyMap(),
        body: JSONObject? = null, authenticated: Boolean = true, verb: HttpMethod? = null): String = guarded { dataSlots.withPermit {
        http.prepareRequest(url(*protocol.route(parts).toTypedArray())) {
            identify(authenticated); method = verb ?: if (body == null) HttpMethod.Get else HttpMethod.Post
            if (protocol.jellyfin && parts.take(2) == listOf("Users", profile.embyUserId))
                parameter("UserId", profile.embyUserId)
            query.forEach { (key, value) -> parameter(key, value) }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body.toString()) }
        }.execute { bytes(it, MAX_MEDIA_API_RESPONSE_BYTES).toString(Charsets.UTF_8) }
    } }
    suspend fun authenticate(): ServerProfile {
        val reply = parseObject(json(listOf("Users", "AuthenticateByName"), body = JSONObject()
            .put("Username", profile.username).put("Pw", profile.password), authenticated = false))
        val user = reply.optJSONObject("User")?.optString("Id").orEmpty()
        val token = reply.optString("AccessToken")
        val server = reply.optString("ServerId")
        if (user.isBlank() || token.isBlank() || server.isBlank()) throw EmbyFailure(ServerError.PROTOCOL)
        return profile.copy(embyUserId = user, embyToken = token, embyServerId = server)
    }
    suspend fun verifyServer() {
        val info = rateLimitedRead { parseObject(json(listOf("System", "Info", "Public"), authenticated = false)) }
        if (info.optString("Id") != profile.embyServerId) throw EmbyFailure(ServerError.AUTHENTICATION)
    }
    suspend fun page(location: EmbyLocation, start: Int = 0): EmbyPage {
        val query = mutableMapOf("StartIndex" to start.toString(), "Limit" to "60", "EnableImages" to "true",
            "ImageTypeLimit" to "1", "Fields" to protocol.browseFields, "EnableUserData" to "true")
        val parts = mutableListOf("Users", profile.embyUserId)
        when (location.section) {
            EmbySection.LIBRARIES -> parts += "Views"
            EmbySection.LATEST -> { parts += listOf("Items", "Latest"); query["GroupItems"] = "true" }
            EmbySection.RESUME -> {
                parts += listOf("Items", "Resume")
                // Emby Web scopes Continue Watching to video. Emby 4.10 can return
                // an empty list when MediaTypes is omitted, despite saved progress.
                query["MediaTypes"] = "Video"
                query["Recursive"] = "true"
            }
            EmbySection.GENRES -> { parts.clear(); parts += "Genres"; query["UserId"] = profile.embyUserId }
            EmbySection.TAGS -> {
                val names = filters(location.parent).tags
                return EmbyPage(names.map { EmbyItem("tag:$it", it, "Tag", true) }, names.size, names.size)
            }
            else -> {
                parts += "Items"
                if (location.section == EmbySection.SEARCH) {
                    query["SearchTerm"] = location.query; query["Recursive"] = "true"
                    query["IncludeItemTypes"] = "Movie,Series,Episode,Video,MusicVideo,Audio,MusicAlbum,Person"
                }
            }
        }
        location.parent?.let { query["ParentId"] = it.id }
        query["SortBy"] = if (location.parent?.type in setOf("Series", "Season")) "ParentIndexNumber,IndexNumber,SortName" else location.sort
        query["SortOrder"] = if (location.descending && location.parent?.type !in setOf("Series", "Season")) "Descending" else "Ascending"
        if (location.section == EmbySection.GENRES) { query["SortBy"] = "SortName"; query["SortOrder"] = "Ascending" }
        if (location.recursive || location.section in setOf(EmbySection.FAVORITES, EmbySection.RELATED, EmbySection.COLLECTIONS, EmbySection.GENRES)) query["Recursive"] = "true"
        if (location.section == EmbySection.COLLECTIONS) query["IncludeItemTypes"] = "BoxSet"
        else if (location.itemTypes.isNotEmpty()) query["IncludeItemTypes"] = location.itemTypes
        else if (location.section == EmbySection.FOLDER && location.parent?.type == "CollectionFolder") {
            when (location.parent.collectionType) {
                "tvshows" -> { query["Recursive"] = "true"; query["IncludeItemTypes"] = "Series" }
                "movies" -> { query["Recursive"] = "true"; query["IncludeItemTypes"] = "Movie" }
            }
        }
        if (location.favoriteOnly || location.section == EmbySection.FAVORITES) query["IsFavorite"] = "true"
        location.played?.let { query["IsPlayed"] = it.toString() }
        listOf("Genres" to location.genre, "Tags" to location.tag, "Studios" to location.studio,
            "PersonIds" to location.person, "Years" to location.year).filter { it.second.isNotBlank() }.forEach { query[it.first] = it.second }
        val raw = json(parts, query)
        return parsePage(raw, start)
    }
    private fun parsePage(raw: String, start: Int = 0): EmbyPage {
        val objectValue = if (raw.trimStart().startsWith("[")) null else parseObject(raw)
        val array = try { if (objectValue == null) JSONArray(raw) else objectValue.getJSONArray("Items") }
            catch (_: org.json.JSONException) { throw EmbyFailure(ServerError.PROTOCOL) }
        val items = try { (0 until array.length()).map { item(array.getJSONObject(it)) } }
            catch (_: org.json.JSONException) { throw EmbyFailure(ServerError.PROTOCOL) }
        val next = start + array.length()
        val total = objectValue?.optInt("TotalRecordCount", next) ?: next // Latest is a bounded shelf.
        return EmbyPage(items, total.coerceAtLeast(next), next)
    }
    suspend fun detail(id: String): EmbyItem = item(parseObject(json(listOf("Users", profile.embyUserId, "Items", id))))

    /** Dedicated light catalog projection: no MediaSources, PlaybackInfo, streams, or per-item details. */
    suspend fun catalogPage(parentId: String, start: Int): JSONObject = rateLimitedRead { parseObject(json(listOf("Users", profile.embyUserId, "Items"),
        mapOf("ParentId" to parentId, "Recursive" to "true", "IncludeItemTypes" to "Movie,Series,Season,Episode",
            "StartIndex" to start.toString(), "Limit" to "128", "SortBy" to "SortName", "SortOrder" to "Ascending",
            "Fields" to "ProviderIds,Overview,DateCreated,ParentId,People,Genres,Taglines,CommunityRating,CriticRating,OfficialRating,PremiereDate", "EnableUserData" to "true", "EnableImages" to "true", "ImageTypeLimit" to "1"))) }

    suspend fun seasons(series: EmbyItem): EmbyPage = parsePage(json(listOf("Shows", series.id, "Seasons"),
        mapOf("UserId" to profile.embyUserId, "Fields" to protocol.browseFields, "EnableImages" to "true")))
    suspend fun episodes(seriesId: String, seasonId: String = "", limit: Int = 60, start: Int = 0): EmbyPage = parsePage(json(
        listOf("Shows", seriesId, "Episodes"), buildMap {
            put("UserId", profile.embyUserId); put("Fields", protocol.browseFields); put("Limit", limit.toString()); put("EnableImages", "true")
            if (seasonId.isNotEmpty()) put("SeasonId", seasonId)
            put("StartIndex", start.toString())
        }), start)
    suspend fun similar(item: EmbyItem): EmbyPage = parsePage(json(listOf("Items", item.id, "Similar"),
        mapOf("UserId" to profile.embyUserId, "Limit" to "20", "Fields" to protocol.browseFields)))
    suspend fun filters(parent: EmbyItem?): EmbyFilters {
        if (protocol.jellyfin) {
            return filterBase(parent).copy(studios=names("Studios",parent))
        }
        return coroutineScope {
            val genres = async { names("Genres", parent) }; val years = async { names("Years", parent) }
            val studios = async { names("Studios", parent) }; val tags = async { names("Tags", parent) }
            EmbyFilters(genres.await(), years.await(), studios.await(), tags.await())
        }
    }
    suspend fun filterBase(parent: EmbyItem?): EmbyFilters {
        val reply=parseObject(json(listOf("Items","Filters"),buildMap {
            put("UserId",profile.embyUserId); parent?.let { put("ParentId",it.id) }
        }))
        return EmbyFilters(reply.strings("Genres"),reply.strings("Years"),tags=reply.strings("Tags"))
    }
    suspend fun names(endpoint: String, parent: EmbyItem?): List<String> {
        val result = mutableListOf<String>(); var start = 0
        do {
            val reply = parseObject(json(listOf(endpoint), buildMap {
                put("UserId", profile.embyUserId); put("Recursive", "true"); put("StartIndex", start.toString()); put("Limit", "200")
                parent?.let { put("ParentId", it.id) }
            }))
            val array = reply.optJSONArray("Items") ?: throw EmbyFailure(ServerError.PROTOCOL)
            result += (0 until array.length()).map { array.optJSONObject(it)?.text("Name") ?: array.optString(it) }
            start += array.length()
            if (start > 4000) throw EmbyFailure(ServerError.LIMIT)
        } while (array.length() > 0 && start < reply.optInt("TotalRecordCount", start))
        return result.filter { it.isNotBlank() }.distinct()
    }
    suspend fun setUserFlag(item: EmbyItem, favorite: Boolean, value: Boolean) {
        json(listOf("Users", profile.embyUserId, if (favorite) "FavoriteItems" else "PlayedItems", item.id),
            verb = if (value) HttpMethod.Post else HttpMethod.Delete)
    }

    suspend fun playbackInfo(item: EmbyItem, sourceId: String? = null): EmbyPlaybackInfo {
        val device = JSONObject().put("Name", "StellarPlayer").put("MaxStreamingBitrate", 1_000_000_000L)
            .put("DirectPlayProfiles", JSONArray().put(JSONObject().put("Type", "Video"))
                .put(JSONObject().put("Type", "Audio")))
        if (protocol.jellyfin) {
            val subtitles = JSONArray()
            listOf("ass", "ssa", "srt", "subrip", "vtt", "webvtt", "pgssub", "dvdsub", "dvbsub", "mov_text", "text").forEach {
                subtitles.put(JSONObject().put("Format", it).put("Method", "Embed"))
            }
            device.put("SubtitleProfiles", subtitles)
        }
        val body = JSONObject().put("UserId", profile.embyUserId).put("DeviceProfile", device)
            .put("EnableDirectPlay", protocol.jellyfin).put("EnableDirectStream", !protocol.jellyfin).put("EnableTranscoding", false)
            .put("IsPlayback", sourceId != null).put("AutoOpenLiveStream", false).put("MaxStreamingBitrate", 1_000_000_000L)
        // MPV selects embedded tracks from the original file. Do not let the server's
        // default subtitle require burn-in (or an external subtitle URL we cannot use).
        if (protocol.jellyfin) body.put("SubtitleStreamIndex", -1)
        sourceId?.let { body.put("MediaSourceId", it) }
        val reply = parseObject(json(listOf("Items", item.id, "PlaybackInfo"), body = body))
        val array = reply.optJSONArray("MediaSources") ?: throw EmbyFailure(ServerError.PROTOCOL)
        val versions = (0 until array.length()).map { index ->
            val value = array.getJSONObject(index)
            EmbyVersion(value.getString("Id"), value.optString("Name").ifBlank { item.name },
                value.optLong("RunTimeTicks").takeIf { it > 0 }?.let(::ticksToMilliseconds),
                (if (protocol.jellyfin) value.optBoolean("SupportsDirectPlay") else value.optBoolean("SupportsDirectStream")) && !value.optBoolean("RequiresOpening") &&
                    !value.optBoolean("IsInfiniteStream") && value.optString("VideoType") !in setOf("Dvd", "Iso", "Bluray"),
                playMethod = if (protocol.jellyfin) "DirectPlay" else "DirectStream")
        }
        val session = reply.optString("PlaySessionId")
        if (session.isBlank() || versions.isEmpty()) throw EmbyFailure(ServerError.PROTOCOL)
        return EmbyPlaybackInfo(session, versions)
    }
    suspend fun report(suffix: String, body: JSONObject) {
        require(suffix in setOf("", "Progress", "Stopped"))
        // Reporting has its own short deadline and never queues behind directory pagination slots.
        guarded {
            http.preparePost(url(*(listOf("Sessions", "Playing") + if (suffix.isEmpty()) emptyList() else listOf(suffix)).toTypedArray())) {
                identify(); contentType(ContentType.Application.Json); setBody(body.toString())
                timeout { requestTimeoutMillis = 5_000; socketTimeoutMillis = 5_000 }
            }.execute { check(it) }
        }
    }
    suspend fun image(item: EmbyItem, type: String = "Primary", index: Int = 0, tag: String = item.imageTag): ByteArray = guarded {
        val id = when (type) { "Backdrop" -> item.backdropId; "Logo" -> item.logoId; "Chapter" -> item.id; else -> item.imageId }
        http.prepareGet(url("Items", id, "Images", type, index.toString())) {
            identify(); parameter("MaxWidth", if (type in setOf("Backdrop", "Logo")) "1080" else "480")
            parameter("Quality", "90"); parameter("Tag", tag)
        }.execute { bytes(it, MAX_IMAGE_BYTES) }
    }
    override fun close() { http.close() }
    companion object {
        internal val dataSlots = Semaphore(3)
        fun fromSettings(context: Context, profile: ServerProfile) =
            EmbyClient(profile, verifyTlsCertificates = !MediaSecuritySettings(context).ignoreCertificateErrors)
        const val BROWSE_FIELDS = "ProductionYear,EndDate,Status,PrimaryImageAspectRatio,CommunityRating,PremiereDate,Genres,Overview,ParentId,People,Studios,ExternalUrls,OfficialRating,TagItems,Taglines,DateCreated,ChildCount"
        private fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { a ->
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() && it != "null" }
        }.orEmpty()
        private fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        }.orEmpty()
        private fun parseObject(raw: String): JSONObject = try { JSONObject(raw) }
            catch (_: org.json.JSONException) { throw EmbyFailure(ServerError.PROTOCOL) }
        private fun JSONObject.text(key: String, fallback: String = ""): String =
            if (isNull(key)) fallback else optString(key, fallback)
        internal fun item(value: JSONObject): EmbyItem {
            val id = value.text("Id").takeIf { it.isNotBlank() } ?: throw EmbyFailure(ServerError.PROTOCOL)
            val user = value.optJSONObject("UserData")
            val ownTag = value.optJSONObject("ImageTags")?.text("Primary").orEmpty().ifEmpty { value.text("PrimaryImageTag") }
            val seriesTag = value.text("SeriesPrimaryImageTag")
            return EmbyItem(id, value.text("Name"), value.text("Type"), value.optBoolean("IsFolder"),
                value.text("MediaType") == "Audio" || value.text("Type") == "Audio",
                value.text("Overview"), value.optInt("ProductionYear").takeIf { it > 0 },
                value.optLong("RunTimeTicks").takeIf { it > 0 }?.let(::ticksToMilliseconds),
                ticksToMilliseconds(user?.optLong("PlaybackPositionTicks") ?: 0), user?.optBoolean("Played") ?: false,
                ownTag.ifEmpty { seriesTag }, if (ownTag.isEmpty() && seriesTag.isNotEmpty()) value.text("SeriesId", id) else id,
                backdropTags = value.strings("BackdropImageTags").ifEmpty { value.strings("ParentBackdropImageTags") },
                backdropId = if (value.strings("BackdropImageTags").isEmpty()) value.text("ParentBackdropItemId", id) else id,
                logoTag = value.optJSONObject("ImageTags")?.text("Logo").orEmpty().ifEmpty { value.text("ParentLogoImageTag") },
                logoId = if (value.optJSONObject("ImageTags")?.text("Logo").isNullOrEmpty()) value.text("ParentLogoItemId", id) else id,
                rating = value.optDouble("CommunityRating").takeIf { it.isFinite() && it > 0 },
                imdbId = value.optJSONObject("ProviderIds")?.let { ids ->
                    ids.keys().asSequence().firstOrNull { it.equals("imdb", true) }?.let { ids.optString(it) }
                }.orEmpty(),
                criticRating = value.optDouble("CriticRating").takeIf { it.isFinite() && it in 0.0..100.0 },
                lastPlayedDate = user?.optString("LastPlayedDate").orEmpty(),
                dateCreated = value.text("DateCreated"),
                officialRating = value.text("OfficialRating"), endYear = value.text("EndDate").take(4).toIntOrNull(),
                status = value.text("Status"), favorite = user?.optBoolean("IsFavorite") ?: false,
                unplayed = user?.optInt("UnplayedItemCount") ?: 0, genres = value.strings("Genres"),
                tags = value.strings("Tags").ifEmpty { value.objects("TagItems").map { it.text("Name") } },
                studios = value.objects("Studios").map { EmbyNamed(it.text("Id"), it.text("Name")) },
                people = value.objects("People").map { EmbyPerson(it.text("Id"), it.text("Name"),
                    it.text("Role"), it.text("Type"), it.text("PrimaryImageTag")) },
                tagline = value.strings("Taglines").firstOrNull().orEmpty(), premiere = value.text("PremiereDate"),
                birthPlaces = value.strings("ProductionLocations"), seriesId = value.text("SeriesId"),
                seriesName = value.text("SeriesName"), seasonId = value.text("SeasonId"),
                index = value.optInt("IndexNumber", -1).takeIf { it >= 0 }, seasonIndex = value.optInt("ParentIndexNumber", -1).takeIf { it >= 0 },
                seasonCount = value.optInt("ChildCount"), collectionType = value.text("CollectionType"),
                links = value.objects("ExternalUrls").mapNotNull {
                    val link = it.text("Url"); val uri = runCatching { java.net.URI(link) }.getOrNull()
                    if (uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrEmpty() && uri?.userInfo == null)
                        EmbyLink(it.text("Name"), link) else null
                },
                chapters = value.objects("Chapters").mapIndexed { n, it -> EmbyChapter(it.text("Name"),
                    ticksToMilliseconds(it.optLong("StartPositionTicks")), it.text("ImageTag"), n) },
                imageAspectRatio = value.optDouble("PrimaryImageAspectRatio").takeIf { it.isFinite() && it > 0 },
                sources = value.objects("MediaSources").map { source ->
                    EmbySource(source.text("Id"), source.text("Name"), source.text("Container"),
                        source.optLong("Size"), source.optLong("Bitrate"), source.objects("MediaStreams").map { track ->
                            EmbyTrack(track.optInt("Index"), track.text("Type"), track.text("DisplayTitle").ifBlank { track.text("Title") },
                                track.text("Language"), track.text("Codec"), track.optBoolean("IsExternal"),
                                track.optInt("Width"), track.optInt("Height"), track.optInt("Channels"), track.optLong("BitRate"),
                                track.optInt("BitDepth"), track.optDouble("RealFrameRate", 0.0), track.text("PixelFormat"),
                                track.text("VideoRange"), track.text("VideoRangeType"), track.text("ExtendedVideoType"),
                                track.text("ColorTransfer"), track.optInt("DvProfile"))
                        }, fileName = source.text("DisplayFileName").ifBlank {
                            embyDisplayFileName(source.text("Path"), source.text("Name"), source.text("Container"))
                        })
                })
        }
    }
}
