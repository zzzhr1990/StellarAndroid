package cn.coldlake.stellar.opensource.playback.reporting

import android.net.Uri
import android.provider.DocumentsContract
import cn.coldlake.stellar.opensource.medialibrary.catalog.NasLocator
import cn.coldlake.stellar.opensource.medialibrary.servers.ServerProfile
import cn.coldlake.stellar.opensource.medialibrary.library.remoteLibraryPath
import java.security.MessageDigest

internal data class PlaybackRecordKey(val value: String)

/** Length framing avoids delimiter collisions. Only stable identities enter the hash, never tokens/URLs. */
internal object PlaybackIdentity {
    fun key(vararg parts: String): PlaybackRecordKey = PlaybackRecordKey(MessageDigest.getInstance("SHA-256")
        .digest((listOf("local-user", "v1") + parts).joinToString("") { "${it.length}:$it" }.toByteArray())
        .joinToString("") { "%02x".format(it) })

    fun server(profile: ServerProfile, item: String, version: String) =
        key("server", profile.id, profile.embyServerId, profile.embyUserId, item, version)

    fun remote(profile: ServerProfile, path: String): PlaybackRecordKey {
        val root = remoteLibraryPath(profile, path)
        return key("nas", profile.id, root.scope, path.trimEnd('/'))
    }

    fun discTitle(base: PlaybackRecordKey, title: String) = PlaybackRecordKey("disc:" + key("disc-title", base.value, title).value)
    fun locator(raw: String, title: String? = null): PlaybackRecordKey? = runCatching {
        val uri = Uri.parse(raw.removePrefix("local:"))
        val base = when (uri.scheme) {
            "stellar-server" -> {
                val version = uri.getQueryParameter("mediaSource") ?: return null
                val identity = listOf(uri.authority, uri.getQueryParameter("server"), uri.getQueryParameter("user"), uri.lastPathSegment, version)
                if (identity.any { it.isNullOrBlank() }) return null
                key("server", *identity.map { checkNotNull(it) }.toTypedArray())
            }
            "stellar-nas" -> key("nas", uri.authority.orEmpty(), uri.getQueryParameter("scope").orEmpty(),
                NasLocator.path(raw).trimEnd('/'))
            "stellar-cloud" -> key("cloud", uri.authority.orEmpty(), uri.getQueryParameter("provider").orEmpty(),
                uri.getQueryParameter("account").orEmpty(), uri.getQueryParameter("drive").orEmpty(), uri.getQueryParameter("object").orEmpty(), uri.getQueryParameter("version").orEmpty())
            "content" -> key("document", uri.authority.orEmpty(),
                runCatching { DocumentsContract.getDocumentId(uri) }.getOrElse { uri.path.orEmpty() })
            "file" -> key("file", uri.path.orEmpty())
            else -> return null // Arbitrary HTTP and transient session IDs have no durable identity.
        }
        if (title == null) base else discTitle(base, title)
    }.getOrNull()
}

internal data class ServerBaseline(val positionMs: Long, val played: Boolean, val lastPlayed: String,
    val version: String?)
internal data class PlaybackRecord(val key: PlaybackRecordKey, val positionMs: Long, val durationMs: Long?,
    val watched: Boolean, val resumeEligible: Boolean, val lastWatchedAt: Long, val revision: Long,
    val session: String, val sequence: Long, val ended: Boolean)
internal data class PlaybackDelivery(val revision: Long, val acknowledged: Long, val state: DeliveryState,
    val baseline: ServerBaseline?, val confirmationPending: Boolean = false)
internal data class ResumeChoice(val positionMs: Long, val serverPositionMs: Long? = null) {
    val conflict get() = serverPositionMs != null
}
internal object ResumeResolver {
    fun resolve(local: PlaybackRecord?, delivery: PlaybackDelivery?, server: ServerBaseline?, active: Boolean = false): ResumeChoice {
        if (local == null) return ResumeChoice(server?.positionMs ?: 0)
        val position = if (local.resumeEligible) local.positionMs else 0
        if (active || server == null) return ResumeChoice(position)
        // A server's item position cannot be copied to every different cut of the same item.
        if (delivery == null) return if (local.positionMs == server.positionMs && local.watched == server.played)
            ResumeChoice(server.positionMs) else ResumeChoice(position, server.positionMs)
        if (delivery.acknowledged >= local.revision && delivery.state == DeliveryState.Acknowledged && !delivery.confirmationPending)
            return ResumeChoice(server.positionMs)
        if (delivery.baseline == server) return ResumeChoice(position)
        if (local.positionMs == server.positionMs && local.watched == server.played) return ResumeChoice(position)
        return ResumeChoice(position, server.positionMs)
    }
}
