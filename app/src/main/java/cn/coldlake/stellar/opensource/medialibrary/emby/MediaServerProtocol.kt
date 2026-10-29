package cn.coldlake.stellar.opensource.medialibrary.emby

import cn.coldlake.stellar.opensource.medialibrary.servers.ServerKind
import cn.coldlake.stellar.opensource.medialibrary.servers.ServerProfile
import com.stellar.player.source.HttpSource
import io.ktor.http.*

/** Wire differences only. Both playback backends share browser, authentication and reporting. */
internal class MediaServerProtocol(private val profile: ServerProfile) {
    val jellyfin = profile.kind == ServerKind.JELLYFIN
    val base = mediaServerBase(profile.address, profile.kind)
    val browseFields = if (jellyfin)
        "PrimaryImageAspectRatio,Genres,Overview,ParentId,People,Studios,ExternalUrls,Tags,Taglines,DateCreated,ChildCount"
    else EmbyClient.BROWSE_FIELDS

    fun authorization(authenticated: Boolean): Pair<String, String> {
        val identity = "Client=\"StellarPlayer\", Device=\"Android\", DeviceId=\"${profile.id}\", Version=\"0.19.0\""
        return if (jellyfin) "Authorization" to ("MediaBrowser $identity" +
            if (authenticated) ", Token=\"${profile.embyToken}\"" else "")
        else "X-Emby-Authorization" to "Emby $identity"
    }

    fun playbackSource(item: EmbyItem, version: EmbyVersion, session: String, verifyTlsCertificates: Boolean): HttpSource {
        require(profile.embyToken.isNotEmpty() && profile.embyUserId.isNotEmpty() && profile.embyServerId.isNotEmpty())
        val address = URLBuilder(base).apply {
            appendPathSegments(if (item.audio) "Audio" else "Videos", item.id, "stream", encodeSlash = true)
            parameters.append("Static", "true")
            parameters.append("MediaSourceId", version.id)
            parameters.append("PlaySessionId", session)
            parameters.append("DeviceId", profile.id)
        }.buildString()
        val headers = buildMap {
            val (name, value) = authorization(true)
            put(name, value)
            if (!jellyfin) put("X-Emby-Token", profile.embyToken)
        }
        return HttpSource(address, headers, verifyTlsCertificates = verifyTlsCertificates)
    }

    /** Jellyfin 12 canonical routes; keep Emby endpoints byte-for-byte unchanged. */
    fun route(parts: List<String>): List<String> {
        if (!jellyfin || parts.take(2) != listOf("Users", profile.embyUserId)) return parts
        val tail = parts.drop(2)
        return when (tail.firstOrNull()) {
            "Views" -> listOf("UserViews")
            "FavoriteItems" -> listOf("UserFavoriteItems") + tail.drop(1)
            "PlayedItems" -> listOf("UserPlayedItems") + tail.drop(1)
            "Items" -> if (tail.getOrNull(1) == "Resume") listOf("UserItems", "Resume") else tail
            else -> parts
        }
    }
}
