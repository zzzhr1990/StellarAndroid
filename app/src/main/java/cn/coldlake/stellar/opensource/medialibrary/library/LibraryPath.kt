package cn.coldlake.stellar.opensource.medialibrary.library

import cn.coldlake.stellar.opensource.medialibrary.servers.ServerKind
import cn.coldlake.stellar.opensource.medialibrary.servers.ServerProfile
import java.net.URI
import java.util.Base64
import java.util.Locale
import java.util.UUID

internal enum class LibraryPathType { LOCAL, WEBDAV, SMB, NFS, GOOGLE_DRIVE, ONEDRIVE }

/** Portable folder identity. Credentials belong to the referenced server, never this table. */
internal data class LibraryPath(
    val id: String = UUID.randomUUID().toString(),
    val type: LibraryPathType,
    val sourceId: String,
    val scope: String,
    val path: String,
    val pathKey: String,
    val displayPath: String,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null,
    val revision: Long = 1,
) {
    fun sameLocation(other: LibraryPath) = type == other.type && sourceId == other.sourceId &&
        scope == other.scope && pathKey == other.pathKey
    fun covers(other: LibraryPath) = type == other.type && sourceId == other.sourceId && scope == other.scope &&
        (pathKey == other.pathKey || (type !in setOf(LibraryPathType.GOOGLE_DRIVE, LibraryPathType.ONEDRIVE) &&
            (pathKey.isEmpty() || other.pathKey.startsWith("$pathKey/"))))
}

// Encode each component separately: A/B must not match A/BC; encoded slashes stay in their component.
internal fun libraryPathKey(segments: List<String>) = segments.joinToString("/") {
    Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
}

internal fun remoteLibraryPath(profile: ServerProfile, location: String): LibraryPath {
    val type = when (profile.kind) {
        ServerKind.WEBDAV -> LibraryPathType.WEBDAV
        ServerKind.SMB -> LibraryPathType.SMB
        ServerKind.NFS -> LibraryPathType.NFS
        else -> error("Not a remote folder")
    }
    val scope: String
    val segments: List<String>
    val display: String
    if (type == LibraryPathType.WEBDAV) {
        val uri = URI(location)
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val host = uri.host.lowercase(Locale.ROOT)
        val port = uri.port.takeIf { it >= 0 } ?: if (scheme == "https") 443 else 80
        scope = "$scheme://$host:$port"
        segments = uri.rawPath.orEmpty().trim('/').split('/').filter(String::isNotEmpty).map {
            URI("http://placeholder/$it").path.removePrefix("/")
        }
        display = location
    } else {
        scope = listOf(profile.address.lowercase(Locale.ROOT), profile.port,
            if (type == LibraryPathType.SMB) profile.share else profile.path.trimEnd('/'),
            if (type == LibraryPathType.NFS) profile.nfsVersion.toString() else "").joinToString("|")
        segments = location.trim('/').split('/').filter(String::isNotEmpty)
        val base = if (type == LibraryPathType.SMB) profile.share else profile.path.trim('/')
        val host = if (':' in profile.address) "[${profile.address}]" else profile.address
        display = "${type.name.lowercase(Locale.ROOT)}://$host:${profile.port}/" +
            listOf(base, location.trim('/')).filter(String::isNotEmpty).joinToString("/")
    }
    return LibraryPath(type = type, sourceId = profile.id, scope = scope, path = location,
        pathKey = libraryPathKey(segments), displayPath = display)
}

internal sealed interface LibraryAddResult {
    data class Added(val entry: LibraryPath) : LibraryAddResult
    data class Covered(val entry: LibraryPath, val exact: Boolean) : LibraryAddResult
}

internal interface LibraryRepository {
    fun list(): List<LibraryPath>
    fun add(candidate: LibraryPath): LibraryAddResult
    fun delete(id: String)
}
