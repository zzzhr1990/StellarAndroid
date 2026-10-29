package cn.coldlake.stellar.opensource.medialibrary.servers

import java.net.URI
import java.util.UUID

internal enum class ServerKind { WEBDAV, SMB, NFS, EMBY, JELLYFIN, GOOGLE_DRIVE, ONEDRIVE;
    val cloud get() = this == GOOGLE_DRIVE || this == ONEDRIVE
    val remote get() = this == WEBDAV || this == SMB || this == NFS || cloud
    val mediaServer get() = this == EMBY || this == JELLYFIN
    val supported get() = remote || mediaServer
}

/** Portable values only; credentials are deliberately stored as plain SQLite TEXT. */
internal data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val kind: ServerKind,
    val name: String = "",
    val address: String = "",
    val port: String = when (kind) { ServerKind.SMB -> "445"; ServerKind.NFS -> "2049"; else -> "" },
    val share: String = "",
    val path: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val nfsVersion: Int = 3,
    val uid: String = "",
    val gid: String = "",
    val accountSync: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val cloudAccountId: String = "",
    val embyUserId: String = "",
    val embyToken: String = "",
    val embyServerId: String = "",
) {
    // Never include endpoint credentials in generated diagnostic strings.
    override fun toString() = "ServerProfile(id=$id, kind=$kind)"

    fun normalized(): ServerProfile? {
        val value = copy(name = name.trim(), address = address.trim(), port = port.trim(),
            share = share.trim(), path = path.trim(), username = username.trim(), domain = domain.trim(),
            uid = uid.trim(), gid = gid.trim())
        if (value.name.isEmpty() || value.name.length > 120 || value.address.isEmpty() ||
            listOf(value.address, value.share, value.path, value.username, value.password, value.domain)
                .any { it.any { c -> c == '\u0000' || c == '\r' || c == '\n' } }) return null
        if (kind == ServerKind.ONEDRIVE) {
            if (value.address != cn.coldlake.stellar.opensource.medialibrary.onedrive.ONEDRIVE_ADDRESS ||
                value.username.isBlank() || value.username.length > 320 || cloudAccountId.isBlank() ||
                cloudAccountId.length > 256 || cloudAccountId.any { it.isISOControl() }) return null
            return value.copy(port = "", share = "", path = "", password = "", domain = "", uid = "", gid = "",
                accountSync = false, embyUserId = "", embyToken = "", embyServerId = "")
        }
        if (kind == ServerKind.GOOGLE_DRIVE) {
            if (value.address != cn.coldlake.stellar.opensource.medialibrary.drive.DRIVE_ADDRESS || value.username.isBlank() ||
                value.username.length > 320 || !value.username.contains('@')) return null
            return value.copy(port = "", share = "", path = "", password = "", domain = "", uid = "", gid = "",
                accountSync = false, embyUserId = "", embyToken = "", embyServerId = "")
        }
        if (kind == ServerKind.WEBDAV || !kind.remote) {
            if (kind.mediaServer && (value.username.isBlank() ||
                    runCatching { cn.coldlake.stellar.opensource.medialibrary.emby.mediaServerBase(value.address, kind) }.isFailure)) return null
            val uri = runCatching { URI(value.address) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() !in listOf("http", "https") || uri.host.isNullOrBlank() ||
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                uri.port != -1 && uri.port !in 1..65535) return null
            return value.copy(port = (if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80).toString())
        }
        if (value.address.any { it.isWhitespace() || it in "/\\@?#" } ||
            value.port.toIntOrNull() !in 1..65535) return null
        // A host only, including IPv6; the port has its own field.
        val host = value.address.removeSurrounding("[", "]")
        val parsed = runCatching { URI("http://${if (':' in host) "[$host]" else host}") }.getOrNull()
        if (parsed?.host == null || parsed.port != -1) return null
        if (kind == ServerKind.SMB) {
            if (value.share.isEmpty() || value.share.any { it in "/\\" } || value.share in listOf(".", "..")) return null
            val directory = value.path.replace('\\', '/').trim('/')
            if (directory.split('/').any { it == "." || it == ".." }) return null
            return value.copy(address = host, path = directory)
        }
        if (!value.path.startsWith('/') || value.path.split('/').any { it == "." || it == ".." } ||
            value.nfsVersion !in listOf(3, 4) || listOf(value.uid, value.gid).any { it.isNotEmpty() && (it.toIntOrNull() ?: -1) < 0 }) return null
        return value.copy(address = host, path = value.path.trimEnd('/').ifEmpty { "/" })
    }

    val endpoint: String get() {
        if (kind == ServerKind.ONEDRIVE) return "https://onedrive.com/"
        if (kind == ServerKind.GOOGLE_DRIVE) return "https://drive.google.com/"
        if (kind == ServerKind.WEBDAV || !kind.remote) return address
        val host = if (':' in address) "[$address]" else address
        return if (kind == ServerKind.SMB) "smb://$host:$port/$share" + if (path.isEmpty()) "" else "/$path"
        else "nfs://$host:$port$path"
    }
}
