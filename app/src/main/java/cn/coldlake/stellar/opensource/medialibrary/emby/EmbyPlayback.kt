package cn.coldlake.stellar.opensource.medialibrary.emby

import com.stellar.player.contract.*
import cn.coldlake.stellar.opensource.medialibrary.servers.ServerError
import cn.coldlake.stellar.opensource.medialibrary.servers.ServerProfile
import java.io.Closeable
import java.util.UUID

/** Negotiated playback identity and source; preparing this object never starts a viewing session. */
internal class EmbyPlayback(val profile: ServerProfile, val item: EmbyItem, val version: EmbyVersion,
    val playSessionId: String, val resumeMs: Long, val verifyTlsCertificates: Boolean = true,
    val id: String = "emby-session:${UUID.randomUUID()}",
    internal val onReportError: (ServerError?) -> Unit = {}) : Closeable {
    val source = MediaServerProtocol(profile).playbackSource(item, version, playSessionId, verifyTlsCertificates)
    val media = MediaDescriptor(id, episodeTitle(item), if (item.audio) MediaKind.AUDIO else MediaKind.VIDEO, version.durationMs)
    var episodeSiblings: List<EmbyItem> = emptyList()
    var initialAudioId: String? = null
    var initialSubtitleId: String? = null
    // Prepared source only. The application reporting runtime owns all persistence and delivery.
    override fun close() = Unit
}
