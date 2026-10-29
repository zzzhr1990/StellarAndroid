package cn.coldlake.stellar.opensource.medialibrary

import com.stellar.player.contract.MediaDescriptor
import com.stellar.player.contract.MediaKind
import java.net.URI

/** Validation does not resolve the host or contact the URL. Preserve signed query strings. */
internal fun networkMedia(input: String): MediaDescriptor? {
    val value = input.trim()
    if (value.isEmpty() || value.length > 8192 || value.any { it.isWhitespace() || it.isISOControl() }) return null
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() ||
        uri.port !in -1..65535 || uri.port == 0) return null
    val title = uri.path.orEmpty().substringAfterLast('/').ifBlank { uri.host }
    return MediaDescriptor("network:$value", title, MediaKind.VIDEO, null)
}

internal fun isRealMedia(id: String) = id.startsWith("bluray-session:") || id.startsWith("dvd-session:") || id.startsWith("local:") || id.startsWith("network:") || id.startsWith("smb-session:") || id.startsWith("nfs-session:") || id.startsWith("webdav-session:") || id.startsWith("emby-session:") || id.startsWith("drive-session:") || id.startsWith("onedrive-session:")

internal fun resolveRealMedia(id: String): String? = when {
    id.startsWith("local:") -> id.removePrefix("local:")
    id.startsWith("network:") -> id.removePrefix("network:").takeIf { networkMedia(it) != null }
    else -> null
}
